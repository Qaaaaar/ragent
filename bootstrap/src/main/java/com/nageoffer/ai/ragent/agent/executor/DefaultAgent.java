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
import com.nageoffer.ai.ragent.agent.core.Agent;
import com.nageoffer.ai.ragent.agent.core.AgentActionType;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import com.nageoffer.ai.ragent.agent.memory.AgentMemory;
import com.nageoffer.ai.ragent.agent.memory.MemoryMessage;
import com.nageoffer.ai.ragent.agent.planner.AgentPlanner;
import com.nageoffer.ai.ragent.agent.planner.AgentPlannerResult;
import com.nageoffer.ai.ragent.agent.rag.AgentDocument;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultAgent implements Agent {

    private static final int TOOL_MEMORY_LIMIT = 800;

    private final AgentProperties properties;
    private final AgentPlanner planner;
    private final AgentMemory memory;
    private final AgentClarificationService clarificationService;

    @Override
    @RagTraceNode(name = "agent-run", type = "AGENT")
    public AgentResponse run(AgentRequest request) {
        AgentRequest actualRequest = normalizeRequest(request);
        AgentContext context = AgentContext.builder()
                .request(actualRequest)
                .memoryMessages(memory.load(actualRequest.getSessionId(), properties.getMemory().getMaxMessages()))
                .build();

        log.info("Agent run started, sessionId={}, maxIterations={}",
                actualRequest.getSessionId(), resolveMaxIterations(actualRequest));

        try {
            Optional<String> clarification = detectClarification(context);
            if (clarification.isPresent()) {
                String prompt = clarification.get();
                addStep(context, AgentActionType.CLARIFY, "intent-guidance", actualRequest.getUserInput(), prompt, 0L,
                        "Detected ambiguous knowledge intent and asked for clarification.");
                saveMemory(actualRequest, prompt);
                log.info("Agent clarification returned, sessionId={}, steps={}",
                        actualRequest.getSessionId(), context.getSteps().size());
                return AgentResponse.builder()
                        .answer(prompt)
                        .steps(context.getSteps())
                        .toolResults(context.getToolResults())
                        .retrievedContext(context.getRetrievedContext())
                        .success(true)
                        .clarificationRequired(true)
                        .build();
            }

            AgentPlannerResult result = planner.plan(context);
            if (result == null) {
                throw new IllegalStateException("Agent planner returned null result");
            }
            if (!result.isSuccess()) {
                return AgentResponse.builder()
                        .answer("")
                        .steps(safeSteps(result, context))
                        .toolResults(safeToolResults(result, context))
                        .retrievedContext(safeRetrievedContext(result, context))
                        .success(false)
                        .clarificationRequired(false)
                        .errorMessage(result.getErrorMessage())
                        .build();
            }

            saveToolMemories(actualRequest.getSessionId(), safeToolResults(result, context));
            saveMemory(actualRequest, result.getAnswer());
            log.info("Agent run finished, sessionId={}, retrievedContext={}, toolResults={}, steps={}, success=true",
                    actualRequest.getSessionId(),
                    safeRetrievedContext(result, context).size(),
                    safeToolResults(result, context).size(),
                    safeSteps(result, context).size());
            return AgentResponse.builder()
                    .answer(result.getAnswer())
                    .steps(safeSteps(result, context))
                    .toolResults(safeToolResults(result, context))
                    .retrievedContext(safeRetrievedContext(result, context))
                    .success(true)
                    .clarificationRequired(false)
                    .build();
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Agent run failed, sessionId={}, steps={}, reason={}",
                    actualRequest.getSessionId(), context.getSteps().size(), reason, e);
            return AgentResponse.builder()
                    .answer("")
                    .steps(context.getSteps())
                    .toolResults(context.getToolResults())
                    .retrievedContext(context.getRetrievedContext())
                    .success(false)
                    .clarificationRequired(false)
                    .errorMessage(reason)
                    .build();
        }
    }

    private Optional<String> detectClarification(AgentContext context) {
        if (!isKnowledgeSearchEnabled(context.getRequest())) {
            return Optional.empty();
        }
        return clarificationService.detectClarification(context);
    }

    private void saveMemory(AgentRequest request, String answer) {
        memory.save(request.getSessionId(), MemoryMessage.builder()
                .role(MemoryMessage.ROLE_USER)
                .content(request.getUserInput())
                .build());
        memory.save(request.getSessionId(), MemoryMessage.builder()
                .role(MemoryMessage.ROLE_ASSISTANT)
                .content(answer)
                .build());
    }

    private void saveToolMemories(String sessionId, List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) {
            return;
        }
        for (ToolCallResult result : results) {
            String content = "tool=" + result.getToolName()
                    + ", success=" + result.isSuccess()
                    + ", result=" + truncate(result.isSuccess() ? result.getRawResult() : result.getErrorMessage(),
                    TOOL_MEMORY_LIMIT);
            memory.save(sessionId, MemoryMessage.builder()
                    .role(MemoryMessage.ROLE_TOOL)
                    .content(content)
                    .metadata(toolMemoryMetadata(result))
                    .build());
        }
    }

    private AgentRequest normalizeRequest(AgentRequest request) {
        AgentRequest source = request == null ? AgentRequest.builder().build() : request;
        if (!StringUtils.hasText(source.getUserInput())) {
            throw new IllegalArgumentException("Agent userInput must not be blank");
        }
        if (!StringUtils.hasText(source.getSessionId())) {
            source.setSessionId(UUID.randomUUID().toString());
        }
        if (source.getMetadata() == null) {
            source.setMetadata(new LinkedHashMap<>());
        }
        return source;
    }

    private int resolveMaxIterations(AgentRequest request) {
        int configured = properties.getMaxIterations() > 0 ? properties.getMaxIterations() : 3;
        return request.getMaxIterations() > 0 ? request.getMaxIterations() : configured;
    }

    private boolean isKnowledgeSearchEnabled(AgentRequest request) {
        return properties.getRag().isEnabled() && request.isEnableRag();
    }

    private List<AgentStep> safeSteps(AgentPlannerResult result, AgentContext context) {
        return result.getSteps() == null ? context.getSteps() : result.getSteps();
    }

    private List<ToolCallResult> safeToolResults(AgentPlannerResult result, AgentContext context) {
        return result.getToolResults() == null ? context.getToolResults() : result.getToolResults();
    }

    private List<AgentDocument> safeRetrievedContext(AgentPlannerResult result, AgentContext context) {
        return result.getRetrievedContext() == null ? context.getRetrievedContext() : result.getRetrievedContext();
    }

    private Map<String, Object> toolMemoryMetadata(ToolCallResult result) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", result.getToolName());
        metadata.put("success", result.isSuccess());
        return metadata;
    }

    private void addStep(AgentContext context, AgentActionType actionType, String actionName,
                         String input, String output, long latencyMs, String thoughtSummary) {
        context.getSteps().add(AgentStep.builder()
                .index(context.getSteps().size() + 1)
                .thoughtSummary(thoughtSummary)
                .actionType(actionType.name())
                .actionName(actionName)
                .input(input)
                .output(output)
                .latencyMs(latencyMs)
                .build());
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit)) + "...";
    }
}
