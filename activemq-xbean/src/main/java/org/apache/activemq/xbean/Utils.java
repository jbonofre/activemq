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

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * Resolves a string path/URI to a {@link URL} using plain Java mechanisms:
 * filesystem check, explicit URL schemes, and classpath fallback.
 */
public class Utils {

    /**
     * Resolve {@code uri} to a {@link URL}.
     *
     * <ul>
     *   <li>If the path points to an existing file on the filesystem it is
     *       returned as a {@code file:} URL.</li>
     *   <li>Strings beginning with {@code classpath:} are resolved against the
     *       context class loader.</li>
     *   <li>Any valid URL string (containing {@code ://}) is returned directly.</li>
     *   <li>Otherwise the string is treated as a classpath resource.</li>
     * </ul>
     *
     * @throws MalformedURLException if the resource cannot be resolved
     */
    public static URL resourceFromString(String uri) throws MalformedURLException {
        // 1. Filesystem check
        File file = new File(uri);
        if (file.exists()) {
            return file.toURI().toURL();
        }

        // 2. Explicit classpath: prefix
        if (uri.startsWith("classpath:")) {
            String path = uri.substring("classpath:".length());
            URL url = classLoader().getResource(path);
            if (url != null) {
                return url;
            }
            throw new MalformedURLException("Classpath resource not found: " + path);
        }

        // 3. Explicit URL with scheme (http://, file://, etc.)
        if (uri.contains("://") || uri.startsWith("file:")) {
            try {
                return new URL(uri);
            } catch (MalformedURLException e) {
                // fall through to classpath lookup
            }
        }

        // 4. Classpath fallback
        URL url = classLoader().getResource(uri);
        if (url != null) {
            return url;
        }

        throw new MalformedURLException("Cannot resolve URI: " + uri);
    }

    private static ClassLoader classLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return (cl != null) ? cl : Utils.class.getClassLoader();
    }
}
