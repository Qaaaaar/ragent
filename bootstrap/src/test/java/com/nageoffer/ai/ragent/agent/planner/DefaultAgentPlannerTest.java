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

import com.nageoffer.ai.ragent.agent.core.AgentActionType;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentPlannerTest {

    private static final String NO_KNOWLEDGE_EVIDENCE_ANSWER =
            "\u672a\u68c0\u7d22\u5230\u4e0e\u95ee\u9898\u76f8\u5173\u7684\u6587\u6863\u5185\u5bb9\u3002";

    @Test
    void shouldRunActionObservationThenFinalAnswer() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need live weather.
                        Action: weather
                        Action Input: {"city":"Hangzhou"}
                        """,
                """
                        Thought: Have the weather result.
                        Final Answer: Hangzhou is sunny.
                        """
        );
        StubAgentTool weather = StubAgentTool.success("weather", "weather ok");
        DefaultAgentPlanner planner = newPlanner(llmService, weather);

        AgentPlannerResult result = planner.plan(context("weather in Hangzhou"));

        assertTrue(result.isSuccess());
        assertEquals("Hangzhou is sunny.", result.getAnswer());
        assertEquals(1, weather.calls);
        assertEquals("Hangzhou", weather.lastArguments.get("city"));
        assertEquals(1, result.getToolResults().size());
        assertEquals(2, result.getSteps().size());
        assertEquals(AgentActionType.MCP_TOOL_CALL.name(), result.getSteps().get(0).getActionType());
        assertEquals("weather ok", result.getSteps().get(0).getOutput());
        assertTrue(llmService.promptAt(1).contains("Observation: weather ok"));
    }

    @Test
    void shouldReturnFinalAnswerWithoutToolCall() {
        StubLlmService llmService = new StubLlmService("""
                Thought: This can be answered directly.
                Final Answer: hello
                """);
        StubAgentTool weather = StubAgentTool.success("weather", "weather ok");
        DefaultAgentPlanner planner = newPlanner(llmService, weather);

        AgentPlannerResult result = planner.plan(context("hello"));

        assertTrue(result.isSuccess());
        assertEquals("hello", result.getAnswer());
        assertEquals(0, weather.calls);
        assertEquals(1, result.getSteps().size());
        assertEquals(AgentActionType.LLM_ANSWER.name(), result.getSteps().get(0).getActionType());
    }

    @Test
    void shouldRecordUnknownToolAsFailedObservation() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need a missing tool.
                        Action: missing_tool
                        Action Input: {"query":"x"}
                        """,
                """
                        Thought: Tool is unavailable.
                        Final Answer: missing_tool is not available.
                        """
        );
        DefaultAgentPlanner planner = newPlanner(llmService);

        AgentPlannerResult result = planner.plan(context("use missing tool"));

        assertTrue(result.isSuccess());
        assertEquals(1, result.getToolResults().size());
        assertFalse(result.getToolResults().get(0).isSuccess());
        assertEquals("Tool not found: missing_tool", result.getToolResults().get(0).getErrorMessage());
        assertTrue(llmService.promptAt(1).contains("Observation: Tool not found: missing_tool"));
    }

    @Test
    void shouldSkipDuplicateToolCallAndContinue() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need weather once.
                        Action: weather
                        Action Input: {"city":"Hangzhou"}
                        """,
                """
                        Thought: Try the same call again.
                        Action: weather
                        Action Input: {"city":"Hangzhou"}
                        """,
                """
                        Thought: Duplicate was skipped.
                        Final Answer: weather ok
                        """
        );
        StubAgentTool weather = StubAgentTool.success("weather", "weather ok");
        DefaultAgentPlanner planner = newPlanner(llmService, weather);

        AgentPlannerResult result = planner.plan(context("repeat weather"));

        assertTrue(result.isSuccess());
        assertEquals(1, weather.calls);
        assertEquals(1, result.getToolResults().size());
        assertTrue(result.getSteps().stream()
                .anyMatch(step -> "Skipped duplicate tool call: weather".equals(step.getOutput())));
    }

    @Test
    void shouldReturnDisabledToolObservationWithoutCallingTool() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need weather.
                        Action: weather
                        Action Input: {"city":"Hangzhou"}
                        """,
                """
                        Thought: Tool calling is disabled.
                        Final Answer: Tool calling is disabled.
                        """
        );
        StubAgentTool weather = StubAgentTool.success("weather", "weather ok");
        DefaultAgentPlanner planner = newPlanner(llmService, weather);
        AgentContext context = context("weather");
        context.getRequest().setEnableToolCalling(false);

        AgentPlannerResult result = planner.plan(context);

        assertTrue(result.isSuccess());
        assertEquals(0, weather.calls);
        assertEquals(0, result.getToolResults().size());
        assertTrue(result.getSteps().stream()
                .anyMatch(step -> "Tool calling disabled.".equals(step.getOutput())));
    }

    @Test
    void shouldReturnKnowledgeDisabledObservationWithoutCallingTool() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need internal docs.
                        Action: knowledge_search
                        Action Input: {"query":"policy"}
                        """,
                """
                        Thought: Knowledge search is disabled.
                        Final Answer: Knowledge search is disabled.
                        """
        );
        StubAgentTool knowledge = StubAgentTool.success(AgentToolNames.KNOWLEDGE_SEARCH, "{\"count\":1,\"chunks\":[{}]}");
        DefaultAgentPlanner planner = newPlanner(llmService, knowledge);
        AgentContext context = context("policy");
        context.getRequest().setEnableRag(false);

        AgentPlannerResult result = planner.plan(context);

        assertTrue(result.isSuccess());
        assertEquals(0, knowledge.calls);
        assertEquals(0, result.getToolResults().size());
        assertTrue(result.getSteps().stream()
                .anyMatch(step -> "Knowledge search disabled for this request.".equals(step.getOutput())));
    }

    @Test
    void shouldFinalizeAfterMaxIterations() {
        StubLlmService llmService = new StubLlmService(
                """
                        Thought: Need weather.
                        Action: weather
                        Action Input: {"city":"Hangzhou"}
                        """,
                """
                        Thought: Must answer now.
                        Final Answer: final after limit
                        """
        );
        StubAgentTool weather = StubAgentTool.success("weather", "weather ok");
        AgentProperties properties = new AgentProperties();
        properties.setMaxIterations(1);
        DefaultAgentPlanner planner = newPlanner(properties, llmService, weather);

        AgentPlannerResult result = planner.plan(context("weather"));

        assertTrue(result.isSuccess());
        assertEquals("final after limit", result.getAnswer());
        assertEquals(1, weather.calls);
        assertTrue(result.getSteps().stream()
                .anyMatch(step -> "max-iterations".equals(step.getActionName())));
        assertTrue(llmService.promptAt(1).contains("You must return Final Answer now."));
    }

    @Test
    void shouldGuardEmptyKnowledgeSearchResult() {
        StubLlmService llmService = new StubLlmService("""
                Thought: Need internal docs.
                Action: knowledge_search
                Action Input: {"query":"policy"}
                """);
        StubAgentTool knowledge = StubAgentTool.success(AgentToolNames.KNOWLEDGE_SEARCH, "{\"count\":0,\"chunks\":[]}");
        DefaultAgentPlanner planner = newPlanner(llmService, knowledge);

        AgentPlannerResult result = planner.plan(context("policy"));

        assertTrue(result.isSuccess());
        assertEquals(NO_KNOWLEDGE_EVIDENCE_ANSWER, result.getAnswer());
        assertEquals(1, knowledge.calls);
        assertEquals(1, llmService.calls);
        assertEquals(AgentActionType.LLM_ANSWER.name(), result.getSteps().get(1).getActionType());
    }

    private DefaultAgentPlanner newPlanner(StubLlmService llmService, AgentTool... tools) {
        return newPlanner(new AgentProperties(), llmService, tools);
    }

    private DefaultAgentPlanner newPlanner(AgentProperties properties, StubLlmService llmService, AgentTool... tools) {
        ToolRegistry registry = toolRegistryWith(tools);
        return new DefaultAgentPlanner(llmService, registry, new AgentPromptBuilder(), properties);
    }

    private AgentContext context(String userInput) {
        return AgentContext.builder()
                .request(AgentRequest.builder()
                        .userInput(userInput)
                        .sessionId("s1")
                        .build())
                .build();
    }

    private ToolRegistry toolRegistryWith(AgentTool... tools) {
        ToolRegistry registry = new ToolRegistry(new EmptyMcpToolRegistry());
        for (AgentTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }

    private static class StubAgentTool implements AgentTool {

        private final String name;
        private final String rawResult;
        private int calls;
        private Map<String, Object> lastArguments = new LinkedHashMap<>();

        private StubAgentTool(String name, String rawResult) {
            this.name = name;
            this.rawResult = rawResult;
        }

        static StubAgentTool success(String name, String rawResult) {
            return new StubAgentTool(name, rawResult);
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
            lastArguments = new LinkedHashMap<>(arguments);
            return ToolCallResult.builder()
                    .toolName(name)
                    .arguments(arguments)
                    .rawResult(rawResult)
                    .success(true)
                    .latencyMs(1L)
                    .build();
        }
    }

    private static class StubLlmService implements LLMService {

        private final List<String> responses;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int calls;

        private StubLlmService(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String chat(ChatRequest request) {
            requests.add(request);
            String response = calls < responses.size()
                    ? responses.get(calls)
                    : responses.get(responses.size() - 1);
            calls++;
            return response;
        }

        String promptAt(int index) {
            return requests.get(index).getMessages().get(0).getContent();
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
