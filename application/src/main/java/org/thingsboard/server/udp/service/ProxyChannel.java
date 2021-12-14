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

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.socket.DatagramPacket;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.udp.util.TbRateLimits;

import java.net.InetSocketAddress;

@Slf4j
@Data
public class ProxyChannel {

    private final Channel clientChannel;
    private Channel targetChannel;
    private final InetSocketAddress client;
    private InetSocketAddress target;
    private final int proxyPort;
    private volatile long lastActivityTime;
    private final TbRateLimits rateLimits;

    public ProxyChannel(Channel clientChannel, Channel targetChannel, InetSocketAddress client, InetSocketAddress target, int proxyPort, String limitsConfiguration) {
        this.clientChannel = clientChannel;
        this.targetChannel = targetChannel;
        this.client = client;
        this.target = target;
        this.proxyPort = proxyPort;
        this.lastActivityTime = System.currentTimeMillis();
        this.rateLimits = new TbRateLimits(limitsConfiguration);
    }

    public void toTarget(DatagramPacket packet) {
        if (!rateLimits.tryConsume()) {
                log.info("[{}] Failed to send request. Max request limit reached", client.getAddress());
            return;
        }

        send(client, target, targetChannel, packet.content());
        lastActivityTime = System.currentTimeMillis();
    }

    public void toClient(DatagramPacket packet) {
        send(target, client, clientChannel, packet.content());
        lastActivityTime = System.currentTimeMillis();
    }

    private static void send(InetSocketAddress client, InetSocketAddress target, Channel channel, ByteBuf data) {
        DatagramPacket targetPacket = new DatagramPacket(data, target);
        targetPacket.retain();
        log.trace("[{}]->[{}] Write {} bytes", client, target, targetPacket.content().readableBytes());
        //TODO: optimize to flush periodically or if buffered to much data. Maybe channelWritabilityChanged?
        channel.writeAndFlush(targetPacket);
    }

    @Override
    public String toString() {
        return "[" + client + "][" + target + "][" + proxyPort + "][" + lastActivityTime + "][" + client + "][" + target + "]";
    }
}
