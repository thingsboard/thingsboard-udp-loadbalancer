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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.thingsboard.server.udp.service.context.LbContext;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "lb.resolver", value = "type", havingValue = "default", matchIfMissing = true)
public class DefaultResolver extends AbstractResolver {

    public DefaultResolver(LbContext context, ApplicationEventPublisher applicationEventPublisher) {
        super(context, applicationEventPublisher);
    }

    @Override
    public List<InetAddress> doResolve(String targetAddress) throws UnknownHostException {
        return Arrays.asList(InetAddress.getAllByName(targetAddress));
    }
}
