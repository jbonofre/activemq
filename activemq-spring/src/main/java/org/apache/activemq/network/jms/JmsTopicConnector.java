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
package org.apache.activemq.network.jms;

import java.util.Properties;

/**
 * A Bridge to other JMS Topic providers.
 *
 * <p>JNDI contexts are configured via {@link Properties} objects rather than
 * Spring's {@code JndiTemplate}, removing the Spring dependency.</p>
 *
 * @org.apache.xbean.XBean
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
public class JmsTopicConnector extends SimpleJmsTopicConnector {

    /**
     * Configures the local JNDI context using the supplied environment
     * properties.
     *
     * @param environment JNDI environment properties for the local context
     */
    public void setJndiLocalEnvironment(Properties environment) {
        super.setJndiLocalTemplate(new JndiTemplateLookupFactory(environment));
    }

    /**
     * Configures the outbound JNDI context using the supplied environment
     * properties.
     *
     * @param environment JNDI environment properties for the outbound context
     */
    public void setJndiOutboundEnvironment(Properties environment) {
        super.setJndiOutboundTemplate(new JndiTemplateLookupFactory(environment));
    }
}
