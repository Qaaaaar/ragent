/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class InMemoryAgentMemory implements AgentMemory {

    private static final String DEFAULT_SESSION_ID = "default";

    private final AgentProperties properties;
    private final ConcurrentMap<String, Deque<MemoryMessage>> store = new ConcurrentHashMap<>();

    @Override
    public List<MemoryMessage> load(String sessionId, int limit) {
        String key = normalizeSessionId(sessionId);
        Deque<MemoryMessage> messages = store.get(key);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int actualLimit = limit > 0 ? limit : properties.getMemory().getMaxMessages();
        synchronized (messages) {
            List<MemoryMessage> snapshot = new ArrayList<>(messages);
            int start = Math.max(0, snapshot.size() - actualLimit);
            return new ArrayList<>(snapshot.subList(start, snapshot.size()));
        }
    }

    @Override
    public void save(String sessionId, MemoryMessage message) {
        if (message == null) {
            return;
        }
        String key = normalizeSessionId(sessionId);
        Deque<MemoryMessage> messages = store.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (messages) {
            messages.addLast(message);
            int maxMessages = Math.max(1, properties.getMemory().getMaxMessages());
            while (messages.size() > maxMessages) {
                messages.removeFirst();
            }
        }
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : DEFAULT_SESSION_ID;
    }
}
