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
package org.thingsboard.server.udp.service;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.util.ResourceLeakDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;
import org.thingsboard.server.udp.conf.LbProperties;
import org.thingsboard.server.udp.conf.LbUpstreamProperties;
import org.thingsboard.server.udp.service.context.DefaultUpstreamContext;
import org.thingsboard.server.udp.service.context.LbContext;
import org.thingsboard.server.udp.service.resolve.DnsUpdateEvent;
import org.thingsboard.server.udp.service.resolve.Resolver;
import org.thingsboard.server.udp.service.strategy.RoundRobinLbStrategy;
import org.thingsboard.server.udp.storage.SessionPersistenceStorage;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class BootstrapService implements ApplicationListener<DnsUpdateEvent> {

    private static final String ALL_ADDRESSES = "0.0.0.0";
    private final LbContext context;
    private final LbProperties properties;
    private final Resolver resolver;
    private final SessionPersistenceStorage sessionsStorage;
    private final Map<String, DefaultUpstreamContext> upstreams = new ConcurrentHashMap<>();

    @Value("${lb.netty.worker_group_thread_count:4}")
    private int workerGroupThreadCount;
    @Value("${lb.netty.leak_detection_lvl:SIMPLE}")
    private String leakDetectionLevel;

    @Value("${lb.sessions.persistence:true}")
    private boolean persistence;
    @Value("${lb.sessions.persistence_interval:600}")
    private long persistenceInterval;

    private EventLoopGroup workerGroup;

    @PostConstruct
    public void init() throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.valueOf(leakDetectionLevel));
        log.info("Starting ThingsBoard UDP Load Balancer Service...");
        workerGroup = new NioEventLoopGroup(workerGroupThreadCount);

        int upstreamSize = properties.getUpstreams().size();
        int uniqueUpstreamSize = properties.getUpstreams().stream().map(LbUpstreamProperties::getName).collect(Collectors.toSet()).size();
        if (upstreamSize == 0) {
            log.error("No upstream servers configured!");
            System.exit(-1);
        } else if (upstreamSize != uniqueUpstreamSize) {
            log.error("Upstream server names are not unique!");
            System.exit(-1);
        }

        properties.getUpstreams().forEach(p -> {
            try {
                resolver.resolve(p.getTargetAddress());
            } catch (Exception e) {
                log.warn("Failed resolve target address [{}]!", p.getTargetAddress(), e);
            }
        });

        properties.setUpstreams(duplicateInterfacesIfBindSeparately(properties.getUpstreams()));

        Map<String, Map<Integer, InetSocketAddress>> fetchedDnsSessions = sessionsStorage.getSessions();

        for (LbUpstreamProperties upstreamProperties : properties.getUpstreams()) {
            final DefaultUpstreamContext ctx = new DefaultUpstreamContext(upstreamProperties, resolver, new RoundRobinLbStrategy());
            ctx.init(workerGroup, context);
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                            nioDatagramChannel.pipeline().addLast(new UdpClientLbHandler(ctx));
                        }
                    });
            if (!upstreamProperties.isBindSeparately() || upstreamProperties.getBindAddress().equals(ALL_ADDRESSES)) {
                b.option(ChannelOption.SO_BROADCAST, true);
            }
            log.info("[{}] Binding to: {}:{}", upstreamProperties.getName(), upstreamProperties.getBindAddress(), upstreamProperties.getBindPort());
            Channel serverChannel = b.bind(upstreamProperties.getBindAddress(), upstreamProperties.getBindPort())
                    .sync().channel();
            ctx.setClientChannel(serverChannel);
            upstreams.put(upstreamProperties.getName(), ctx);
            Map<Integer, InetSocketAddress> clients = fetchedDnsSessions.get(upstreamProperties.getName());
            if (clients != null) {
                clients.forEach((port, client) -> ctx.getOrCreateTargetChannel(client, port));
            }
        }

        if (persistence) {
            context.getScheduler().scheduleWithFixedDelay(() -> {
                try {
                    sessionsStorage.saveSessions(upstreams);
                } catch (IOException e) {
                    log.error("Failed to persist DNS sessions!", e);
                }
            }, persistenceInterval, persistenceInterval, TimeUnit.SECONDS);
        }

        log.info("ThingsBoard UDP Load Balancer Service started!");
    }

    private List<LbUpstreamProperties> duplicateInterfacesIfBindSeparately(List<LbUpstreamProperties> src) {
        return src.stream().flatMap(
                original -> {
                    if (original.isBindSeparately() && original.getBindAddress().equals(ALL_ADDRESSES)) {
                        try {
                            List<NetworkInterface> networkInterfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
                            if (hasOnlyOneInterface(networkInterfaces)) {
                                return Stream.of(original);
                            } else {
                                Set<InetAddress> allIps = new LinkedHashSet<>();
                                for (NetworkInterface networkInterface : networkInterfaces) {
                                    allIps.addAll(Collections.list(networkInterface.getInetAddresses()));
                                }
                                return allIps.stream().map(original::copy);
                            }
                        } catch (SocketException e) {
                            log.warn("[{}] Failed to bind to all addresses due to: ", original.getName(), e);
                            return Stream.of(original);
                        }
                    } else {
                        return Stream.of(original);
                    }
                }
        ).collect(Collectors.toList());
    }

    private boolean hasOnlyOneInterface(List<NetworkInterface> networkInterfaces) {
        return networkInterfaces.size() == 2 && networkInterfaces.stream().anyMatch(ni -> ni.getName().equalsIgnoreCase("lo"));
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Stopping ThingsBoard UDP Load Balancer Service Service!");
        try {
            for (DefaultUpstreamContext ctx : upstreams.values()) {
                ctx.getClientChannel().close().sync();
            }
        } finally {
            workerGroup.shutdownGracefully();
        }
        log.info("ThingsBoard UDP Load Balancer Service stopped!");
    }

    @Override
    public void onApplicationEvent(DnsUpdateEvent dnsUpdateEvent) {
        for (DefaultUpstreamContext ctx : upstreams.values()) {
            ctx.onDnsUpdate(dnsUpdateEvent);
        }
    }
}
