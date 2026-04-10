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

import java.net.URI;

import org.apache.activemq.broker.BrokerContextAware;
import org.apache.activemq.broker.BrokerFactoryHandler;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.spring.DefaultBrokerContext;
import org.apache.activemq.spring.Utils;
import org.apache.activemq.util.IntrospectionSupport;
import org.apache.activemq.util.URISupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.util.Map;

/**
 * Creates a {@link BrokerService} from an XBean XML configuration URI using the
 * SPI-registered {@link BrokerFactoryHandler} mechanism. XML parsing is
 * performed by {@link XBeanBrokerLoader} without any Spring dependency.
 */
public class XBeanBrokerFactory implements BrokerFactoryHandler {

    private static final Logger LOG = LoggerFactory.getLogger(XBeanBrokerFactory.class);

    private boolean validate = true;

    public boolean isValidate() {
        return validate;
    }

    public void setValidate(boolean validate) {
        this.validate = validate;
    }

    @Override
    public BrokerService createBroker(URI config) throws Exception {
        String uri = config.getSchemeSpecificPart();
        if (uri.lastIndexOf('?') != -1) {
            IntrospectionSupport.setProperties(this, URISupport.parseQuery(uri));
            uri = uri.substring(0, uri.lastIndexOf('?'));
        }

        URL resource = Utils.resourceFromString(uri);
        LOG.debug("Loading broker configuration from {}", resource);

        XBeanBrokerLoader loader = new XBeanBrokerLoader();
        BrokerService broker = loader.loadBroker(resource);
        Map<String, Object> registry = loader.getBeanRegistry();

        DefaultBrokerContext brokerContext = new DefaultBrokerContext(registry, uri);
        if (broker instanceof BrokerContextAware) {
            ((BrokerContextAware) broker).setBrokerContext(brokerContext);
        } else {
            broker.setBrokerContext(brokerContext);
        }

        return broker;
    }
}
