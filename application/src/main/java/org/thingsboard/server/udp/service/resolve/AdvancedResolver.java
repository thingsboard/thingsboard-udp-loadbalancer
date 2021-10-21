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

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddressStreamProvider;
import io.netty.resolver.dns.MultiDnsServerAddressStreamProvider;
import io.netty.resolver.dns.SingletonDnsServerAddressStreamProvider;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.thingsboard.server.udp.conf.LbProperties;
import org.thingsboard.server.udp.service.context.LbContext;

import javax.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
@ConditionalOnExpression("'${lb.resolver.type:null}'=='advanced'")
public class AdvancedResolver extends AbstractResolver {

    @Value("${lb.resolver.servers:60}")
    private String servers;
    private DnsNameResolver dnsResolver;
    private EventLoopGroup eventLoopGroup;

    public AdvancedResolver(LbContext context, ApplicationEventPublisher applicationEventPublisher, LbProperties lbProperties) {
        super(context, applicationEventPublisher, lbProperties);
        this.eventLoopGroup = new NioEventLoopGroup(1);
    }

    @Override
    public void init() {
        DnsNameResolverBuilder builder = new DnsNameResolverBuilder(eventLoopGroup.next());
        builder.channelType(NioDatagramChannel.class);

        String[] serversArray = servers.split(",");

        if (serversArray.length > 1) {
            DnsServerAddressStreamProvider[] providers = new DnsServerAddressStreamProvider[serversArray.length];
            for (int i = 0; i < serversArray.length; i++) {
                providers[i] = new SingletonDnsServerAddressStreamProvider(createSocketAddress(serversArray[i]));
            }

            builder.nameServerProvider(new MultiDnsServerAddressStreamProvider(providers));
        } else {
            builder.nameServerProvider(new SingletonDnsServerAddressStreamProvider(createSocketAddress(serversArray[0])));
        }

        dnsResolver = builder.build();
        super.init();
    }

    private InetSocketAddress createSocketAddress(String address) {
        String[] hostPort = address.split(":");
        return new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]));
    }

    @SneakyThrows
    @Override
    public List<InetAddress> doResolve(String targetAddress) throws UnknownHostException {
        return dnsResolver.resolveAll(targetAddress).get();
    }

    @PreDestroy
    private void destroy() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
    }
}
