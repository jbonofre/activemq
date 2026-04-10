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
import java.util.HashMap;

import org.apache.activemq.broker.BrokerService;

/**
 * A plain-Java factory that shares a single broker instance across multiple
 * callers using the same configuration file. A use case is multiple web
 * applications that each try to start an embedded broker: only the first one
 * actually starts it; subsequent calls increment a reference count.
 *
 * <p>Previously this class implemented Spring's {@code FactoryBean},
 * {@code InitializingBean} and {@code DisposableBean}. Those interfaces have
 * been removed so that no Spring dependency is required.</p>
 */
public class PooledBrokerFactoryBean {

    static final HashMap<String, SharedBroker> SHARED_BROKER_MAP = new HashMap<>();

    private boolean start;
    private URL config;

    static class SharedBroker {
        BrokerFactoryBean factory;
        int refCount;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    public void afterPropertiesSet() throws Exception {
        String key = config.toExternalForm();
        synchronized (SHARED_BROKER_MAP) {
            SharedBroker sharedBroker = SHARED_BROKER_MAP.get(key);
            if (sharedBroker == null) {
                sharedBroker = new SharedBroker();
                sharedBroker.factory = new BrokerFactoryBean(config);
                sharedBroker.factory.setStart(start);
                sharedBroker.factory.afterPropertiesSet();
                SHARED_BROKER_MAP.put(key, sharedBroker);
            }
            sharedBroker.refCount++;
        }
    }

    public void destroy() throws Exception {
        String key = config.toExternalForm();
        synchronized (SHARED_BROKER_MAP) {
            SharedBroker sharedBroker = SHARED_BROKER_MAP.get(key);
            if (sharedBroker != null) {
                sharedBroker.refCount--;
                if (sharedBroker.refCount == 0) {
                    sharedBroker.factory.destroy();
                    SHARED_BROKER_MAP.remove(key);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Factory method
    // -------------------------------------------------------------------------

    public BrokerService getBroker() throws Exception {
        String key = config.toExternalForm();
        synchronized (SHARED_BROKER_MAP) {
            SharedBroker sharedBroker = SHARED_BROKER_MAP.get(key);
            return (sharedBroker != null) ? sharedBroker.factory.getBroker() : null;
        }
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

    public boolean isStart() {
        return start;
    }

    public void setStart(boolean start) {
        this.start = start;
    }
}
