/**
 * Copyright © 2021-2021 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.udp.util;

public class IpUtil {

    public static String getCIDR(String ip, int cidr) {
        if (cidr > 32) {
            throw new NumberFormatException("CIDR can not be greater than 32");
        }

        int i = 24;
        int baseIpNumeric = 0;

        for (String s : ip.split("\\.")) {
            int value = Integer.parseInt(s);
            baseIpNumeric += value << i;
            i -= 8;
        }

        /* netmask from CIDR */
        if (cidr < 8) {
            throw new NumberFormatException("Netmask CIDR can not be less than 8");
        }
        int netmaskNumeric = 0xffffffff;
        netmaskNumeric = netmaskNumeric << (32 - cidr);

        for (i = 0; i < 32; i++) {
            if ((netmaskNumeric << i) == 0) {
                break;
            }
        }
        return convertNumericIpToSymbolic(baseIpNumeric & netmaskNumeric) + "/" + i;
    }

    private static String convertNumericIpToSymbolic(Integer ip) {
        StringBuilder sb = new StringBuilder(15);

        for (int shift = 24; shift > 0; shift -= 8) {
            // process 3 bytes, from high order byte down.
            sb.append(Integer.toString((ip >>> shift) & 0xff));

            sb.append('.');
        }
        sb.append((ip & 0xff));

        return sb.toString();
    }

    public static void main(String[] args) {
        System.out.println(getCIDR("192.168.161.125", 24));
    }
}
