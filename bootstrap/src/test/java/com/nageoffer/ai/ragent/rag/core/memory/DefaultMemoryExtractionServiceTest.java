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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagMemoryDO;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryScopeType;
import com.nageoffer.ai.ragent.rag.enums.RagMemoryStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMemoryExtractionServiceTest {

    @Test
    void shouldPersistExplicitMemoryEvenWhenExtractorRejectsIt() {
        RecordingPersistenceService persistenceService = new RecordingPersistenceService();
        DefaultMemoryExtractionService service = serviceWith(
                "[{\"shouldRemember\":false}]",
                persistenceService
        );
        String question = "\u4ee5\u540e\u56de\u7b54\u6545\u969c\u95ee\u9898\u5148\u7ed9\u6392\u67e5\u6b65\u9aa4\uff0c\u8bf7\u8bb0\u4f4f";

        service.extractAsync(context(question, "answer"));

        assertEquals(1, persistenceService.commands.size());
        RagMemoryCommand command = persistenceService.commands.get(0);
        assertEquals(RagMemoryScopeType.USER.name(), command.getScopeType());
        assertEquals(RagMemoryStatus.ACTIVE.name(), command.getStatus());
        assertEquals(question, command.getContent());
        assertEquals(1D, command.getConfidence());
    }

    @Test
    void shouldDropLowConfidenceCandidate() {
        RecordingPersistenceService persistenceService = new RecordingPersistenceService();
        DefaultMemoryExtractionService service = serviceWith(
                """
                        [{
                          "shouldRemember": true,
                          "scopeType": "USER",
                          "memoryLevel": "LONG_TERM",
                          "memoryType": "preference",
                          "title": "Low confidence",
                          "content": "Prefer verbose answers.",
                          "confidence": 0.2,
                          "importance": 80
                        }]
                        """,
                persistenceService
        );

        service.extractAsync(context("normal question", "answer"));

        assertTrue(persistenceService.commands.isEmpty());
    }

    @Test
    void shouldRouteOrganizationCandidateToPendingReview() {
        RecordingPersistenceService persistenceService = new RecordingPersistenceService();
        DefaultMemoryExtractionService service = serviceWith(
                """
                        [{
                          "shouldRemember": true,
                          "scopeType": "ORG",
                          "memoryLevel": "LONG_TERM",
                          "memoryType": "terminology",
                          "title": "CMDB",
                          "content": "CMDB means configuration management database in this organization.",
                          "keywords": ["CMDB"],
                          "confidence": 0.91,
                          "importance": 70
                        }]
                        """,
                persistenceService
        );

        service.extractAsync(context("What does CMDB mean?", "CMDB is a shared internal term."));

        assertEquals(1, persistenceService.commands.size());
        RagMemoryCommand command = persistenceService.commands.get(0);
        assertEquals(RagMemoryScopeType.ORG.name(), command.getScopeType());
        assertEquals(RagMemoryStatus.PENDING_REVIEW.name(), command.getStatus());
        assertNull(command.getOwnerUserId());
        assertEquals(List.of("CMDB"), command.getKeywords());
    }

    private DefaultMemoryExtractionService serviceWith(String response,
                                                       RecordingPersistenceService persistenceService) {
        MemoryProperties properties = new MemoryProperties();
        properties.setLongTermEnabled(true);
        properties.setAutoExtractionEnabled(true);
        properties.setMinConfidence(0.78D);
        return new DefaultMemoryExtractionService(
                properties,
                new StubLlmService(response),
                new ObjectMapper(),
                persistenceService,
                Runnable::run
        );
    }

    private MemoryExtractionContext context(String question, String answer) {
        return MemoryExtractionContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .userQuestion(question)
                .assistantAnswer(answer)
                .sourceMessageIds(List.of("message-1"))
                .build();
    }

    private static class RecordingPersistenceService implements RagMemoryPersistenceService {

        private final List<RagMemoryCommand> commands = new ArrayList<>();

        @Override
        public RagMemoryDO create(RagMemoryCommand command) {
            commands.add(command);
            return RagMemoryDO.builder().id("memory-" + commands.size()).build();
        }

        @Override
        public void refreshEmbedding(RagMemoryDO memory) {
        }

        @Override
        public boolean existsActiveLike(RagMemoryCommand command) {
            return false;
        }
    }

    private record StubLlmService(String response) implements LLMService {

        @Override
        public String chat(ChatRequest request) {
            return response;
        }

        @Override
        public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
            return () -> {
            };
        }
    }
}
