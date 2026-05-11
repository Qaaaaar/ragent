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

package com.nageoffer.ai.ragent.rag.core.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.config.RagEvaluationProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAsJudgeServiceTest {

    @Test
    void shouldParseJudgeJsonInsideMarkdownFence() {
        LlmAsJudgeService service = new LlmAsJudgeService(
                new StubLlmService("{}"),
                new ObjectMapper(),
                new RagEvaluationProperties()
        );

        JudgeResult result = service.parse("""
                ```json
                {
                  "faithfulness": {"score": 5, "reason": "grounded"},
                  "answerRelevancy": {"score": 4, "reason": "mostly answers"},
                  "answerCorrectness": {"score": 5, "reason": "correct"},
                  "citationSupport": [
                    {"claim": "claim", "citationChunkId": "chunk_1", "score": 5, "supported": true, "reason": "ok"}
                  ],
                  "overallPass": true
                }
                ```
                """);

        assertEquals(5, result.getFaithfulness().getScore());
        assertEquals(4, result.getAnswerRelevancy().getScore());
        assertTrue(result.getCitationSupport().get(0).isSupported());
        assertTrue(result.isOverallPass());
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
