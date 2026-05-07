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

package com.nageoffer.ai.ragent.agent.planner;

import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentPlannerTest {

    @Test
    void shouldParseJsonPlanFromLlm() {
        StubLlmService llmService = new StubLlmService("""
                {
                  "needRag": true,
                  "needTool": true,
                  "toolCalls": [{"toolName":"weather","arguments":{"city":"Hangzhou"}}],
                  "shouldAnswerDirectly": false,
                  "reasoningSummary": "Need docs and live weather.",
                  "maxIterations": 2
                }
                """);
        ToolRegistry registry = toolRegistryWith(new StubAgentTool("weather", "weather lookup"));
        DefaultAgentPlanner planner = new DefaultAgentPlanner(llmService, registry, new AgentPromptBuilder(), new AgentProperties());

        AgentPlan plan = planner.plan(context("Please use docs and weather tool"));

        assertTrue(plan.isNeedRag());
        assertTrue(plan.isNeedTool());
        assertFalse(plan.isShouldAnswerDirectly());
        assertEquals(1, plan.getToolCalls().size());
        assertEquals("weather", plan.getToolCalls().get(0).getToolName());
        assertEquals("Hangzhou", plan.getToolCalls().get(0).getArguments().get("city"));
        assertEquals(2, plan.getMaxIterations());
    }

    @Test
    void shouldFallbackToRulesWhenPlannerJsonFails() {
        StubLlmService llmService = new StubLlmService("not json");
        ToolRegistry registry = toolRegistryWith(new StubAgentTool("ticket", "ticket status lookup"));
        DefaultAgentPlanner planner = new DefaultAgentPlanner(llmService, registry, new AgentPromptBuilder(), new AgentProperties());

        AgentPlan plan = planner.plan(context("查询内部文档依据，并查看实时工单状态"));

        assertTrue(plan.isNeedRag());
        assertTrue(plan.isNeedTool());
        assertFalse(plan.isShouldAnswerDirectly());
        assertEquals(1, plan.getToolCalls().size());
    }

    private AgentContext context(String userInput) {
        return AgentContext.builder()
                .request(AgentRequest.builder()
                        .userInput(userInput)
                        .sessionId("s1")
                        .build())
                .build();
    }

    private ToolRegistry toolRegistryWith(AgentTool tool) {
        ToolRegistry registry = new ToolRegistry(new EmptyMcpToolRegistry());
        registry.register(tool);
        return registry;
    }

    private record StubAgentTool(String name, String description) implements AgentTool {

        @Override
        public Map<String, Object> schema() {
            return Map.of();
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            return ToolCallResult.builder().toolName(name).success(true).build();
        }
    }

    private static class StubLlmService implements LLMService {

        private final String response;

        private StubLlmService(String response) {
            this.response = response;
        }

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
