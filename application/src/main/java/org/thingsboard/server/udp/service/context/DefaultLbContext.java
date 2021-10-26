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

import lombok.Getter;
import org.springframework.stereotype.Component;
import org.thingsboard.server.udp.util.ThingsBoardThreadFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Component
public class DefaultLbContext implements LbContext {

    @Getter
    private ScheduledExecutorService scheduler;
    @Getter
    private ExecutorService executor;


    @PostConstruct
    public void init() {
        scheduler = Executors.newSingleThreadScheduledExecutor(ThingsBoardThreadFactory.forName("lb-scheduler"));
        int coreSize = Runtime.getRuntime().availableProcessors();
        executor =  new ThreadPoolExecutor(coreSize, coreSize, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), ThingsBoardThreadFactory.forName("lb-executor"));
        ((ThreadPoolExecutor)executor).allowCoreThreadTimeOut(true);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

}
