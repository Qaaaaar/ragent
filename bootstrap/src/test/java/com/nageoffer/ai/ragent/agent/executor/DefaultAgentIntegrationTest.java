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

import com.nageoffer.ai.ragent.agent.clarification.AgentClarificationService;
import com.nageoffer.ai.ragent.agent.core.AgentActionType;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import com.nageoffer.ai.ragent.agent.memory.InMemoryAgentMemory;
import com.nageoffer.ai.ragent.agent.planner.AgentPlanner;
import com.nageoffer.ai.ragent.agent.planner.AgentPlannerResult;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultAgentIntegrationTest {

    @Test
    void shouldReturnPlannerDirectAnswer() {
        DefaultAgent agent = newAgent(fixedPlanner(successResult("direct final")));

        AgentResponse response = agent.run(request("hello", 3));

        assertTrue(response.isSuccess());
        assertEquals("direct final", response.getAnswer());
        assertEquals(1, response.getSteps().size());
        assertEquals(AgentActionType.LLM_ANSWER.name(), response.getSteps().get(0).getActionType());
    }

    @Test
    void shouldReturnPlannerToolPathResult() {
        ToolCallResult weatherResult = ToolCallResult.builder()
                .toolName("weather")
                .arguments(Map.of("city", "Hangzhou"))
                .rawResult("tool ok")
                .success(true)
                .build();
        DefaultAgent agent = newAgent(fixedPlanner(successResult(
                "tool final",
                List.of(toolStep("weather", "{\"city\":\"Hangzhou\"}", "tool ok")),
                List.of(weatherResult)
        )));

        AgentResponse response = agent.run(request("weather", 3));

        assertTrue(response.isSuccess());
        assertEquals("tool final", response.getAnswer());
        assertEquals(1, response.getToolResults().size());
        assertEquals("weather", response.getToolResults().get(0).getToolName());
    }

    @Test
    void shouldReturnPlannerKnowledgeAndMcpCombinedPath() {
        ToolCallResult knowledgeResult = ToolCallResult.builder()
                .toolName(AgentToolNames.KNOWLEDGE_SEARCH)
                .rawResult("{\"count\":1,\"chunks\":[{}]}")
                .success(true)
                .build();
        ToolCallResult weatherResult = ToolCallResult.builder()
                .toolName("weather")
                .rawResult("tool ok")
                .success(true)
                .build();
        DefaultAgent agent = newAgent(fixedPlanner(successResult(
                "combined final",
                List.of(
                        toolStep(AgentToolNames.KNOWLEDGE_SEARCH, "{\"query\":\"docs\"}", knowledgeResult.getRawResult()),
                        toolStep("weather", "{\"city\":\"Hangzhou\"}", "tool ok")
                ),
                List.of(knowledgeResult, weatherResult)
        )));

        AgentResponse response = agent.run(request("docs plus weather", 3));

        assertTrue(response.isSuccess());
        assertEquals("combined final", response.getAnswer());
        assertEquals(2, response.getToolResults().size());
    }

    @Test
    void shouldReturnPlannerFailure() {
        DefaultAgent agent = newAgent(context -> AgentPlannerResult.builder()
                .success(false)
                .errorMessage("planner failed")
                .steps(context.getSteps())
                .toolResults(context.getToolResults())
                .retrievedContext(context.getRetrievedContext())
                .build());

        AgentResponse response = agent.run(request("hello", 3));

        assertFalse(response.isSuccess());
        assertEquals("planner failed", response.getErrorMessage());
    }

    @Test
    void shouldReturnClarificationBeforePlanning() {
        CountingPlanner planner = new CountingPlanner(successResult("should not answer"));
        CountingClarificationService clarificationService = new CountingClarificationService("Please choose a system.");
        DefaultAgent agent = newAgent(planner, clarificationService, 3);

        AgentResponse response = agent.run(request("query process", 3));

        assertTrue(response.isSuccess());
        assertTrue(response.isClarificationRequired());
        assertEquals("Please choose a system.", response.getAnswer());
        assertEquals(1, clarificationService.calls);
        assertEquals(0, planner.calls);
        assertTrue(response.getSteps().stream()
                .anyMatch(step -> AgentActionType.CLARIFY.name().equals(step.getActionType())));
    }

    @Test
    void shouldSkipClarificationWhenRequestDisablesRag() {
        CountingPlanner planner = new CountingPlanner(successResult("direct final"));
        CountingClarificationService clarificationService = new CountingClarificationService("Please choose a system.");
        DefaultAgent agent = newAgent(planner, clarificationService, 3);

        AgentRequest request = request("hello", 3);
        request.setEnableRag(false);
        AgentResponse response = agent.run(request);

        assertTrue(response.isSuccess());
        assertFalse(response.isClarificationRequired());
        assertEquals("direct final", response.getAnswer());
        assertEquals(0, clarificationService.calls);
        assertEquals(1, planner.calls);
    }

    @Test
    void shouldSkipClarificationWhenAgentRagDisabled() {
        CountingPlanner planner = new CountingPlanner(successResult("direct final"));
        CountingClarificationService clarificationService = new CountingClarificationService("Please choose a system.");
        AgentProperties properties = new AgentProperties();
        properties.getRag().setEnabled(false);
        DefaultAgent agent = newAgent(properties, planner, clarificationService);

        AgentResponse response = agent.run(request("hello", 3));

        assertTrue(response.isSuccess());
        assertFalse(response.isClarificationRequired());
        assertEquals("direct final", response.getAnswer());
        assertEquals(0, clarificationService.calls);
        assertEquals(1, planner.calls);
    }

    private DefaultAgent newAgent(AgentPlanner planner) {
        return newAgent(planner, AgentClarificationService.empty(), 3);
    }

    private DefaultAgent newAgent(AgentPlanner planner, AgentClarificationService clarificationService,
                                  int maxIterations) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxIterations(maxIterations);
        properties.getMemory().setMaxMessages(20);
        return newAgent(properties, planner, clarificationService);
    }

    private DefaultAgent newAgent(AgentProperties properties, AgentPlanner planner,
                                  AgentClarificationService clarificationService) {
        return new DefaultAgent(
                properties,
                planner,
                new InMemoryAgentMemory(properties),
                clarificationService
        );
    }

    private AgentRequest request(String userInput, int maxIterations) {
        return AgentRequest.builder()
                .sessionId("s1")
                .userInput(userInput)
                .maxIterations(maxIterations)
                .build();
    }

    private AgentPlanner fixedPlanner(AgentPlannerResult result) {
        return context -> result;
    }

    private AgentPlannerResult successResult(String answer) {
        return successResult(answer,
                List.of(answerStep(answer)),
                List.of());
    }

    private AgentPlannerResult successResult(String answer, List<AgentStep> steps, List<ToolCallResult> toolResults) {
        return AgentPlannerResult.builder()
                .answer(answer)
                .steps(steps)
                .toolResults(toolResults)
                .success(true)
                .build();
    }

    private AgentStep answerStep(String answer) {
        return AgentStep.builder()
                .index(1)
                .thoughtSummary("Final answer.")
                .actionType(AgentActionType.LLM_ANSWER.name())
                .actionName("final-answer")
                .output(answer)
                .build();
    }

    private AgentStep toolStep(String toolName, String input, String output) {
        return AgentStep.builder()
                .index(1)
                .thoughtSummary("Need tool.")
                .actionType(AgentActionType.MCP_TOOL_CALL.name())
                .actionName(toolName)
                .input(input)
                .output(output)
                .build();
    }

    private static class CountingPlanner implements AgentPlanner {

        private final AgentPlannerResult result;
        private int calls;

        private CountingPlanner(AgentPlannerResult result) {
            this.result = result;
        }

        @Override
        public AgentPlannerResult plan(AgentContext context) {
            calls++;
            return result;
        }
    }

    private static class CountingClarificationService implements AgentClarificationService {

        private final String prompt;
        private int calls;

        private CountingClarificationService(String prompt) {
            this.prompt = prompt;
        }

        @Override
        public Optional<String> detectClarification(AgentContext context) {
            calls++;
            return Optional.ofNullable(prompt);
        }
    }
}
