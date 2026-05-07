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

package com.nageoffer.ai.ragent.agent.executor;

import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.memory.InMemoryAgentMemory;
import com.nageoffer.ai.ragent.agent.planner.AgentPlan;
import com.nageoffer.ai.ragent.agent.planner.AgentPlanner;
import com.nageoffer.ai.ragent.agent.planner.ToolCallSpec;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
import com.nageoffer.ai.ragent.agent.rag.AgentDocument;
import com.nageoffer.ai.ragent.agent.rag.AgentRagRetriever;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import com.nageoffer.ai.ragent.agent.tool.ToolRegistry;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentIntegrationTest {

    @Test
    void shouldAnswerDirectly() {
        CountingRagRetriever ragRetriever = new CountingRagRetriever();
        StubAgentTool tool = StubAgentTool.success("weather");
        DefaultAgent agent = newAgent(fixedPlanner(directPlan()), ragRetriever, tool, new StubLlmService("direct final"), 3);

        AgentResponse response = agent.run(request("hello", 3));

        assertTrue(response.isSuccess());
        assertEquals("direct final", response.getAnswer());
        assertEquals(0, ragRetriever.calls);
        assertEquals(0, tool.calls);
    }

    @Test
    void shouldRunRagPath() {
        CountingRagRetriever ragRetriever = new CountingRagRetriever();
        DefaultAgent agent = newAgent(fixedPlanner(ragPlan()), ragRetriever, StubAgentTool.success("weather"), new StubLlmService("rag final"), 3);

        AgentResponse response = agent.run(request("use documents", 3));

        assertTrue(response.isSuccess());
        assertEquals(1, ragRetriever.calls);
        assertEquals(1, response.getRetrievedContext().size());
    }

    @Test
    void shouldRunMcpToolPath() {
        CountingRagRetriever ragRetriever = new CountingRagRetriever();
        StubAgentTool tool = StubAgentTool.success("weather");
        DefaultAgent agent = newAgent(fixedPlanner(toolPlan("weather", Map.of("city", "Hangzhou"))),
                ragRetriever, tool, new StubLlmService("tool final"), 3);

        AgentResponse response = agent.run(request("weather", 3));

        assertTrue(response.isSuccess());
        assertEquals(0, ragRetriever.calls);
        assertEquals(1, tool.calls);
        assertEquals(1, response.getToolResults().size());
        assertTrue(response.getToolResults().get(0).isSuccess());
    }

    @Test
    void shouldRunRagAndMcpCombinedPath() {
        CountingRagRetriever ragRetriever = new CountingRagRetriever();
        StubAgentTool tool = StubAgentTool.success("weather");
        DefaultAgent agent = newAgent(fixedPlanner(ragAndToolPlan("weather")),
                ragRetriever, tool, new StubLlmService("combined final"), 3);

        AgentResponse response = agent.run(request("docs plus weather", 3));

        assertTrue(response.isSuccess());
        assertEquals(1, ragRetriever.calls);
        assertEquals(1, tool.calls);
        assertEquals(1, response.getRetrievedContext().size());
        assertEquals(1, response.getToolResults().size());
    }

    @Test
    void shouldContinueToFinalAnswerWhenToolFails() {
        StubAgentTool tool = StubAgentTool.failure("weather");
        DefaultAgent agent = newAgent(fixedPlanner(toolPlan("weather", Map.of("city", "Hangzhou"))),
                new CountingRagRetriever(), tool, new StubLlmService("fallback final"), 3);

        AgentResponse response = agent.run(request("weather", 3));

        assertTrue(response.isSuccess());
        assertEquals("fallback final", response.getAnswer());
        assertEquals(1, response.getToolResults().size());
        assertFalse(response.getToolResults().get(0).isSuccess());
    }

    @Test
    void shouldStopAtMaxIterations() {
        StubAgentTool tool = StubAgentTool.success("weather");
        DefaultAgent agent = newAgent(new CountingToolPlanner(), new CountingRagRetriever(), tool, new StubLlmService("max final"), 2);

        AgentResponse response = agent.run(request("keep using tool", 2));

        assertTrue(response.isSuccess());
        assertEquals(2, tool.calls);
        assertEquals(2, response.getToolResults().size());
        assertTrue(response.getSteps().stream().anyMatch(step -> "max-iterations".equals(step.getActionName())));
    }

    private DefaultAgent newAgent(AgentPlanner planner, AgentRagRetriever ragRetriever,
                                  AgentTool tool, LLMService llmService, int maxIterations) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxIterations(maxIterations);
        properties.getMemory().setMaxMessages(20);
        ToolRegistry toolRegistry = new ToolRegistry(new EmptyMcpToolRegistry());
        if (tool != null) {
            toolRegistry.register(tool);
        }
        return new DefaultAgent(
                properties,
                planner,
                new InMemoryAgentMemory(properties),
                ragRetriever,
                toolRegistry,
                new AgentPromptBuilder(),
                llmService
        );
    }

    private AgentRequest request(String userInput, int maxIterations) {
        return AgentRequest.builder()
                .sessionId("s1")
                .userInput(userInput)
                .maxIterations(maxIterations)
                .build();
    }

    private AgentPlanner fixedPlanner(AgentPlan plan) {
        return context -> plan;
    }

    private AgentPlan directPlan() {
        return AgentPlan.builder()
                .shouldAnswerDirectly(true)
                .reasoningSummary("Direct answer is enough.")
                .maxIterations(3)
                .build();
    }

    private AgentPlan ragPlan() {
        return AgentPlan.builder()
                .needRag(true)
                .reasoningSummary("Need RAG.")
                .maxIterations(3)
                .build();
    }

    private AgentPlan toolPlan(String toolName, Map<String, Object> arguments) {
        return AgentPlan.builder()
                .needTool(true)
                .toolCalls(List.of(ToolCallSpec.builder()
                        .toolName(toolName)
                        .arguments(new LinkedHashMap<>(arguments))
                        .build()))
                .reasoningSummary("Need MCP tool.")
                .maxIterations(3)
                .build();
    }

    private AgentPlan ragAndToolPlan(String toolName) {
        return AgentPlan.builder()
                .needRag(true)
                .needTool(true)
                .toolCalls(List.of(ToolCallSpec.builder()
                        .toolName(toolName)
                        .arguments(new LinkedHashMap<>(Map.of("city", "Hangzhou")))
                        .build()))
                .reasoningSummary("Need RAG and MCP tool.")
                .maxIterations(3)
                .build();
    }

    private static class CountingToolPlanner implements AgentPlanner {

        private int count;

        @Override
        public AgentPlan plan(AgentContext context) {
            count++;
            return AgentPlan.builder()
                    .needTool(true)
                    .toolCalls(List.of(ToolCallSpec.builder()
                            .toolName("weather")
                            .arguments(new LinkedHashMap<>(Map.of("round", count)))
                            .build()))
                    .reasoningSummary("Need another tool call.")
                    .maxIterations(2)
                    .build();
        }
    }

    private static class CountingRagRetriever implements AgentRagRetriever {

        private int calls;

        @Override
        public List<AgentDocument> retrieve(String query, AgentContext context) {
            calls++;
            return List.of(AgentDocument.builder()
                    .id("doc-1")
                    .content("retrieved context")
                    .source("test")
                    .score(0.9D)
                    .build());
        }
    }

    private static class StubAgentTool implements AgentTool {

        private final String name;
        private final boolean success;
        private int calls;

        private StubAgentTool(String name, boolean success) {
            this.name = name;
            this.success = success;
        }

        static StubAgentTool success(String name) {
            return new StubAgentTool(name, true);
        }

        static StubAgentTool failure(String name) {
            return new StubAgentTool(name, false);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub tool";
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of();
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            calls++;
            return ToolCallResult.builder()
                    .toolName(name)
                    .arguments(arguments)
                    .rawResult(success ? "tool ok" : "")
                    .success(success)
                    .errorMessage(success ? null : "tool failed")
                    .latencyMs(1L)
                    .build();
        }
    }

    private static class StubLlmService implements LLMService {

        private final String answer;

        private StubLlmService(String answer) {
            this.answer = answer;
        }

        @Override
        public String chat(ChatRequest request) {
            return answer;
        }

        @Override
        public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
            return () -> {
            };
        }
    }

    private static class EmptyMcpToolRegistry implements McpToolRegistry {

        @Override
        public void register(McpToolExecutor executor) {
        }

        @Override
        public void unregister(String toolId) {
        }

        @Override
        public Optional<McpToolExecutor> getExecutor(String toolId) {
            return Optional.empty();
        }

        @Override
        public List<Tool> listAllTools() {
            return List.of();
        }

        @Override
        public List<McpToolExecutor> listAllExecutors() {
            return List.of();
        }

        @Override
        public boolean contains(String toolId) {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
