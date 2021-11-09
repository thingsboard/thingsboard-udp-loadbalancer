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
package org.thingsboard.server.udp.storage;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.thingsboard.server.udp.service.context.DefaultUpstreamContext;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "lb.sessions", value = "storage-type", havingValue = "file", matchIfMissing = true)
public class FileSessionPersistenceStorage implements SessionPersistenceStorage {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String TMP_PREFIX = ".tmp";
    public static final String CORRUPTED_PREFIX = ".corrupted";

    @Value("${lb.sessions.file-path:./sessions}")
    private String filePath;

    public void saveSessions(Map<String, DefaultUpstreamContext> upstreams) throws IOException {
        Map<String, Map<Integer, InetSocketAddress>> dnsSessions = new HashMap<>();
        upstreams.forEach((name, upstream) -> {
            Map<Integer, InetSocketAddress> clients = new HashMap<>();
            upstream.getProxyPortMap().forEach((port, proxyChanel) -> clients.put(port, proxyChanel.getClient()));
            dnsSessions.put(name, clients);
        });

        String tmpFilePathStr = filePath + TMP_PREFIX;
        Path tmpFilePath = Paths.get(tmpFilePathStr);

        Files.createFile(tmpFilePath);

        MAPPER.writeValue(new File(tmpFilePathStr), dnsSessions);

        Files.move(tmpFilePath, Paths.get(filePath), StandardCopyOption.REPLACE_EXISTING);
    }

    public Map<String, Map<Integer, InetSocketAddress>> getSessions() throws IOException {
        if (isFileExists(filePath)) {
            try {
                return MAPPER.readValue(new File(filePath), new TypeReference<>() {
                });
            } catch (Exception e) {
                if (e instanceof JsonParseException) {
                    Files.move(Paths.get(filePath), Paths.get(filePath + CORRUPTED_PREFIX));
                }
                log.warn("Failed to fetch Sessions!", e);
            }
        }
        return Collections.emptyMap();
    }

    private boolean isFileExists(String filePath) {
        File f = new File(filePath);
        return f.exists() && !f.isDirectory();
    }
}
