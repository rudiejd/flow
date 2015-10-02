/*
 * Copyright 2000-2014 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.vaadin.ui;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import com.vaadin.annotations.TemplateEventHandler;
import com.vaadin.hummingbird.kernel.Element;
import com.vaadin.hummingbird.kernel.ElementTemplate;
import com.vaadin.hummingbird.kernel.JsonConverter;
import com.vaadin.hummingbird.kernel.StateNode;
import com.vaadin.hummingbird.parser.TemplateParser;

import elemental.json.JsonArray;
import elemental.json.JsonType;
import elemental.json.JsonValue;

public abstract class Template extends AbstractComponent {
    public interface Model {
        // Only a marker
    }

    private Model model;

    private final StateNode node = StateNode.create();

    public Template() {
        this(null);
    }

    protected Template(ElementTemplate elementTemplate) {
        if (elementTemplate == null) {
            elementTemplate = TemplateParser.parse(getClass());
        }
        setElement(Element.getElement(elementTemplate, node));

        getNode().put(TemplateCallbackHandler.class, this::onBrowserEvent);
    }

    protected void onBrowserEvent(StateNode node, String methodName,
            JsonArray params) {
        Method method = findTemplateEventHandlerMethod(getClass(), methodName,
                params.length());
        if (method == null) {
            throw new RuntimeException("Couldn't find any @"
                    + com.vaadin.annotations.TemplateEventHandler.class
                            .getName()
                    + " method named " + methodName + "with " + params.length()
                    + " parameters in " + getClass().getName());
        }

        int paramIndex = 0;

        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] methodParams = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> type = parameterTypes[i];
            JsonValue param = params.get(paramIndex++);
            if (type == Element.class && param.getType() == JsonType.ARRAY) {
                JsonArray elementArray = (JsonArray) param;
                int nodeId = (int) elementArray.getNumber(0);
                int templateId = (int) elementArray.getNumber(1);

                StateNode elementNode = node.getRoot().getById(nodeId);
                ElementTemplate template = getUI().getTemplate(templateId);
                methodParams[i] = Element.getElement(template, elementNode);
            } else {
                methodParams[i] = JsonConverter.fromJson(type, param);
            }
        }

        method.setAccessible(true);
        try {
            method.invoke(this, methodParams);
        } catch (IllegalAccessException | IllegalArgumentException
                | InvocationTargetException e) {
            throw new RuntimeException("Couldn't invoke " + method, e);
        }
    }

    private static Method findTemplateEventHandlerMethod(Class<?> type,
            String methodName, int paramCount) {
        while (type != null && type != Template.class) {
            Method[] declaredMethods = type.getDeclaredMethods();
            for (Method method : declaredMethods) {
                if (method.getName().equals(methodName)
                        && method.getAnnotation(
                                TemplateEventHandler.class) != null
                        && method.getParameterCount() == paramCount) {
                    return method;
                }
            }

            type = type.getSuperclass();
        }

        return null;
    }

    protected StateNode getNode() {
        return node;
    }

    protected Model getModel() {
        if (model == null) {
            model = createModel();
        }
        return model;
    }

    private Class<? extends Model> getModelType() {
        // TODO cache the result (preferably without leaking classloaders)
        Class<?> type = getClass();
        while (type != Template.class) {
            try {
                Method method = type.getDeclaredMethod("getModel");
                return method.getReturnType().asSubclass(Model.class);
            } catch (NoSuchMethodException e) {
                type = type.getSuperclass();
            }
        }

        return Model.class;
    }

    private static final Map<Class<?>, Object> primitiveDefaults = new HashMap<>();

    static {
        primitiveDefaults.put(byte.class, Byte.valueOf((byte) 0));
        primitiveDefaults.put(short.class, Short.valueOf((short) 0));
        primitiveDefaults.put(char.class, Character.valueOf((char) 0));
        primitiveDefaults.put(int.class, Integer.valueOf(0));
        primitiveDefaults.put(long.class, Long.valueOf(0));
        primitiveDefaults.put(float.class, Float.valueOf(0f));
        primitiveDefaults.put(double.class, Double.valueOf(0));
    }

    private Model createModel() {
        Class<? extends Model> modelType = getModelType();
        if (modelType == Model.class) {
            return new Model() {
                // Don't bother with a proxy if there's nothing in the interface
            };
        } else {
            Object proxy = Proxy.newProxyInstance(modelType.getClassLoader(),
                    new Class[] { modelType }, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method,
                                Object[] args) throws Throwable {
                            if (method.getDeclaringClass() == Object.class) {
                                return handleObjectMethod(proxy, method, args);
                            }

                            String name = method.getName();
                            if (name.startsWith("get")
                                    || name.startsWith("is")) {
                                assert args == null || args.length == 0;
                                assert method.getReturnType() != void.class;

                                return get(getPropertyName(method.getName()),
                                        method.getReturnType());
                            } else if (name.startsWith("set")) {
                                assert args.length == 1;
                                assert method.getReturnType() == void.class;

                                set(getPropertyName(method.getName()),
                                        method.getParameterTypes()[0], args[0]);
                                return null;
                            } else {
                                throw new RuntimeException(
                                        "Method not supported: " + method);
                            }
                        }

                        private void set(String propertyName, Class<?> type,
                                Object value) {
                            if (type == boolean.class
                                    && Boolean.FALSE.equals(value)) {
                                getNode().remove(propertyName);
                            } else {
                                getNode().put(propertyName, value);
                            }
                        }

                        private Object get(String propertyName, Class<?> type) {
                            if (type == boolean.class) {
                                return Boolean.valueOf(
                                        getNode().containsKey(propertyName));
                            }

                            if (type.isPrimitive()) {
                                if (!getNode().containsKey(propertyName)) {
                                    // Find the default value, somehow
                                    return primitiveDefaults.get(type);
                                } else {
                                    // Can't get by type int if value is Integer
                                    return getNode().get(propertyName);
                                }
                            }

                            return getNode().get(propertyName, type);
                        }

                        private String getPropertyName(String methodName) {
                            String propertyName = methodName
                                    .replaceFirst("^(set|get|is)", "");
                            propertyName = Character
                                    .toLowerCase(propertyName.charAt(0))
                                    + propertyName.substring(1);
                            return propertyName;
                        }

                        private Object handleObjectMethod(Object proxy,
                                Method method, Object[] args) {
                            switch (method.getName()) {
                            default:
                                throw new RuntimeException(
                                        "Method not yet supported: " + method);
                            }
                        }

                    });
            return (Model) proxy;
        }
    }
}
