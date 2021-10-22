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
package org.thingsboard.server.udp.service.strategy;

import org.thingsboard.server.udp.service.context.DefaultUpstreamContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

public class RoundRobinLbStrategy implements LbStrategy {

    private final Random random;

    public RoundRobinLbStrategy() {
        random = new Random(System.currentTimeMillis());
    }

    @Override
    public InetAddress get(DefaultUpstreamContext ctx, List<InetAddress> targets, InetSocketAddress source) {
        if (targets.isEmpty()) {
            return null;
        } else {
            var size = targets.size();
            if (size == 1) {
                return targets.get(0);
            } else {
                return targets.get(random.nextInt(size));
            }
        }
    }
}
