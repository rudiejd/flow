package com.vaadin.base.devserver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vaadin.flow.server.Version;
import com.vaadin.flow.server.startup.ApplicationConfiguration;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.stream.Stream;

public class VaadinUsageStatistics {


    /*
     * Client-side telemetry parameter.
     */
    public static final String TELEMETRY_PARAMETER = "vaadin_telemetry_data";


    /*
     * Name of the JSON file containing all the statistics.
     */
    private static final String STATISTICS_FILE_NAME = "usage-statistics.json";


    /*
     *  Reporting remote URL.
     */
    private static final String USAGE_REPORT_URL = "https://tools.vaadin.com/usage-stats/v2/submit";

    // Default data values and limits
    private static final String MISSING_DATA = "[NA]";
    private static final String DEFAULT_PROJECT_ID = "default-project-id";
    private static final String GENERATED_USERNAME = "GENERATED";
    private static final long TIME_MS_24H = 86400000L;
    private static final long TIME_MS_30D = 2592000000L;
    private static final int MAX_TELEMETRY_LENGTH = 1024*100; // 100k
    private static final String INVALID_SERVER_RESPONSE = "Invalid server response.";

    // External parameters and file names
    private static final String PARAMETER_PROJECT_SOURCE_ID = "project.source.id";
    public static final String PROPERTY_USER_HOME = "user.home";
    public static final String VAADIN_FOLDER_NAME = ".vaadin";
    public static final String USER_KEY_FILE_NAME = "userKey";

    // Meta fields for reporting and scheduling
    private static final String FIELD_LAST_SENT = "lastSent";
    private static final String FIELD_LAST_STATUS = "lastSendStatus";
    private static final String FIELD_SEND_INTERVAL = "reportInterval";
    private static final String FIELD_SERVER_MESSAGE = "serverMessage";

    // Data fields
    private static final String FIELD_PROJECT_ID = "id";
    private static final String FIELD_PROJECT_DEVMODE_STARTS = "devModeStarts";
    private static final String FIELD_PROJECT_DEVMODE_RELOADS = "devModeReloads";
    private static final String FIELD_OPEARATING_SYSTEM = "os";
    private static final String FIELD_JVM = "jvm";
    private static final String FIELD_FLOW_VERSION = "flowVersion";
    private static final String FIELD_SOURCE_ID = "sourceId";
    private static final String FIELD_PROKEY = "proKey";
    private static final String FIELD_USER_KEY = "userKey";
    public static final String FIELD_PROJECTS = "projects";

    private static String projectId;
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static ObjectNode json;
    private static ObjectNode projectJson;
    private static boolean usageStatisticsEnabled;
    private static String usageReportingUrl;

    private VaadinUsageStatistics() {
        // Only static methods here, no need to create an instance
    }

    /**
     * Initialize statistics module. This should be done on devmode startup.
     * First check if statistics collection is enabled.
     *
     *
     * @param config Application configuration parameters.
     * @param projectFolder Folder of the working project.
     */
    public static void init(ApplicationConfiguration config, String projectFolder) {
        setStatisticsEnabled(config != null
                && !config.isProductionMode()
                && config.isUsageStatisticsEnabled());
        if (isStatisticsEnabled()) {
            getLogger().debug("VaadinUsageStatistics enabled");
        } else {
            getLogger().debug("VaadinUsageStatistics disabled");
            return; // Do not go any further
        }

        projectId = generateProjectId(projectFolder);

        // Read the current statistics data
        json = readStatisticsJson();

        // Update the machine / user / source level data
        json.put(FIELD_OPEARATING_SYSTEM, getOperatingSystem());
        json.put(FIELD_JVM, getJVMVersion());
        json.put(FIELD_PROKEY, getProKey());
        json.put(FIELD_USER_KEY, getUserKey());

        // Find the project we are working on
        if (!json.has(FIELD_PROJECTS)) {
            json.set(FIELD_PROJECTS, jsonMapper.createArrayNode());
        }
        projectJson = findById(projectId, json.get(FIELD_PROJECTS), true);

        // Update basic project statistics and save
        projectJson.put(FIELD_FLOW_VERSION, Version.getFullVersion());
        projectJson.put(FIELD_SOURCE_ID, config.getStringProperty(PARAMETER_PROJECT_SOURCE_ID, MISSING_DATA));
        incrementJsonValue(projectJson, FIELD_PROJECT_DEVMODE_STARTS);
        writeStatisticsJson(json);

        // Send statistics, if enough time has passed
        if (isIntervalElapsed()) {
            String message = sendCurrentStatistics(getUsageReportingUrl());

            // Show message in System.out, if present
            if (message != null && !message.trim().isEmpty()) {
                getLogger().info(message);
            }
        }

    }


    /**
     *  Get the remote reporting URL.
     *
     *  Uses default method visibility to allow testing.
     *
     *  @see #setUsageReportingUrl(String)
     * @return By default return {@link #USAGE_REPORT_URL}. 
     */
    static String getUsageReportingUrl() {
        return usageReportingUrl == null? USAGE_REPORT_URL : usageReportingUrl;
    }

    /**
     *  Set the remote reporting URL.
     *
     *  This is meant for testing only, and not intended to be used otherwise.
     *
     * @see #getUsageReportingUrl()
     */
    public static void setUsageReportingUrl(String reportingUrl) {
        usageReportingUrl =reportingUrl;
    }

    /**
     *  Check if statistics are enabled for this project.
     *
     * @return true if statistics collection is enabled.
     */
    public static boolean isStatisticsEnabled() {
        return usageStatisticsEnabled;
    }

    /** Enable or disable statistics collection and sending.
     *
     * @param enabled true if statistics should be collected, false otherwise.
     */
    public static void setStatisticsEnabled(boolean enabled) {
        usageStatisticsEnabled = enabled;
    }

    /**
     * Get operating system identifier from system.
     *
     * @return os.name system property or MISSING_DATA
     */
    public static String getOperatingSystem() {
        String os = System.getProperty("os.name");
        return os == null  ? MISSING_DATA: os;
    }

    /**
     * Get operating JVM version identifier from system.
     *
     * @return os.name system property or MISSING_DATA
     */
    public static String getJVMVersion() {
        String os = System.getProperty("java.vm.name");
        os = (os == null ? MISSING_DATA : os);
        String ver = System.getProperty("java.specification.version");
        ver = (ver == null ? MISSING_DATA : ver);
        return os == null && ver == null? MISSING_DATA: os+ " / " +ver;
    }

    /**
     * Get Vaadin Pro key if available in the system, or generated id.
     *
     * Uses default method visibility to allow testing.
     *
     * @return Vaadin Pro Key or null
     */
    static String getProKey() {
            // Use the local proKey if present
            ProKey proKey = LocalProKey.get();
            return proKey.getProKey();
    }

    /**
     * Get generated user id.
     *
     * Uses default method visibility to allow testing.
     *
     * @return Generated user id, or null if unable to load or generate one.
     */
     static String getUserKey() {
        File userKeyFile = getUserKeyLocation();
        if (userKeyFile != null && userKeyFile.exists()) {
            try {
                ProKey localKey = ProKey.fromFile(userKeyFile);
                if (localKey != null && localKey.getProKey() != null) {
                    return localKey.getProKey();
                }
            } catch (IOException ignored) {
                getLogger().debug("Failed to load userKey", ignored);
            }
        }

        try {
            // Generate a new one if missing and store it
            ProKey localKey = new ProKey(GENERATED_USERNAME, "user-"+UUID.randomUUID());
            localKey.toFile(userKeyFile);
            return localKey.getProKey();
        } catch (IOException ignored) {
            getLogger().debug("Failed to write generated userKey", ignored);
        }
        return null;
    }

    /**
     *  Get location for user key file.
     *
     *  Uses default method visibility to allow testing.
     *
     * @return File containing the generated user id.
     */
    static File getUserKeyLocation() {
        String userHome = System.getProperty(PROPERTY_USER_HOME);
        return new File(new File(userHome, VAADIN_FOLDER_NAME), USER_KEY_FILE_NAME);
    }

    /** Handles a client-side request to receive component telemetry data.
     *
     * @return <code>true</code> if request was handled, <code>false</code> otherwise.
     */
    public static boolean handleClientTelemetryData(HttpServletRequest request, HttpServletResponse response) {
        if (!usageStatisticsEnabled) {
            return false;
        }

        if (request.getParameter(TELEMETRY_PARAMETER) != null && request.getMethod().equals("POST")) {
            getLogger().debug("Received telemetry POST from browser");
            try {
                if (request.getContentType() == null || !request.getContentType().equals("application/json")) {
                    // Content type should be correct
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return true;
                }
                if (request.getContentLength() > MAX_TELEMETRY_LENGTH) {
                    // Do not store meaningless amount of telemetry data
                    ObjectNode telemetryData = jsonMapper.createObjectNode();
                    telemetryData.put("elements", "Too much telemetry data: "+request.getContentLength());
                    updateProjectTelemetryData(telemetryData);
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    return true;
                } else {
                    // Backward compatible parsing: The request contains an explanation,
                    // and the json starts with the first "{"
                    String data = IOUtils.toString(request.getReader());
                    if (!data.contains("{")) {
                        response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                        return true;
                    }
                    String json = data.substring(data.indexOf("{"));

                    JsonNode telemetryData = jsonMapper.readTree(json);
                    updateProjectTelemetryData(telemetryData);
                }

            } catch (Exception e) {
                getLogger().debug("Failed to handle telemetry request", e);
            } finally {
                try {
                    response.getWriter().write("Thank you");
                } catch (IOException e) {
                    getLogger().debug("Failed to write telemetry response", e);
                }
            }
            return true;
        }
        return false;
    }

    /**
     *  Helper to update client data in current project.
     *
     * @param clientData Json data received from client.
     */
    private static void updateProjectTelemetryData(JsonNode clientData) {
        try {
            if (clientData != null && clientData.isObject()) {
                clientData.fields().forEachRemaining(e -> projectJson.set(e.getKey(), e.getValue()));
            }
        } catch (Exception e) {
            getLogger().debug("Failed to update client telemetry data", e);
        }
        writeStatisticsJson(json);
    }

    /**
     * Update a single increment value in current project data.
     *
     *  Stores the data to the disk automatically.
     *
     * @see #incrementJsonValue(ObjectNode, String)
     * @param fieldName name of the field to increment
     */
    public static void incrementField(String fieldName) {
        incrementJsonValue(projectJson,fieldName);
        writeStatisticsJson(json);
    }

    /**
     * Helper to update a single autoincrement value in current project data.
     *
     * @param node Json node which contains the field
     * @param fieldName name of the field to increment
     */
    private static void incrementJsonValue(ObjectNode node, String fieldName) {
        if (node.has(fieldName)) {
            JsonNode f = node.get(fieldName);
            node.put(fieldName,f.asInt()+1);
        } else {
            node.put(fieldName,0);
        }
    }

    /** Generates a unique pseudonymisated hash string for the project in folder.
     * Uses either pom.xml or settings.gradle.
     *
     * @param projectFolder Project root folder. Should contain either pom.xml or settings.gradle.
     * @return Unique string of project or hash of <code>DEFAULT_PROJECT_ID</code> if no valid project was found in the folder.
     */
    static String generateProjectId(String projectFolder) {
        Path projectPath = Paths.get(projectFolder);
        File pomFile = projectPath.resolve("pom.xml").toFile();

        // Maven project
        if (pomFile.exists()) {
            try {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                Document pom = db.parse(pomFile);
                String groupId = getFirstElementTextByName(pom.getDocumentElement(),"groupId");
                String artifactId = getFirstElementTextByName(pom.getDocumentElement(),"artifactId");
                return "pom"+createHash(groupId+artifactId);
            } catch (SAXException | IOException | ParserConfigurationException ignored) {
                getLogger().debug("Failed to parse project id from "+pomFile.getPath(),ignored);
            }
        }

        // Gradle project
        Path gradleFile = projectPath.resolve("settings.gradle");
        if (gradleFile.toFile().exists()) {
            try (Stream<String> stream = Files.lines(gradleFile)) {
                String projectName =  stream
                        .filter(line -> line.contains("rootProject.name"))
                        .findFirst()
                        .orElse(DEFAULT_PROJECT_ID);
                if (projectName.contains("=")) {
                    projectName = projectName.substring(projectName.indexOf("="));
                }
                return "gradle"+createHash(projectName);
            } catch (IOException ignored) {
                getLogger().debug("Failed to parse project id from "+gradleFile.toFile().getPath(),ignored);
            }
        }
        return createHash(DEFAULT_PROJECT_ID);
    }

    /**
     * DOM helper to find the text content of the first direct child node by given name.
     *
     * @param parent
     * @param nodeName
     * @return Text content of the first mach or null if not found.
     */
    private static String getFirstElementTextByName(Element parent, String nodeName) {
        NodeList nodeList = parent.getChildNodes();
        for (int i = 0; i < nodeList.getLength(); i++) {
            if (nodeList.item(i).getNodeName().equals(nodeName))  {
                return nodeList.item(i).getTextContent();
            }
        }
        return null;
    }

    /** Creates a MD5 hash out from a string for pseudonymisation purposes.
     *
     * @param string String to hash
     * @return Hex encoded MD5 version of string or <code>MISSING_DATA</code>.
     */
    private static String createHash(String string) {
        if (string != null) {
            try {
                MessageDigest.getInstance("MD5");
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(string.getBytes());
                byte[] digest = md.digest();
                return new String(Hex.encodeHex(digest));
            } catch (NoSuchAlgorithmException ignored) {
                getLogger().debug("Missing hash algorithm", ignored);
            }
        }
        return MISSING_DATA;
    }


    /**
     *  Helper to find a project node by id in the given array node.
     *
     *
     * @see #FIELD_PROJECT_ID
     * @param pid Project ID
     * @param projects Json array node containing list of projects
     * @param createNew true if a new {@link ObjectNode} should be created if not found.
     * @return Json {@link ObjectNode} if found or null. Always returns a node if <code>createNew</code> is <code>true</code> and <code>projects</code> is not null.
     */
    private static ObjectNode findById(String pid, JsonNode projects, boolean createNew) {
        if (projects== null || !projects.isArray()) {
            return null;
        }

        for (final JsonNode p : projects) {
            if (p!= null && p.has(FIELD_PROJECT_ID) && pid.equals(p.get(FIELD_PROJECT_ID).asText())) {
                return (ObjectNode)p;
            }
        }

        if (createNew) {
            ArrayNode arrayNode = (ArrayNode) projects;
            ObjectNode p = arrayNode.addObject();
            p.put(FIELD_PROJECT_ID, pid);
            return p;
        }

        return null;
    }


    /**
     *  Send current statistics to given reporting URL.
     *
     * Reads the current data and posts it to given URL. Updates or replaces
     * the local data according to the response.
     *
     * @see #readStatisticsJson()
     * @see #postData(String, JsonNode)
     * @param usageUrl URL to send data to.
     */
    private static String sendCurrentStatistics(String usageUrl) {

        String message = null;
        JsonNode response = postData(usageUrl,json);

        // Update the last sent time
        // If the last send was successful we clear the project data
        if (response != null && response.isObject() && response.has(FIELD_LAST_STATUS)) {
            json.put(FIELD_LAST_SENT,System.currentTimeMillis());
            json.put(FIELD_LAST_STATUS,response.get(FIELD_LAST_STATUS).asText());

            // Use longer interval, if requested in response
            if (response.has(FIELD_SEND_INTERVAL)
                    && response.get(FIELD_SEND_INTERVAL).isLong()) {
                json.put(FIELD_SEND_INTERVAL, normalizeInterval(response.get(FIELD_SEND_INTERVAL).asLong()));
            } else {
                json.put(FIELD_SEND_INTERVAL, TIME_MS_24H);
            }

            // Update the server message
            if (response.has(FIELD_SERVER_MESSAGE)
                    && response.get(FIELD_SERVER_MESSAGE).isTextual()) {
                message = response.get(FIELD_SERVER_MESSAGE).asText();
                json.put(FIELD_SERVER_MESSAGE, message);
            }

            // If data was sent ok, clear the existing project data
            if (response.get(FIELD_LAST_STATUS).asText().startsWith("200:")) {
                json.set(FIELD_PROJECTS, jsonMapper.createArrayNode());
                projectJson = findById(projectId, json.get(FIELD_PROJECTS), true);
            }
        }

        writeStatisticsJson(json);

        return message;
    }

    /**
     * Get interval that is between {@link #TIME_MS_24H}and {@link #TIME_MS_30D}
     *
     * @param interval Interval to normalize
     * @return <code>interval</code> if inside valid range.
     */
    private static long normalizeInterval(long interval) {
        if (interval < TIME_MS_24H) return TIME_MS_24H;
        if (interval > TIME_MS_30D) return TIME_MS_30D;
        return TIME_MS_24H;
    }

    /** Posts given Json data to a URL.
     *
     * Updates <code>FIELD_LAST_SENT</code> and <code>FIELD_LAST_STATUS</code>.
     *
     * @param posrtUrl URL to post data to.
     * @param data Json data to send
     * @return Response or <code>data</code> if the data was not successfully sent.
     */
    private static ObjectNode postData(String posrtUrl, JsonNode data) {
        try {
            HttpPost post = new HttpPost(posrtUrl);
            post.addHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(jsonMapper.writeValueAsString(data)));

            HttpClient client = HttpClientBuilder.create().build();
            HttpResponse response = client.execute(post);
            String responseStatus = response.getStatusLine().getStatusCode() + ": " + response.getStatusLine().getReasonPhrase();
            JsonNode jsonResponse = null;
            if (response.getStatusLine().getStatusCode()== HttpStatus.SC_OK) {
                String responseString = EntityUtils.toString(response.getEntity());
                jsonResponse = jsonMapper.readTree(responseString);
            }

            if (jsonResponse != null && jsonResponse.isObject()) {
                // Update the status and return the results json as-is
                ObjectNode result = (ObjectNode) jsonResponse;
                result.put(FIELD_LAST_STATUS, responseStatus);
                return result;
            }

        } catch (IOException e) {
            getLogger().debug("Failed to send statistics: "+e.getMessage());
        }

        // Default response in case of any problems
        ObjectNode result = jsonMapper.createObjectNode();
        result.put(FIELD_LAST_STATUS, INVALID_SERVER_RESPONSE);

        return result;
    }


    /**
     *  Writes the given json data to local project statistics file.
     *
     * @see #getStatisticsFile()
     * @param data Json data to be written.
     */
    static synchronized void writeStatisticsJson(JsonNode data) {
        try {
            jsonMapper.writeValue(getStatisticsFile(), data);
        } catch (IOException ignored) {
            getLogger().debug("Failed to write json", ignored);
        }
    }

    /**
     *  Read the data from local project statistics file.
     *
     * @see #getStatisticsFile()
     * @return  Json data in the file or empty Json node.
     */
     static synchronized ObjectNode readStatisticsJson() {
        try {
            File file = getStatisticsFile();
            if (file.exists()) {
                return  (ObjectNode)jsonMapper.readTree(file);
            }
        } catch (JsonProcessingException ignored) {
            getLogger().debug("Failed to parse json", ignored);
        } catch (IOException ignored) {
            getLogger().debug("Failed to read json", ignored);
        }
        // Return empty node if nothing else is found
        return jsonMapper.createObjectNode();
    }


    /** Is the Interval elapsed.
     *  Uses <code>System.currentTimeMillis</code> as time source.
     * Uses default method visibility to allow testing, but not intended to use outside.
     *
     * @see #getLastSendTime()
     * @see #getInterval()
     * @return true if enough time has passed since the last send attempt.
     */
    static boolean isIntervalElapsed() {
        long now = System.currentTimeMillis();
        long lastSend = getLastSendTime();
        long interval = getInterval();
        return lastSend+interval < now;
    }

    /** Reads the statistics update interval.
     *
     *  Uses default method visibility to allow testing, but not intended to use outside.
     *
     * @see #FIELD_SEND_INTERVAL
     * @return Time interval in milliseconds. {@link #TIME_MS_24H} in minumun and {@link #TIME_MS_30D} as maximum.
     */
    static long getInterval() {
        try {
            long interval = json.get(FIELD_SEND_INTERVAL).asLong();
            return normalizeInterval(interval);
        } catch (Exception ignored) {
            // Just return the default value
        }
        return TIME_MS_24H;
    }

    /**
     *  Gets the last time the data was collected according to the statistics file.
     *
     * @see #FIELD_LAST_SENT
     * @return Unix timestamp or -1 if not present
     */
    static long getLastSendTime() {
        try {
            return json.get(FIELD_LAST_SENT).asLong();
        } catch (Exception ignored) {
            // Use default value in case of problems
        }
        return -1; //
    }

    private static Logger getLogger() {
        return LoggerFactory.getLogger(VaadinUsageStatistics.class.getName());
    }


    /**
     *
     * Get usage statistics json file location.
     *
     * Uses default method visibility to allow testing, but not intended to use outside.
     *
     * @return the location of statistics storage file.
     */
    static File getStatisticsFile() {
        String userHome = System.getProperty(PROPERTY_USER_HOME);
        return new File(new File(userHome, VAADIN_FOLDER_NAME), STATISTICS_FILE_NAME);
    }


    /** Class representing Vaadin Pro key.
     *
     *  Uses default method visibility to allow testing, but not intended to use outside.
     */
    static class ProKey {

        private final String username;
        private final String proKey;

        public ProKey(String username, String proKey) {
            super();
            this.username = username;
            this.proKey = proKey;
        }

        public static ProKey fromJson(String jsonData) {
            ProKey proKey = new ProKey(null,null);
            try {
                JsonNode json = jsonMapper.readTree(jsonData);
                proKey = new ProKey(json.get("username").asText(),
                        json.get("proKey").asText());
                return proKey;
            } catch (JsonProcessingException |NullPointerException ignored) {
                getLogger().debug("Failed to parse proKey from Json", ignored);
            }
            return proKey;
        }

        public static ProKey fromFile(File jsonFile) throws IOException {
            ProKey proKey = new ProKey(null,null);
            JsonNode json = jsonMapper.readTree(jsonFile);
            if (json != null && json.has("proKey")) {
                proKey = new ProKey(null,
                        json.get("proKey").asText());
            }
            return proKey;
        }

        public void toFile(File proKeyLocation) throws IOException {
            jsonMapper.writeValue(proKeyLocation,proKey);
        }

        public String toJson() {
            ObjectNode json = jsonMapper.createObjectNode();
            json.put("username", username);
            json.put("proKey", proKey);
            try {
                return jsonMapper.writeValueAsString(json);
            } catch (JsonProcessingException ignored) {
                getLogger().debug("Unable to read proKey", ignored);
            }
            return null;
        }

        public String getUsername() {
            return username;
        }

        public String getProKey() {
            return proKey;
        }

    }

    /** Class representing Vaadin Pro key.
     *  Uses default method visibility to allow testing, but not intended to use outside.
     */
    static class LocalProKey {

        private LocalProKey() {}

        private static ProKey read(File proKeyLocation) throws IOException {
            if (!proKeyLocation.exists()) {
                return null;
            }
            return ProKey.fromFile(proKeyLocation);
        }

        public static void write(ProKey proKey, File proKeyLocation) throws IOException {
            File proKeyDirectory = getLocation().getParentFile();
            if (!proKeyDirectory.exists()) {
                proKeyDirectory.mkdirs();
            }
            proKey.toFile(proKeyLocation);
        }

        public static File getLocation() {
            String userHome = System.getProperty(PROPERTY_USER_HOME);
            return new File(new File(userHome, VAADIN_FOLDER_NAME), "proKey");
        }

        public static ProKey get() {
            ProKey proKey = getSystemProperty();
            if (proKey != null) {
                return proKey;
            }
            proKey = getEnvironmentVariable();
            if (proKey != null) {
                return proKey;
            }
            File proKeyLocation = getLocation();
            try {
                proKey = read(proKeyLocation);
                return proKey;
            } catch (IOException ignored) {
                getLogger().debug("Unable to read proKey", ignored);
                return null;
            }
        }

        private static ProKey getSystemProperty() {
            String value = System.getProperty("vaadin.proKey");
            if (value == null) {
                return null;
            }
            String[] parts = value.split("/");
            if (parts.length != 2) {
                getLogger().debug(
                        "Unable to read pro key from the vaadin.proKey system property. The property must be of type -Dvaadin.proKey=[vaadin.com login email]/[prokey]");
                return null;
            }

            return new ProKey(parts[0], parts[1]);
        }

        private static ProKey getEnvironmentVariable() {
            String value = System.getenv("VAADIN_PRO_KEY");
            if (value == null) {
                return null;
            }
            String[] parts = value.split("/");
            if (parts.length != 2) {
                getLogger().debug(
                        "Unable to read pro key from the VAADIN_PRO_KEY environment variable. The value must be of type VAADIN_PRO_KEY=[vaadin.com login email]/[prokey]");
                return null;
            }

            return new ProKey(parts[0], parts[1]);
        }
    }


}
