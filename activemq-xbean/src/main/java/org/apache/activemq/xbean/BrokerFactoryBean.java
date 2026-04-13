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

import java.net.URL;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.xbean.DefaultBrokerContext;
import org.apache.activemq.xbean.Utils;

/**
 * A plain-Java factory that creates an embedded {@link BrokerService} from an
 * XBean XML configuration file.
 *
 * <p>Usage:</p>
 * <pre>
 * BrokerFactoryBean factory = new BrokerFactoryBean();
 * factory.setConfig(new URL("file:/path/to/activemq.xml"));
 * factory.setStart(true);
 * factory.afterPropertiesSet();          // initialise
 * BrokerService broker = factory.getBroker();
 * // … use broker …
 * factory.destroy();                     // stop
 * </pre>
 *
 * <p>Previously this class implemented Spring's {@code FactoryBean},
 * {@code InitializingBean}, {@code DisposableBean} and
 * {@code ApplicationContextAware}. Those interfaces have been removed in favour
 * of explicit lifecycle calls so that no Spring dependency is required.</p>
 */
public class BrokerFactoryBean {

    private URL config;
    private BrokerService broker;
    private boolean start;
    private DefaultBrokerContext brokerContext;

    private boolean systemExitOnShutdown;
    private int systemExitOnShutdownExitCode;

    public BrokerFactoryBean() {
    }

    public BrokerFactoryBean(URL config) {
        this.config = config;
    }

    /** Convenience constructor that resolves the config from a URI string. */
    public BrokerFactoryBean(String configUri) throws Exception {
        this.config = Utils.resourceFromString(configUri);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Loads and (optionally) starts the broker.
     *
     * @throws IllegalArgumentException if {@code config} has not been set or no
     *                                  broker definition is found in the file
     */
    public void afterPropertiesSet() throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("config property must be set");
        }

        XBeanBrokerLoader loader = new XBeanBrokerLoader();
        broker = loader.loadBroker(config);
        brokerContext = new DefaultBrokerContext(loader.getBeanRegistry(), config.toExternalForm());
        broker.setBrokerContext(brokerContext);

        if (systemExitOnShutdown) {
            final int exitCode = systemExitOnShutdownExitCode;
            broker.addShutdownHook(() -> System.exit(exitCode));
        }
        if (start) {
            broker.start();
        }
    }

    /** Stops the broker. */
    public void destroy() throws Exception {
        if (broker != null) {
            broker.stop();
        }
    }

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    /** Returns the configured {@link BrokerService}, or {@code null} if not yet initialised. */
    public BrokerService getBroker() {
        return broker;
    }

    // -------------------------------------------------------------------------
    // Properties
    // -------------------------------------------------------------------------

    public URL getConfig() {
        return config;
    }

    public void setConfig(URL config) {
        this.config = config;
    }

    /** Convenience setter that resolves a URI string to a {@link URL}. */
    public void setConfigUri(String uri) throws Exception {
        this.config = Utils.resourceFromString(uri);
    }

    public boolean isStart() {
        return start;
    }

    public void setStart(boolean start) {
        this.start = start;
    }

    public boolean isSystemExitOnStop() {
        return systemExitOnShutdown;
    }

    public void setSystemExitOnStop(boolean systemExitOnStop) {
        this.systemExitOnShutdown = systemExitOnStop;
    }

    public boolean isSystemExitOnShutdown() {
        return systemExitOnShutdown;
    }

    public void setSystemExitOnShutdown(boolean systemExitOnShutdown) {
        this.systemExitOnShutdown = systemExitOnShutdown;
    }

    public int getSystemExitOnShutdownExitCode() {
        return systemExitOnShutdownExitCode;
    }

    public void setSystemExitOnShutdownExitCode(int systemExitOnShutdownExitCode) {
        this.systemExitOnShutdownExitCode = systemExitOnShutdownExitCode;
    }
}
