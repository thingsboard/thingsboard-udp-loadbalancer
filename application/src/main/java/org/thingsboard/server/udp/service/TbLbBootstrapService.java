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
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thingsboard.server.udp.conf.LbProperties;
import org.thingsboard.server.udp.conf.LbUpstreamProperties;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service("ThingsboardK8sDnsService")
@Slf4j
public class TbLbBootstrapService {

    @Autowired
    private LbProperties properties;
    @Value("${lb.netty.worker_group_thread_count}")
    private Integer workerGroupThreadCount;

    private EventLoopGroup workerGroup;
    private Map<String, UpstreamContext> upstreams = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() throws Exception {
        log.info("Starting ThingsBoard UDP Load Balancer Service...");
        workerGroup = new NioEventLoopGroup(workerGroupThreadCount);

        int upstreamSize = properties.getUpstreams().size();
        int uniqueUpstreamSize = properties.getUpstreams().stream().map(LbUpstreamProperties::getName).collect(Collectors.toSet()).size();
        if (upstreamSize < 0) {
            log.error("No upstream servers configured!");
            System.exit(-1);
        } else if (upstreamSize != uniqueUpstreamSize) {
            log.error("Upstream server names are not unique!");
            System.exit(-1);
        }

        for (LbUpstreamProperties upstreamProperties : properties.getUpstreams()) {
            final UpstreamContext ctx = new UpstreamContext(upstreamProperties);
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                    .channel(NioDatagramChannel.class)
                    .handler(new ChannelInitializer<NioDatagramChannel>() {
                        @Override
                        protected void initChannel(NioDatagramChannel nioDatagramChannel) throws Exception {
//                            nioDatagramChannel.pipeline().addLast(new UdpMessageHandler(ctx));
                        }
                    }).option(ChannelOption.SO_BROADCAST, true)
                    .option(ChannelOption.SO_KEEPALIVE, true);

            Channel serverChannel = b.bind(upstreamProperties.getBindAddress(), upstreamProperties.getBindPort())
                    .sync().channel();
            ctx.setServerChannel(serverChannel);
            upstreams.put(upstreamProperties.getName(), ctx);
        }

        log.info("ThingsBoard UDP Load Balancer Service started!");
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        log.info("Stopping ThingsBoard UDP Load Balancer Service Service!");
        try {
            for (UpstreamContext ctx : upstreams.values()) {
                ctx.getServerChannel().close().sync();
            }
        } finally {
            workerGroup.shutdownGracefully();
        }
        log.info("ThingsBoard UDP Load Balancer Service stopped!");
    }
}
