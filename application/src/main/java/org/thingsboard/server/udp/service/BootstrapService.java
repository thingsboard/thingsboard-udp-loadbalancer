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

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class BootstrapService implements ApplicationListener<DnsUpdateEvent> {

    private final LbContext context;
    private final LbProperties properties;
    private final Resolver resolver;
    private final Map<String, DefaultUpstreamContext> upstreams = new ConcurrentHashMap<>();

    @Value("${lb.netty.worker_group_thread_count:4}")
    private int workerGroupThreadCount;
    @Value("${lb.netty.leak_detection_lvl:SIMPLE}")
    private String leakDetectionLevel;

    @Value("${lb.sessions.persist:true}")
    private boolean persist;
    @Value("${lb.sessions.persisting_interval:600}")
    private long persistingInterval;
    @Value("${lb.sessions.file-path:./sessions}")
    private String filePath;

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

        Map<String, Map<InetSocketAddress, Integer>> fetchedDnsSessions = fetchDnsSessions();

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
                    }).option(ChannelOption.SO_BROADCAST, true);

            Channel serverChannel = b.bind(upstreamProperties.getBindAddress(), upstreamProperties.getBindPort())
                    .sync().channel();
            ctx.setClientChannel(serverChannel);
            upstreams.put(upstreamProperties.getName(), ctx);
            Map<InetSocketAddress, Integer> clients = fetchedDnsSessions.get(upstreamProperties.getName());
            if (clients != null) {
                clients.forEach(ctx::getOrCreateTargetChannel);
            }
        }

        if (persist) {
            context.getScheduler().scheduleWithFixedDelay(() -> {
                try {
                    persistDnsSessions();
                } catch (IOException e) {
                    log.error("Failed to persist DNS sessions!", e);
                }
            }, persistingInterval, persistingInterval, TimeUnit.SECONDS);
        }

        log.info("ThingsBoard UDP Load Balancer Service started!");
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

    private void persistDnsSessions() throws IOException {
        Map<String, Map<InetSocketAddress, Integer>> dnsSessions = new HashMap<>();
        upstreams.forEach((name, upstream) -> {
            Map<InetSocketAddress, Integer> clients = new HashMap<>();
            upstream.getProxyPortMap().forEach((port, proxyChanel) -> clients.put(proxyChanel.getClient(), port));
            dnsSessions.put(name, clients);
        });

        String tmpFilePathStr = filePath + ".tmp";
        Path tmpFilePath = Paths.get(tmpFilePathStr);

        Files.createFile(tmpFilePath);

        try (FileOutputStream fos = new FileOutputStream(tmpFilePathStr)) {
            try (ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                oos.writeObject(dnsSessions);
            }
        }

        Path targetFilePath = Paths.get(filePath);

        if (isFileExists(filePath)) {
            Files.delete(targetFilePath);
        }

        Files.move(tmpFilePath, targetFilePath);
    }

    private Map<String, Map<InetSocketAddress, Integer>> fetchDnsSessions() {
        if (isFileExists(filePath)) {
            try (FileInputStream fis = new FileInputStream(filePath)) {
                try (ObjectInputStream ois = new ObjectInputStream(fis)) {
                    return (Map<String, Map<InetSocketAddress, Integer>>) ois.readObject();
                }
            } catch (IOException | ClassNotFoundException e) {
                log.error("Failed to fetch DNS sessions!", e);
            }
        }
        return Collections.emptyMap();
    }

    private boolean isFileExists(String filePath) {
        File f = new File(filePath);
        return f.exists() && !f.isDirectory();
    }
}
