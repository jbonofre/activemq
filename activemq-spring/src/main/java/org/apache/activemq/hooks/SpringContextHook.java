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
package org.apache.activemq.hooks;

import java.io.Closeable;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A broker shutdown hook that closes a {@link Closeable} context (e.g. a
 * resource or connection factory) when the broker stops.
 *
 * <p>Previously this class held a direct reference to a Spring
 * {@code ApplicationContext} and closed it on shutdown. It has been refactored
 * to operate on any {@link Closeable}, removing the Spring dependency.</p>
 */
public class SpringContextHook implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(SpringContextHook.class);

    private final Closeable context;

    /**
     * Creates a hook that will close {@code context} when the broker stops.
     *
     * @param context the resource to close on broker shutdown
     */
    public SpringContextHook(Closeable context) {
        this.context = context;
    }

    @Override
    public void run() {
        if (context != null) {
            try {
                context.close();
            } catch (IOException e) {
                LOG.warn("Error closing context on broker shutdown: {}", e.getMessage(), e);
            }
        }
    }
}
