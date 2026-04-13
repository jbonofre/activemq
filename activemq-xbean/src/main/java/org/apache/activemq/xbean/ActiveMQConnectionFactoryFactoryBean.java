/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.xbean;

import java.util.ArrayList;
import java.util.List;

/**
 * A helper factory for creating a failover-configured
 * {@link ActiveMQConnectionFactory} from one or more TCP host/port pairs
 * without having to construct the URL manually.
 *
 * <p>Previously this class implemented Spring's {@code FactoryBean}. It has
 * been refactored to a plain factory with a {@link #createConnectionFactory()}
 * method so that no Spring dependency is required.</p>
 */
public class ActiveMQConnectionFactoryFactoryBean {

    private List<String> tcpHostAndPorts = new ArrayList<>();

    // TCP properties
    private Long maxInactivityDuration;
    private String tcpProperties;

    // failover properties
    private Long maxReconnectDelay;
    private String failoverProperties;

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Creates and returns a new {@link ActiveMQConnectionFactory} configured
     * with a failover URL built from the supplied host/port list.
     */
    public ActiveMQConnectionFactory createConnectionFactory() throws Exception {
        ActiveMQConnectionFactory answer = new ActiveMQConnectionFactory();
        answer.setBrokerURL(getBrokerURL());
        return answer;
    }

    public String getBrokerURL() {
        StringBuilder buffer = new StringBuilder("failover:(");
        int counter = 0;
        for (String tcpHostAndPort : tcpHostAndPorts) {
            if (counter++ > 0) {
                buffer.append(",");
            }
            buffer.append(createTcpHostAndPortUrl(tcpHostAndPort));
        }
        buffer.append(")");

        List<String> parameters = new ArrayList<>();
        if (maxReconnectDelay != null) {
            parameters.add("maxReconnectDelay=" + maxReconnectDelay);
        }
        if (notEmpty(failoverProperties)) {
            parameters.add(failoverProperties);
        }
        buffer.append(asQueryString(parameters));
        return buffer.toString();
    }

    // -------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------

    public List<String> getTcpHostAndPorts() { return tcpHostAndPorts; }
    public void setTcpHostAndPorts(List<String> tcpHostAndPorts) { this.tcpHostAndPorts = tcpHostAndPorts; }

    public void setTcpHostAndPort(String tcpHostAndPort) {
        this.tcpHostAndPorts = new ArrayList<>();
        this.tcpHostAndPorts.add(tcpHostAndPort);
    }

    public Long getMaxInactivityDuration() { return maxInactivityDuration; }
    public void setMaxInactivityDuration(Long maxInactivityDuration) { this.maxInactivityDuration = maxInactivityDuration; }

    public String getTcpProperties() { return tcpProperties; }
    public void setTcpProperties(String tcpProperties) { this.tcpProperties = tcpProperties; }

    public Long getMaxReconnectDelay() { return maxReconnectDelay; }
    public void setMaxReconnectDelay(Long maxReconnectDelay) { this.maxReconnectDelay = maxReconnectDelay; }

    public String getFailoverProperties() { return failoverProperties; }
    public void setFailoverProperties(String failoverProperties) { this.failoverProperties = failoverProperties; }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    protected String asQueryString(List<String> parameters) {
        int size = parameters.size();
        if (size < 1) {
            return "";
        }
        StringBuilder buffer = new StringBuilder("?");
        buffer.append(parameters.get(0));
        for (int i = 1; i < size; i++) {
            buffer.append("&");
            buffer.append(parameters.get(i));
        }
        return buffer.toString();
    }

    protected String createTcpHostAndPortUrl(String tcpHostAndPort) {
        List<String> parameters = new ArrayList<>();
        if (maxInactivityDuration != null) {
            parameters.add("wireFormat.maxInactivityDuration=" + maxInactivityDuration);
        }
        if (notEmpty(tcpProperties)) {
            parameters.add(tcpProperties);
        }
        return tcpHostAndPort + asQueryString(parameters);
    }

    protected boolean notEmpty(String text) {
        return text != null && !text.isEmpty();
    }
}
