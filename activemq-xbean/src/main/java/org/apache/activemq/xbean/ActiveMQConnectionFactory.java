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
package org.apache.activemq.xbean;

import jakarta.annotation.PostConstruct;

/**
 * An ActiveMQ connection factory that supports using the bean name as the
 * {@code clientIDPrefix} so that JMX visibility is improved.
 *
 * <p>When running inside a Jakarta EE / CDI container the {@link PostConstruct}
 * callback on {@link #afterPropertiesSet()} is invoked automatically. In plain
 * Java usage call {@link #afterPropertiesSet()} explicitly after setting all
 * properties.</p>
 *
 * @org.apache.xbean.XBean element="connectionFactory"
 */
public class ActiveMQConnectionFactory extends org.apache.activemq.ActiveMQConnectionFactory {

    private String beanName;
    private boolean useBeanNameAsClientIdPrefix;

    /**
     * Jakarta EE lifecycle callback – invoked automatically by CDI / Jakarta
     * containers. In plain Java code call {@link #afterPropertiesSet()} directly.
     */
    @PostConstruct
    private void postConstruct() {
        try {
            afterPropertiesSet();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Applies the bean name as the client-ID prefix when
     * {@link #isUseBeanNameAsClientIdPrefix()} is {@code true}.
     *
     * @org.apache.xbean.InitMethod
     */
    public void afterPropertiesSet() throws Exception {
        if (isUseBeanNameAsClientIdPrefix() && getClientIDPrefix() == null) {
            setClientIDPrefix(getBeanName());
        }
    }

    public String getBeanName() {
        return beanName;
    }

    /**
     * Sets the logical name of this bean. When
     * {@link #isUseBeanNameAsClientIdPrefix()} is enabled the name is used as
     * the JMS client-ID prefix.
     */
    public void setBeanName(String beanName) {
        this.beanName = beanName;
    }

    public boolean isUseBeanNameAsClientIdPrefix() {
        return useBeanNameAsClientIdPrefix;
    }

    public void setUseBeanNameAsClientIdPrefix(boolean useBeanNameAsClientIdPrefix) {
        this.useBeanNameAsClientIdPrefix = useBeanNameAsClientIdPrefix;
    }
}
