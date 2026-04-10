/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.spring;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.activemq.broker.BrokerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A map-backed {@link BrokerContext} that does not depend on any external
 * container. Beans are registered explicitly via {@link #registerBean(String, Object)}.
 *
 * <p>For backward compatibility with code that passes a container object (e.g. a
 * Spring {@code ApplicationContext}) via {@link #setApplicationContext(Object)},
 * bean lookups are delegated to that object via reflection when the local map
 * does not contain the requested name/type.</p>
 */
public class DefaultBrokerContext implements BrokerContext {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultBrokerContext.class);

    private final Map<String, Object> beans = new LinkedHashMap<>();
    private String configurationUrl;

    /**
     * Optional delegate context (e.g. a Spring ApplicationContext).
     * Accessed only via reflection so there is no compile-time Spring dependency.
     */
    private Object delegateContext;

    public DefaultBrokerContext() {
    }

    public DefaultBrokerContext(Map<String, Object> beans, String configurationUrl) {
        this.beans.putAll(beans);
        this.configurationUrl = configurationUrl;
    }

    public void registerBean(String name, Object bean) {
        beans.put(name, bean);
    }

    /**
     * Optionally sets a delegate context whose {@code getBean(String)} and
     * {@code getBeansOfType(Class)} methods are called via reflection when the
     * local map does not satisfy a lookup.
     *
     * <p>This allows code that previously used Spring's
     * {@code ApplicationContextAware} callback to continue working without
     * requiring a Spring compile-time dependency in this module.</p>
     *
     * @param ctx any object exposing {@code getBean(String)} /
     *            {@code getBeansOfType(Class)} – typically a Spring
     *            {@code ApplicationContext}
     */
    public void setApplicationContext(Object ctx) {
        this.delegateContext = ctx;
    }

    @Override
    public Object getBean(String name) {
        Object result = beans.get(name);
        if (result == null && delegateContext != null) {
            try {
                Method m = delegateContext.getClass().getMethod("getBean", String.class);
                result = m.invoke(delegateContext, name);
            } catch (Exception e) {
                LOG.trace("Delegate getBean({}) failed: {}", name, e.getMessage());
            }
        }
        return result;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Map getBeansOfType(Class type) {
        Map result = new LinkedHashMap();
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            if (type.isInstance(entry.getValue())) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        if (result.isEmpty() && delegateContext != null) {
            try {
                Method m = delegateContext.getClass().getMethod("getBeansOfType", Class.class);
                Map delegate = (Map) m.invoke(delegateContext, type);
                if (delegate != null) result.putAll(delegate);
            } catch (Exception e) {
                LOG.trace("Delegate getBeansOfType({}) failed: {}", type.getSimpleName(), e.getMessage());
            }
        }
        return result;
    }

    @Override
    public String getConfigurationUrl() {
        return configurationUrl;
    }

    public void setConfigurationUrl(String configurationUrl) {
        this.configurationUrl = configurationUrl;
    }
}
