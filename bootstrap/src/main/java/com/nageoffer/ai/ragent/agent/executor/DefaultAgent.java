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

import com.nageoffer.ai.ragent.agent.core.Agent;
import com.nageoffer.ai.ragent.agent.core.AgentActionType;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import com.nageoffer.ai.ragent.agent.memory.AgentMemory;
import com.nageoffer.ai.ragent.agent.memory.MemoryMessage;
import com.nageoffer.ai.ragent.agent.planner.AgentPlan;
import com.nageoffer.ai.ragent.agent.planner.AgentPlanner;
import com.nageoffer.ai.ragent.agent.planner.ToolCallSpec;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import com.nageoffer.ai.ragent.agent.tool.ToolRegistry;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final ToolRegistry toolRegistry;
    private final AgentPromptBuilder promptBuilder;
    private final LLMService llmService;

    @Override
    @RagTraceNode(name = "agent-run", type = "AGENT")
    public AgentResponse run(AgentRequest request) {
        AgentRequest actualRequest = normalizeRequest(request);
        AgentContext context = AgentContext.builder()
                .request(actualRequest)
                .memoryMessages(memory.load(actualRequest.getSessionId(), properties.getMemory().getMaxMessages()))
                .build();

        boolean toolsEnabled = properties.getTools().isEnabled() && actualRequest.isEnableToolCalling();
        int maxIterations = resolveMaxIterations(actualRequest);
        Set<String> calledToolSignatures = new HashSet<>();
        boolean stoppedByMaxIterations = false;

        log.info("Agent run started, sessionId={}, toolsEnabled={}, knowledgeSearchEnabled={}, maxIterations={}",
                actualRequest.getSessionId(), toolsEnabled, isKnowledgeSearchEnabled(actualRequest), maxIterations);

        try {
            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                context.setIteration(iteration);
                AgentPlan plan = plan(context);
                if (plan.isFinish()) {
                    addStep(context, AgentActionType.FINISH, "finish", "", "Planner requested finish.", 0L,
                            "Planner indicated the task can finish.");
                    break;
                }
                if (plan.isShouldAnswerDirectly()) {
                    addStep(context, AgentActionType.REFLECT, "direct-answer", "", "No external context required.", 0L,
                            "Planner selected direct answer.");
                    break;
                }

                boolean didWork = false;
                if (plan.isNeedTool()) {
                    if (toolsEnabled) {
                        didWork = callTools(context, plan.getToolCalls(), calledToolSignatures) || didWork;
                    } else {
                        addStep(context, AgentActionType.MCP_TOOL_CALL, "mcp", "", "Tool calling disabled.", 0L,
                                "Skipped tools because tool calling is disabled.");
                    }
                }

                if (didWork) {
                    addStep(context, AgentActionType.REFLECT, "context-check", "", "Context gathered.", 0L,
                            "Collected external context for the final answer.");
                }
                if (!didWork) {
                    break;
                }
                if (iteration == maxIterations) {
                    stoppedByMaxIterations = true;
                }
            }

            if (stoppedByMaxIterations) {
                addStep(context, AgentActionType.REFLECT, "max-iterations", "", "Reached maxIterations.", 0L,
                        "Stopped iterative planning at the configured limit.");
            }

            String answer = answer(context);
            saveMemory(actualRequest, answer);
            log.info("Agent run finished, sessionId={}, legacyDocs={}, toolResults={}, steps={}, success=true",
                    actualRequest.getSessionId(),
                    context.getRetrievedContext().size(),
                    context.getToolResults().size(),
                    context.getSteps().size());
            return AgentResponse.builder()
                    .answer(answer)
                    .steps(context.getSteps())
                    .toolResults(context.getToolResults())
                    .retrievedContext(context.getRetrievedContext())
                    .success(true)
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
                    .errorMessage(reason)
                    .build();
        }
    }

    private AgentPlan plan(AgentContext context) {
        long startMs = System.currentTimeMillis();
        AgentPlan plan = planner.plan(context);
        adaptLegacyRagPlan(plan, context);
        context.setCurrentPlan(plan);
        addStep(context, AgentActionType.PLAN, "planner", "", summarizePlan(plan),
                System.currentTimeMillis() - startMs, plan.getReasoningSummary());
        return plan;
    }

    private boolean callTools(AgentContext context, List<ToolCallSpec> toolCalls, Set<String> calledToolSignatures) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return false;
        }
        boolean didWork = false;
        for (ToolCallSpec callSpec : toolCalls) {
            String toolName = callSpec.getToolName();
            Map<String, Object> arguments = safeArguments(callSpec.getArguments());
            if (isKnowledgeSearchTool(toolName) && !isKnowledgeSearchEnabled(context.getRequest())) {
                addStep(context, AgentActionType.MCP_TOOL_CALL, toolName, arguments.toString(),
                        "Knowledge search disabled for this request.", 0L,
                        "Skipped knowledge_search because it is disabled.");
                continue;
            }
            String signature = toolName + ":" + arguments;
            if (calledToolSignatures.contains(signature)) {
                continue;
            }
            calledToolSignatures.add(signature);
            didWork = true;
            ToolCallResult result = callTool(toolName, arguments);
            context.getToolResults().add(result);
            saveToolMemory(context.getRequest().getSessionId(), result);
            addStep(context, AgentActionType.MCP_TOOL_CALL, toolName, arguments.toString(),
                    result.isSuccess() ? result.getRawResult() : result.getErrorMessage(),
                    result.getLatencyMs(),
                    result.isSuccess() ? "Called MCP tool." : "MCP tool call failed.");
            log.info("Agent MCP tool call completed, sessionId={}, tool={}, success={}, latencyMs={}",
                    context.getRequest().getSessionId(), toolName, result.isSuccess(), result.getLatencyMs());
        }
        return didWork;
    }

    private ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        long startMs = System.currentTimeMillis();
        return toolRegistry.findByName(toolName)
                .map(tool -> tool.call(arguments))
                .orElseGet(() -> ToolCallResult.builder()
                        .toolName(toolName)
                        .arguments(arguments)
                        .rawResult("")
                        .success(false)
                        .errorMessage("Tool not found: " + toolName)
                        .latencyMs(System.currentTimeMillis() - startMs)
                        .build());
    }

    private String answer(AgentContext context) {
        long startMs = System.currentTimeMillis();
        String prompt = promptBuilder.buildFinalAnswerPrompt(context);
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.8D)
                .thinking(context.getRequest().isDeepThinking())
                .build();
        String answer = llmService.chat(chatRequest, properties.getAnswer().getModel());
        addStep(context, AgentActionType.LLM_ANSWER, "final-answer", "", answer,
                System.currentTimeMillis() - startMs, "Generated final answer.");
        return answer;
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

    private void saveToolMemory(String sessionId, ToolCallResult result) {
        String content = "tool=" + result.getToolName()
                + ", success=" + result.isSuccess()
                + ", result=" + truncate(result.isSuccess() ? result.getRawResult() : result.getErrorMessage(), TOOL_MEMORY_LIMIT);
        memory.save(sessionId, MemoryMessage.builder()
                .role(MemoryMessage.ROLE_TOOL)
                .content(content)
                .metadata(Map.of("toolName", result.getToolName(), "success", result.isSuccess()))
                .build());
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

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        return arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }

    private void adaptLegacyRagPlan(AgentPlan plan, AgentContext context) {
        if (plan == null || !plan.isNeedRag()) {
            return;
        }
        if (plan.getToolCalls() == null) {
            plan.setToolCalls(new ArrayList<>());
        } else {
            plan.setToolCalls(new ArrayList<>(plan.getToolCalls()));
        }
        if (plan.getToolCalls().stream().noneMatch(call -> isKnowledgeSearchTool(call.getToolName()))) {
            plan.getToolCalls().add(knowledgeSearchCall(context));
        }
        plan.setNeedTool(true);
        plan.setNeedRag(false);
    }

    private ToolCallSpec knowledgeSearchCall(AgentContext context) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", context.getRequest().getUserInput());
        Map<String, Object> metadata = context.getRequest().getMetadata();
        copyMetadataArgument(metadata, arguments, "topK");
        copyMetadataArgument(metadata, arguments, "collectionName");
        copyMetadataArgument(metadata, arguments, "metadataFilters");
        return ToolCallSpec.builder()
                .toolName(AgentToolNames.KNOWLEDGE_SEARCH)
                .arguments(arguments)
                .build();
    }

    private void copyMetadataArgument(Map<String, Object> metadata, Map<String, Object> arguments, String key) {
        if (metadata != null && metadata.containsKey(key)) {
            arguments.put(key, metadata.get(key));
        }
    }

    private boolean isKnowledgeSearchEnabled(AgentRequest request) {
        return properties.getRag().isEnabled() && request.isEnableRag();
    }

    private boolean isKnowledgeSearchTool(String toolName) {
        return AgentToolNames.KNOWLEDGE_SEARCH.equals(toolName);
    }

    private String summarizePlan(AgentPlan plan) {
        List<String> parts = new ArrayList<>();
        if (plan.isShouldAnswerDirectly()) {
            parts.add("direct");
        }
        if (plan.isNeedTool()) {
            parts.add("tools=" + (plan.getToolCalls() == null ? 0 : plan.getToolCalls().size()));
        }
        if (plan.isFinish()) {
            parts.add("finish");
        }
        return String.join(",", parts);
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
