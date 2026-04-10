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

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.activemq.broker.BrokerContext;

/**
 * A map-backed {@link BrokerContext} that does not depend on any external
 * container. Beans are registered explicitly via {@link #registerBean(String, Object)}.
 */
public class DefaultBrokerContext implements BrokerContext {

    private final Map<String, Object> beans = new LinkedHashMap<>();
    private String configurationUrl;

    public DefaultBrokerContext() {
    }

    public DefaultBrokerContext(Map<String, Object> beans, String configurationUrl) {
        this.beans.putAll(beans);
        this.configurationUrl = configurationUrl;
    }

    public void registerBean(String name, Object bean) {
        beans.put(name, bean);
    }

    @Override
    public Object getBean(String name) {
        return beans.get(name);
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
