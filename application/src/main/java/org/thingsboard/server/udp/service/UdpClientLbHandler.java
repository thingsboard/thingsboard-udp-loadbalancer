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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.thingsboard.server.udp.service.context.DefaultUpstreamContext;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class UdpClientLbHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private final DefaultUpstreamContext upstreamContext;

    public UdpClientLbHandler(DefaultUpstreamContext ctx) {
        super(false);
        this.upstreamContext = ctx;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) throws Exception {
        final InetSocketAddress client = packet.sender();

        Map<InetSocketAddress, AtomicLong> disallowedClients = upstreamContext.getDisallowedClients();

        AtomicLong disallowedEndTime = disallowedClients.get(client);
        if (disallowedEndTime != null) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - disallowedEndTime.get() >= upstreamContext.getConf().getConnections().getMaxDisallowedDuration()) {
                disallowedClients.remove(client);
            } else {
                disallowedEndTime.set(currentTime);
                log.debug("[{}] The address is in the blocked list!", client.getAddress());
                return;
            }
        }

        ListenableFuture<ProxyChannel> proxyChannelFuture = upstreamContext.getOrCreateTargetChannel(client, 0);
        Futures.addCallback(proxyChannelFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(@Nullable ProxyChannel proxyChannel) {
                try {
                    if (proxyChannel != null) {
                        proxyChannel.toTarget(packet);
                    }
                } finally {
                    ReferenceCountUtil.release(packet);
                }
            }

            @Override
            public void onFailure(Throwable t) {
                try {
                    log.info("[{}] Unexpected exception: ", client, t);
                } finally {
                    ReferenceCountUtil.release(packet);
                }
            }
        }, upstreamContext.getExecutor());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
        log.warn("[{}] Unexpected exception: ", ctx, cause);
    }
}
