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

import java.util.Hashtable;
import java.util.Properties;

import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * A {@link JndiLookupFactory} that creates an {@link InitialContext} from a
 * configurable set of JNDI environment properties.
 *
 * <p>This replaces the former Spring {@code JndiTemplate}-based implementation
 * with a plain-Java equivalent that carries no Spring dependency.</p>
 */
public class JndiTemplateLookupFactory extends JndiLookupFactory {

    private final Properties environment;

    /**
     * Creates a factory that performs JNDI lookups using the supplied
     * environment properties.
     *
     * @param environment JNDI initial-context environment; may be {@code null}
     *                    to use the default context
     */
    public JndiTemplateLookupFactory(Properties environment) {
        this.environment = environment;
    }

    @Override
    public <T> T lookup(String name, Class<T> clazz) throws NamingException {
        Hashtable<Object, Object> env = null;
        if (environment != null && !environment.isEmpty()) {
            env = new Hashtable<>(environment);
        }
        InitialContext ctx = (env != null) ? new InitialContext(env) : new InitialContext();
        try {
            return clazz.cast(ctx.lookup(name));
        } finally {
            ctx.close();
        }
    }
}
