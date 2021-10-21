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

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
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
import org.thingsboard.server.udp.service.resolve.Resolver;
import org.thingsboard.server.udp.service.strategy.LbStrategy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@RequiredArgsConstructor
public class DefaultUpstreamContext implements UpstreamContext {

    private final LbUpstreamProperties conf;
    private final Resolver resolver;
    private final LbStrategy strategy;
    @Getter
    @Setter
    private Channel serverChannel;
    private Bootstrap proxyBootstrap;
    private Map<InetSocketAddress, ProxyChannel> clientsMap = new ConcurrentHashMap<>();
    private Map<Integer, ProxyChannel> proxyPortMap = new ConcurrentHashMap<>();

    public void init(EventLoopGroup workerGroup, ScheduledExecutorService scheduler) {
        proxyBootstrap = new Bootstrap();
        proxyBootstrap.group(workerGroup)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel nioDatagramChannel) {
                        nioDatagramChannel.pipeline().addLast(new UdpProxyLbHandler(DefaultUpstreamContext.this));
                    }
                }).option(ChannelOption.SO_BROADCAST, true);

        //TODO: periodically persist active sessions and restore them here. Use specific port instead of proxyBootstrap.bind(0).
    }

    private InetSocketAddress getNextServer(InetSocketAddress src) throws Exception {
        List<InetAddress> servers = resolver.resolve(conf.getTargetAddress());
        InetAddress server = strategy.get(this, servers, src);
        return new InetSocketAddress(server, conf.getTargetPort());
    }

    @Override
    public ProxyChannel getOrCreateTargetChannel(InetSocketAddress client) throws Exception {
        ProxyChannel result = clientsMap.get(client);
        if (result == null) {
            InetSocketAddress target = getNextServer(client);
            final Channel ch = proxyBootstrap.bind(0).sync().channel();
            int proxyPort = ((InetSocketAddress) ch.localAddress()).getPort();
            result = new ProxyChannel(serverChannel, ch, client, target);
            clientsMap.put(client, result);
            proxyPortMap.put(proxyPort, result);
            if (log.isDebugEnabled()) {
                log.debug("New session: [{}]->[{}] using: {}", client, target, ch.localAddress());
            }
        }
        return result;
    }

    @Override
    public void processReply(DatagramPacket packet) {
        InetSocketAddress proxyAddress = packet.recipient();
        ProxyChannel proxyChannel = proxyPortMap.get(proxyAddress.getPort());
        if (proxyChannel != null) {
            proxyChannel.toClient(packet);
        } else {
            log.trace("[{}] Proxy not found.", proxyAddress);
        }
    }
}
