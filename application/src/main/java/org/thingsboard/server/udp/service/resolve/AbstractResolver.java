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

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.thingsboard.server.udp.service.context.LbContext;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public abstract class AbstractResolver implements Resolver {

    private final LbContext context;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Map<String, List<InetAddress>> dnsMap = new ConcurrentHashMap<>();

    @Value("${lb.resolver.validity-time:60}")
    private int dnsRecordValidityTimeInSeconds;

    @PostConstruct
    public void init() {
        context.getScheduler().scheduleWithFixedDelay(this::checkDnsUpdates, dnsRecordValidityTimeInSeconds, dnsRecordValidityTimeInSeconds, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {

    }

    private void checkDnsUpdates() {

    }

    @Override
    public List<InetAddress> resolve(String targetAddress) throws UnknownHostException {
        List<InetAddress> addresses = dnsMap.get(targetAddress);
        if (addresses == null) {
            addresses = doResolve(targetAddress);
            dnsMap.put(targetAddress, addresses);
        }
        return addresses;
    }

    public abstract List<InetAddress> doResolve(String targetAddress) throws UnknownHostException;
}
