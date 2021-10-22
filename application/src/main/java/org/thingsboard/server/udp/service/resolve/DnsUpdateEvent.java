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
package org.thingsboard.server.udp.service.resolve;

import lombok.Getter;
import lombok.ToString;
import org.springframework.context.ApplicationEvent;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

@ToString
public class DnsUpdateEvent extends ApplicationEvent {

    @Getter
    private final String hostname;
    @Getter
    private final List<InetAddress> newAddresses;
    @Getter
    private final Set<InetAddress> removedAddresses;

    public DnsUpdateEvent(Object source, String hostname, List<InetAddress> newAddresses, Set<InetAddress> removedAddresses) {
        super(source);
        this.hostname = hostname;
        this.newAddresses = newAddresses;
        this.removedAddresses = removedAddresses;
    }

}
