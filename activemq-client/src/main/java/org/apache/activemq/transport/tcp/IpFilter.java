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
package org.apache.activemq.transport.tcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility to match IP addresses against a list of patterns.
 * Supports exact IP addresses (e.g. {@code 192.168.1.1}) and
 * CIDR notation (e.g. {@code 192.168.1.0/24}).
 */
public class IpFilter {

    private final List<CidrEntry> entries;

    private IpFilter(List<CidrEntry> entries) {
        this.entries = entries;
    }

    /**
     * Parses a comma-separated list of IP patterns into an IpFilter.
     * Returns {@code null} if the input is null or blank.
     *
     * @param patterns comma-separated IP addresses or CIDR blocks
     * @return an IpFilter, or null if no patterns provided
     * @throws IllegalArgumentException if any pattern is invalid
     */
    public static IpFilter parse(String patterns) {
        if (patterns == null || patterns.trim().isEmpty()) {
            return null;
        }
        List<CidrEntry> entries = new ArrayList<>();
        for (String token : patterns.split(",")) {
            token = token.trim();
            if (!token.isEmpty()) {
                entries.add(CidrEntry.parse(token));
            }
        }
        return entries.isEmpty() ? null : new IpFilter(Collections.unmodifiableList(entries));
    }

    /**
     * Returns true if the given address matches any entry in this filter.
     */
    public boolean matches(InetAddress address) {
        for (CidrEntry entry : entries) {
            if (entry.matches(address)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return entries.toString();
    }

    private static class CidrEntry {
        private final byte[] network;
        private final int prefixLength;

        CidrEntry(byte[] network, int prefixLength) {
            this.network = network;
            this.prefixLength = prefixLength;
        }

        static CidrEntry parse(String pattern) {
            String ipPart;
            int prefix;
            int slashIndex = pattern.indexOf('/');
            if (slashIndex >= 0) {
                ipPart = pattern.substring(0, slashIndex);
                try {
                    prefix = Integer.parseInt(pattern.substring(slashIndex + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid CIDR prefix in: " + pattern, e);
                }
            } else {
                ipPart = pattern;
                prefix = -1; // will be set to full length below
            }

            InetAddress addr;
            try {
                addr = InetAddress.getByName(ipPart);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP address in: " + pattern, e);
            }

            byte[] addrBytes = addr.getAddress();
            int maxPrefix = addrBytes.length * 8;
            if (prefix < 0) {
                prefix = maxPrefix;
            }
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException("Invalid CIDR prefix length " + prefix + " in: " + pattern);
            }

            return new CidrEntry(addrBytes, prefix);
        }

        boolean matches(InetAddress address) {
            byte[] addrBytes = address.getAddress();
            // IPv4 vs IPv6 mismatch
            if (addrBytes.length != network.length) {
                return false;
            }

            int fullBytes = prefixLength / 8;
            int remainingBits = prefixLength % 8;

            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != network[i]) {
                    return false;
                }
            }

            if (remainingBits > 0 && fullBytes < addrBytes.length) {
                int mask = (0xFF << (8 - remainingBits)) & 0xFF;
                if ((addrBytes[fullBytes] & mask) != (network[fullBytes] & mask)) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String toString() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
            } catch (UnknownHostException e) {
                return "invalid";
            }
        }
    }
}
