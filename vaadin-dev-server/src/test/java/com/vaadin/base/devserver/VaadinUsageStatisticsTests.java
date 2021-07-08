package com.vaadin.base.devserver;

import com.sun.net.httpserver.HttpServer;
import com.vaadin.flow.server.startup.ApplicationConfiguration;
import com.vaadin.flow.testutil.TestUtils;
import junit.framework.TestCase;
import net.jcip.annotations.NotThreadSafe;
import org.junit.*;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.Collections;

@NotThreadSafe
public class VaadinUsageStatisticsTests extends TestCase {
    
    private HttpServer httpServer;
    public static final String USAGE_REPORT_URL_LOCAL ="http://localhost:8089/v2/submit";
    private static final String VALID_PROKEY = "pro-546ea143-test-test-test-f7a1ef314f7a";
    private static final String USER_KEY = "user-546ea143-test-test-test-f7a1ef314f7a";


    @Before
    public void setup() throws Exception {
        httpServer = createStubGatherServlet(8089, 200, "");
    }


    @After
    public void teardown() throws Exception {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    public void testLoadStatisticsEnabled() throws Exception {
    }

    @Test
    public void testMavenProjectProjectId() {
        String mavenProjectFolder1 = TestUtils.getTestFolder("maven-project-folder1").toPath().toString();
        String mavenProjectFolder2 = TestUtils.getTestFolder("maven-project-folder2").toPath().toString();
        String id1 = VaadinUsageStatistics.generateProjectId(mavenProjectFolder1);
        String id2 = VaadinUsageStatistics.generateProjectId(mavenProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertNotEquals(id1,id2); // Should differ
    }

    @Test
    public void testGradleProjectProjectId() {
        String gradleProjectFolder1 = TestUtils.getTestFolder("gradle-project-folder1").toPath().toString();
        String gradleProjectFolder2 = TestUtils.getTestFolder("gradle-project-folder2").toPath().toString();
        String id1 = VaadinUsageStatistics.generateProjectId(gradleProjectFolder1);
        String id2 = VaadinUsageStatistics.generateProjectId(gradleProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertNotEquals(id1,id2); // Should differ
    }


    @Test
    public void testMissingProject() {
        String mavenProjectFolder1 = TestUtils.getTestFolder("java").toPath().toString();
        String mavenProjectFolder2 = TestUtils.getTestFolder("empty").toPath().toString();
        String id1 = VaadinUsageStatistics.generateProjectId(mavenProjectFolder1);
        String id2 = VaadinUsageStatistics.generateProjectId(mavenProjectFolder2);
        Assert.assertNotNull(id1);
        Assert.assertNotNull(id2);
        Assert.assertEquals(id1,id2); // Should be the default in both cases
    }

    @Test
    public void testNullStatisticsUpdate() {
    }

    @Test
    public void testStatisticsUpdate() {
    }

    public void loadStatisticsEnabled() throws Exception {
        try (MockedStatic<VaadinUsageStatistics> dummyStatic = Mockito.mockStatic(VaadinUsageStatistics.class)) {
            dummyStatic.when(VaadinUsageStatistics::getProKey).thenReturn(VALID_PROKEY);
            dummyStatic.when(VaadinUsageStatistics::getUserKey).thenReturn(USER_KEY);
            dummyStatic.when(VaadinUsageStatistics::getUsageReportingUrl).thenReturn("http://localhost:8089/");

            // Make sure by default statistics are enabled
            ApplicationConfiguration configuration = mockAppConfig();
            Assert.assertFalse(configuration.isProductionMode());
            Assert.assertTrue(configuration.isUsageStatisticsEnabled());

            // Initialize the statistics from Maven project
            String mavenProjectFolder = TestUtils.getTestFolder("maven-project-folder1").toPath().toString();
            VaadinUsageStatistics.init(configuration, mavenProjectFolder);

            // Make sure statistics are enabled
            Assert.assertTrue(VaadinUsageStatistics.isStatisticsEnabled());

            // Disable statistics in config
            Mockito.when(configuration.isUsageStatisticsEnabled()).thenReturn(false);

            // Make sure statistics are disabled
            Assert.assertFalse(VaadinUsageStatistics.isStatisticsEnabled());

            // Enable statistics in config and enable production mode
            Mockito.when(configuration.isUsageStatisticsEnabled()).thenReturn(true);
            Mockito.when(configuration.isProductionMode()).thenReturn(true);

            // Make sure statistics are disabled
            Assert.assertFalse(VaadinUsageStatistics.isStatisticsEnabled());

        }
    }

    private ApplicationConfiguration mockAppConfig() {
        ApplicationConfiguration appConfig = Mockito
                .mock(ApplicationConfiguration.class);
        Mockito.when(appConfig.getPropertyNames())
                .thenReturn(Collections.emptyEnumeration());
        Mockito.when(appConfig.isProductionMode()).thenReturn(false);
        Mockito.when(appConfig.isUsageStatisticsEnabled()).thenReturn(true);

        return appConfig;
    }

    private VaadinUsageStatistics.LocalProKey mockLocalProKey() {
        return Mockito.mock(VaadinUsageStatistics.LocalProKey.class);
    }

    public static HttpServer createStubGatherServlet(int port, int status,
                                                          String response) throws Exception {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(port),
                0);
        httpServer.createContext("/", exchange -> {
            System.out.println(exchange.getRequestBody());
            exchange.sendResponseHeaders(status, response.length());
            exchange.getResponseBody().write(response.getBytes());
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

}

