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
package org.thingsboard.server.udp.service.context;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.udp.conf.LbUpstreamProperties;
import org.thingsboard.server.udp.service.ProxyChannel;
import org.thingsboard.server.udp.service.UdpProxyLbHandler;
import org.thingsboard.server.udp.service.resolve.DnsUpdateEvent;
import org.thingsboard.server.udp.service.resolve.Resolver;
import org.thingsboard.server.udp.service.strategy.LbStrategy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class DefaultUpstreamContext implements UpstreamContext {

    private static final Random RND = new Random();

    private final String name;
    private final LbUpstreamProperties conf;
    private final Resolver resolver;
    private final LbStrategy strategy;
    private final Lock channelRegisterLock = new ReentrantLock();

    private LbContext context;
    @Getter
    @Setter
    private Channel clientChannel;
    private Bootstrap proxyBootstrap;
    private Map<InetSocketAddress, ProxyChannel> clientsMap = new ConcurrentHashMap<>();
    @Getter
    private Map<Integer, ProxyChannel> proxyPortMap = new ConcurrentHashMap<>();

    public DefaultUpstreamContext(LbUpstreamProperties conf, Resolver resolver, LbStrategy strategy) {
        this.name = conf.getName();
        this.conf = conf;
        this.resolver = resolver;
        this.strategy = strategy;
    }

    public void init(EventLoopGroup workerGroup, LbContext context) {
        this.context = context;
        proxyBootstrap = new Bootstrap();
        proxyBootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                        nioDatagramChannel.pipeline().addLast(new UdpProxyLbHandler(DefaultUpstreamContext.this));
                    }
                }).option(ChannelOption.SO_BROADCAST, true);

        context.getScheduler().scheduleWithFixedDelay(this::invalidateSessions, RND.nextInt(conf.getConnections().getInvalidateFrequency()), conf.getConnections().getInvalidateFrequency(), TimeUnit.SECONDS);
        context.getScheduler().scheduleWithFixedDelay(this::logSessions, RND.nextInt(conf.getConnections().getLogFrequency()), conf.getConnections().getLogFrequency(), TimeUnit.SECONDS);
    }

    private InetSocketAddress getNextServer(InetSocketAddress src, List<InetAddress> servers) throws Exception {
        if (servers.isEmpty()) {
            return null;
        } else {
            InetAddress server = strategy.get(this, servers, src);
            return server != null ? new InetSocketAddress(server, conf.getTargetPort()) : null;
        }
    }

    @Override
    public ListenableFuture<ProxyChannel> getOrCreateTargetChannel(InetSocketAddress client, int port) {
        ProxyChannel result = clientsMap.get(client);
        if (result == null) {
            SettableFuture<ProxyChannel> resultFuture = SettableFuture.create();
            context.getExecutor().submit(() -> {
                doGetOrCreateTargetChannelSync(client, port, resultFuture);
            });
            return resultFuture;
        } else {
            return Futures.immediateFuture(result);
        }
    }

    private void doGetOrCreateTargetChannelSync(InetSocketAddress client, int port, SettableFuture<ProxyChannel> resultFuture) {
        try {
            InetSocketAddress target = getNextServer(client, resolver.resolve(conf.getTargetAddress()));
            if (target == null) {
                resultFuture.set(null);
            }
            channelRegisterLock.lock();
            try {
                ProxyChannel existing = clientsMap.get(client);
                if (existing != null) {
                    resultFuture.set(existing);
                } else {
                    final Channel targetChannel = proxyBootstrap.bind(port).sync().channel();
                    int proxyPort = ((InetSocketAddress) targetChannel.localAddress()).getPort();
                    ProxyChannel createdChannel = new ProxyChannel(clientChannel, targetChannel, client, target, proxyPort);
                    clientsMap.put(client, createdChannel);
                    proxyPortMap.put(proxyPort, createdChannel);
                    if (log.isDebugEnabled()) {
                        log.debug("[{}] New session: [{}]->[{}] using: {}", name, client, target, targetChannel.localAddress());
                    }
                    resultFuture.set(createdChannel);
                }
            } finally {
                channelRegisterLock.unlock();
            }
        } catch (Exception e) {
            log.debug("Failed to create target channel ", e);
            resultFuture.setException(e);
        }
    }

    @Override
    public void processReply(DatagramPacket packet) {
        InetSocketAddress proxyAddress = packet.recipient();
        ProxyChannel proxyChannel = proxyPortMap.get(proxyAddress.getPort());
        if (proxyChannel != null) {
            proxyChannel.toClient(packet);
        } else {
            log.debug("[{}][{}] Proxy not found.", name, proxyAddress);
        }
    }

    @Override
    public ExecutorService getExecutor() {
        return context.getExecutor();
    }

    public void onDnsUpdate(DnsUpdateEvent dnsUpdateEvent) {
        if (!conf.getTargetAddress().equals(dnsUpdateEvent.getHostname())) {
            return;
        }

        log.info("[{}] Processing DNS update event: {}", name, dnsUpdateEvent);
        for (ProxyChannel proxy : clientsMap.values()) {
            if (dnsUpdateEvent.getRemovedAddresses().contains(proxy.getTarget().getAddress())) {
                context.getExecutor().submit(() -> {
                    try {
                        InetSocketAddress oldTarget = proxy.getTarget();
                        InetSocketAddress newTarget = getNextServer(proxy.getClient(), dnsUpdateEvent.getNewAddresses());
                        if (newTarget != null) {
                            proxy.setTarget(newTarget);
                            if (log.isDebugEnabled()) {
                                log.debug("[{}][{}] Channel updated: [{}]->[{}] using: {}", name, proxy.getClient(), oldTarget, newTarget, proxy.getTargetChannel().localAddress());
                            }
                        } else {
                            close(proxy);
                            log.info("[{}][{}] Removed channel due to no valid target{}", name, proxy.getClient(), Instant.ofEpochMilli(proxy.getLastActivityTime()));
                        }
                    } catch (Exception e) {
                        log.warn("[{}][{}] Failed to update target channel", name, proxy);
                    }
                });
            }
        }
        log.info("[{}] Processed DNS update event: {}", name, dnsUpdateEvent);
    }

    private void invalidateSessions() {
        long expTime = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(conf.getConnections().getTimeout());
        clientsMap.values().stream().filter(proxy -> proxy.getLastActivityTime() < expTime).forEach(proxy -> {
            context.getExecutor().submit(() -> {
                try {
                    close(proxy);
                    log.info("[{}][{}] Removed channel due to inactivity. Last activity time: {}", name, proxy.getClient(), Instant.ofEpochMilli(proxy.getLastActivityTime()));
                } catch (InterruptedException e) {
                    log.warn("[{}][{}] Failed to remove channel due to inactivity", name, proxy.getClient());
                }
            });
        });
    }

    private void logSessions() {
        var clientTableSize = clientsMap.size();
        if (clientTableSize > 0) {
            log.info("[{}] Sessions table size: {}", name, clientTableSize);
        }
        if (log.isTraceEnabled()) {
            clientsMap.values().forEach(proxy -> log.trace("[{}]: {}", name, proxy));
        }
    }

    private void close(ProxyChannel proxy) throws InterruptedException {
        channelRegisterLock.lock();
        try {
            clientsMap.remove(proxy.getClient());
            proxyPortMap.remove(proxy.getProxyPort());
        } finally {
            channelRegisterLock.unlock();
        }
        proxy.getTargetChannel().close().sync();
    }

}
