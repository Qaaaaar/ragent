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

import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Primary
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class ConversationAgentMemory implements AgentMemory {

    private final ConversationMemoryService conversationMemoryService;
    private final InMemoryAgentMemory fallbackMemory;

    @Override
    public List<MemoryMessage> load(String sessionId, int limit) {
        String userId = UserContext.getUserId();
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            return fallbackMemory.load(sessionId, limit);
        }
        List<MemoryMessage> messages;
        try {
            messages = conversationMemoryService.load(sessionId, userId).stream()
                    .map(this::toMemoryMessage)
                    .filter(message -> StringUtils.hasText(message.getContent()))
                    .toList();
        } catch (Exception ex) {
            log.warn("Load conversation-backed Agent memory failed, sessionId={}", sessionId, ex);
            return fallbackMemory.load(sessionId, limit);
        }
        if (messages.isEmpty()) {
            return fallbackMemory.load(sessionId, limit);
        }
        int actualLimit = limit > 0 ? limit : messages.size();
        int start = Math.max(0, messages.size() - actualLimit);
        return messages.subList(start, messages.size());
    }

    @Override
    public void save(String sessionId, MemoryMessage message) {
        fallbackMemory.save(sessionId, message);
    }

    private MemoryMessage toMemoryMessage(ChatMessage message) {
        if (message.getRole() == null) {
            return MemoryMessage.builder()
                    .role(MemoryMessage.ROLE_USER)
                    .content(message.getContent())
                    .build();
        }
        String role = switch (message.getRole()) {
            case USER -> MemoryMessage.ROLE_USER;
            case ASSISTANT -> MemoryMessage.ROLE_ASSISTANT;
            case SYSTEM -> "system";
        };
        return MemoryMessage.builder()
                .role(role)
                .content(message.getContent())
                .build();
    }
}
