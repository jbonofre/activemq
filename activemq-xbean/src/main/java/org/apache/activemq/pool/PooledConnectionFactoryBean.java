/*
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
package org.apache.activemq.pool;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.jms.ConnectionFactory;
import jakarta.transaction.TransactionManager;
import org.apache.activemq.jms.pool.PooledConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A plain-Java factory that creates a {@link PooledConnectionFactory}
 * appropriate to the configured transaction strategy:
 * <ul>
 *   <li>JCA pool – when both {@code transactionManager} and {@code resourceName} are set</li>
 *   <li>XA pool  – when only {@code transactionManager} is set</li>
 *   <li>Plain pool – otherwise</li>
 * </ul>
 *
 * <p>Call {@link #afterPropertiesSet()} to initialise and {@link #destroy()} to
 * stop the pool. In Jakarta EE / CDI containers the {@link PostConstruct} /
 * {@link PreDestroy} callbacks are invoked automatically.</p>
 *
 * <p>Previously this class implemented Spring's {@code FactoryBean}. That
 * interface has been removed so that no Spring dependency is required.</p>
 *
 * @org.apache.xbean.XBean
 */
public class PooledConnectionFactoryBean {

    private static final Logger LOGGER = LoggerFactory.getLogger(PooledConnectionFactoryBean.class);

    private PooledConnectionFactory pooledConnectionFactory;
    private ConnectionFactory connectionFactory;
    private int maxConnections = 1;
    private int maximumActive = 500;
    private Object transactionManager;
    private String resourceName;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Jakarta EE lifecycle callback. In plain Java code call
     * {@link #afterPropertiesSet()} directly.
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
     * Creates the appropriate {@link PooledConnectionFactory} based on the
     * configured properties.
     *
     * @org.apache.xbean.InitMethod
     */
    public void afterPropertiesSet() throws Exception {
        if (pooledConnectionFactory == null && transactionManager != null && resourceName != null) {
            try {
                LOGGER.debug("Trying to build a JcaPooledConnectionFactory");
                JcaPooledConnectionFactory f = new JcaPooledConnectionFactory();
                f.setName(resourceName);
                f.setTransactionManager((TransactionManager) transactionManager);
                f.setMaxConnections(maxConnections);
                f.setMaximumActiveSessionPerConnection(maximumActive);
                f.setConnectionFactory(connectionFactory);
                this.pooledConnectionFactory = f;
            } catch (Throwable t) {
                LOGGER.debug("Could not create JCA enabled connection factory: " + t, t);
            }
        }
        if (pooledConnectionFactory == null && transactionManager != null) {
            try {
                LOGGER.debug("Trying to build a XaPooledConnectionFactory");
                XaPooledConnectionFactory f = new XaPooledConnectionFactory();
                f.setTransactionManager((TransactionManager) transactionManager);
                f.setMaxConnections(maxConnections);
                f.setMaximumActiveSessionPerConnection(maximumActive);
                f.setConnectionFactory(connectionFactory);
                this.pooledConnectionFactory = f;
            } catch (Throwable t) {
                LOGGER.debug("Could not create XA enabled connection factory: " + t, t);
            }
        }
        if (pooledConnectionFactory == null) {
            try {
                LOGGER.debug("Trying to build a PooledConnectionFactory");
                PooledConnectionFactory f = new PooledConnectionFactory();
                f.setMaxConnections(maxConnections);
                f.setMaximumActiveSessionPerConnection(maximumActive);
                f.setConnectionFactory(connectionFactory);
                this.pooledConnectionFactory = f;
            } catch (Throwable t) {
                LOGGER.debug("Could not create pooled connection factory: " + t, t);
            }
        }
        if (pooledConnectionFactory == null) {
            throw new IllegalStateException(
                    "Unable to create pooled connection factory. Enable DEBUG log level for more information.");
        }
    }

    /**
     * Jakarta EE lifecycle callback. In plain Java code call
     * {@link #destroy()} directly.
     */
    @PreDestroy
    private void preDestroy() {
        try {
            destroy();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Stops the underlying pooled connection factory.
     *
     * @org.apache.xbean.DestroyMethod
     */
    public void destroy() throws Exception {
        if (pooledConnectionFactory != null) {
            pooledConnectionFactory.stop();
            pooledConnectionFactory = null;
        }
    }

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /**
     * Returns the created {@link ConnectionFactory}.
     * If the factory has not yet been initialised, {@link #afterPropertiesSet()}
     * is called automatically.
     */
    public ConnectionFactory create() throws Exception {
        if (pooledConnectionFactory == null) {
            afterPropertiesSet();
        }
        return pooledConnectionFactory;
    }

    // -------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------

    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int maxConnections) { this.maxConnections = maxConnections; }

    public int getMaximumActive() { return maximumActive; }
    public void setMaximumActive(int maximumActive) { this.maximumActive = maximumActive; }

    public Object getTransactionManager() { return transactionManager; }
    public void setTransactionManager(Object transactionManager) { this.transactionManager = transactionManager; }

    public String getResourceName() { return resourceName; }
    public void setResourceName(String resourceName) { this.resourceName = resourceName; }

    public ConnectionFactory getConnectionFactory() { return connectionFactory; }
    public void setConnectionFactory(ConnectionFactory connectionFactory) { this.connectionFactory = connectionFactory; }
}
