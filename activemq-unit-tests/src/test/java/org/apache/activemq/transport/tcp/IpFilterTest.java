/**
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
package org.apache.activemq.transport.tcp;

import java.net.InetAddress;

import junit.framework.TestCase;

public class IpFilterTest extends TestCase {

    public void testParseNull() {
        assertNull(IpFilter.parse(null));
        assertNull(IpFilter.parse(""));
        assertNull(IpFilter.parse("  "));
    }

    public void testExactMatch() throws Exception {
        IpFilter filter = IpFilter.parse("192.168.1.1");
        assertTrue(filter.matches(InetAddress.getByName("192.168.1.1")));
        assertFalse(filter.matches(InetAddress.getByName("192.168.1.2")));
    }

    public void testCidrMatch() throws Exception {
        IpFilter filter = IpFilter.parse("192.168.1.0/24");
        assertTrue(filter.matches(InetAddress.getByName("192.168.1.0")));
        assertTrue(filter.matches(InetAddress.getByName("192.168.1.1")));
        assertTrue(filter.matches(InetAddress.getByName("192.168.1.255")));
        assertFalse(filter.matches(InetAddress.getByName("192.168.2.1")));
    }

    public void testCidr16() throws Exception {
        IpFilter filter = IpFilter.parse("10.0.0.0/8");
        assertTrue(filter.matches(InetAddress.getByName("10.0.0.1")));
        assertTrue(filter.matches(InetAddress.getByName("10.255.255.255")));
        assertFalse(filter.matches(InetAddress.getByName("11.0.0.1")));
    }

    public void testMultiplePatterns() throws Exception {
        IpFilter filter = IpFilter.parse("192.168.1.0/24, 10.0.0.1");
        assertTrue(filter.matches(InetAddress.getByName("192.168.1.50")));
        assertTrue(filter.matches(InetAddress.getByName("10.0.0.1")));
        assertFalse(filter.matches(InetAddress.getByName("10.0.0.2")));
        assertFalse(filter.matches(InetAddress.getByName("172.16.0.1")));
    }

    public void testLoopback() throws Exception {
        IpFilter filter = IpFilter.parse("127.0.0.0/8");
        assertTrue(filter.matches(InetAddress.getByName("127.0.0.1")));
        assertTrue(filter.matches(InetAddress.getByName("127.0.0.2")));
        assertFalse(filter.matches(InetAddress.getByName("192.168.1.1")));
    }

    public void testInvalidPattern() {
        try {
            IpFilter.parse("not-an-ip");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    public void testInvalidCidrPrefix() {
        try {
            IpFilter.parse("192.168.1.0/33");
            fail("Should have thrown IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }
}
