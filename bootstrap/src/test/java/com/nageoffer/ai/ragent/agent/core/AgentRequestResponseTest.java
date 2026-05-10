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

package com.nageoffer.ai.ragent.agent.core;

import com.nageoffer.ai.ragent.agent.rag.AgentDocument;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRequestResponseTest {

    @Test
    void shouldBuildAgentRequestAndResponse() {
        AgentRequest request = AgentRequest.builder()
                .userInput("hello")
                .sessionId("s1")
                .metadata(Map.of("topK", 3))
                .deepThinking(true)
                .maxIterations(2)
                .build();

        AgentResponse response = AgentResponse.builder()
                .answer("world")
                .steps(List.of(AgentStep.builder().index(1).actionType("LLM_ANSWER").build()))
                .toolResults(List.of(ToolCallResult.builder().toolName("weather").success(true).build()))
                .retrievedContext(List.of(AgentDocument.builder().id("d1").content("doc").build()))
                .success(true)
                .build();

        assertEquals("hello", request.getUserInput());
        assertTrue(request.isEnableRag());
        assertTrue(request.isEnableToolCalling());
        assertTrue(request.isDeepThinking());
        assertEquals(3, request.getMetadata().get("topK"));
        assertEquals("world", response.getAnswer());
        assertEquals(1, response.getSteps().size());
        assertEquals(1, response.getToolResults().size());
        assertEquals(1, response.getRetrievedContext().size());
        assertTrue(response.isSuccess());
    }
}
