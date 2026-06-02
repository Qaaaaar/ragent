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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.core.AgentActionType;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import com.nageoffer.ai.ragent.agent.tool.ToolRegistry;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultAgentPlanner implements AgentPlanner {

    private static final int THOUGHT_LIMIT = 300;
    private static final String NO_KNOWLEDGE_EVIDENCE_ANSWER =
            "\u672a\u68c0\u7d22\u5230\u4e0e\u95ee\u9898\u76f8\u5173\u7684\u6587\u6863\u5185\u5bb9\u3002";
    private static final Pattern REACT_LABEL_PATTERN = Pattern.compile(
            "(?im)^\\s*(Thought|Action Input|Final Answer|Action)\\s*:\\s*");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final AgentPromptBuilder promptBuilder;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public AgentPlannerResult plan(AgentContext context) {
        try {
            validateContext(context);
            int maxIterations = resolveMaxIterations(context.getRequest());
            Set<String> calledToolSignatures = new HashSet<>();

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                context.setIteration(iteration);
                Optional<String> guardedAnswer = answerWhenKnowledgeEvidenceMissing(context);
                if (guardedAnswer.isPresent()) {
                    return finish(context, guardedAnswer.get(), 0L,
                            "Skipped final LLM answer because knowledge_search returned no evidence.");
                }

                long startMs = System.currentTimeMillis();
                String raw = callPlanner(context, false);
                ReActDecision decision;
                try {
                    decision = parseReActDecision(raw);
                } catch (IllegalArgumentException ex) {
                    addStep(context, AgentActionType.REFLECT, "react-parse-error", "",
                            "Planner output could not be parsed: " + ex.getMessage(),
                            System.currentTimeMillis() - startMs,
                            "Planner returned invalid ReAct output.");
                    continue;
                }

                if (decision.hasFinalAnswer()) {
                    String answer = answerWhenKnowledgeEvidenceMissing(context)
                            .orElse(decision.getFinalAnswer());
                    return finish(context, answer, System.currentTimeMillis() - startMs,
                            decision.getThoughtSummary());
                }

                executeAction(context, decision, calledToolSignatures);
            }

            addStep(context, AgentActionType.REFLECT, "max-iterations", "", "Reached maxIterations.",
                    0L, "Stopped iterative ReAct planning at the configured limit.");

            Optional<String> guardedAnswer = answerWhenKnowledgeEvidenceMissing(context);
            if (guardedAnswer.isPresent()) {
                return finish(context, guardedAnswer.get(), 0L,
                        "Skipped final LLM answer because knowledge_search returned no evidence.");
            }

            long startMs = System.currentTimeMillis();
            String raw = callPlanner(context, true);
            ReActDecision decision;
            try {
                decision = parseReActDecision(raw);
            } catch (IllegalArgumentException ex) {
                decision = ReActDecision.finalAnswer(
                        "Finalized after reaching the iteration limit.",
                        fallbackFinalAnswer(raw)
                );
            }
            String answer = decision.hasFinalAnswer()
                    ? decision.getFinalAnswer()
                    : "I could not produce a final answer from the available observations.";
            return finish(context, answer, System.currentTimeMillis() - startMs, decision.getThoughtSummary());
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            log.error("Agent ReAct planner failed, sessionId={}, steps={}, reason={}",
                    context != null && context.getRequest() != null ? context.getRequest().getSessionId() : null,
                    context != null ? context.getSteps().size() : 0,
                    reason, ex);
            return AgentPlannerResult.builder()
                    .answer("")
                    .steps(context != null ? context.getSteps() : List.of())
                    .toolResults(context != null ? context.getToolResults() : List.of())
                    .retrievedContext(context != null ? context.getRetrievedContext() : List.of())
                    .success(false)
                    .errorMessage(reason)
                    .build();
        }
    }

    public ReActDecision parseReActDecision(String raw) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
        Map<String, String> sections = extractSections(cleaned);
        String thought = sanitizeThought(sections.get("thought"));
        String finalAnswer = trimToNull(sections.get("final answer"));
        if (finalAnswer != null) {
            return ReActDecision.finalAnswer(thought, finalAnswer);
        }

        String action = trimToNull(sections.get("action"));
        if (action == null) {
            throw new IllegalArgumentException("Missing Action or Final Answer");
        }
        action = firstLine(action);
        Map<String, Object> arguments = parseActionInput(sections.get("action input"));
        return ReActDecision.action(thought, action, arguments);
    }

    private String callPlanner(AgentContext context, boolean forceFinalAnswer) {
        String prompt = promptBuilder.buildReActPlannerPrompt(context, toolRegistry.listTools(), forceFinalAnswer);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
        return llmService.chat(request, properties.getPlanner().getModel());
    }

    private void executeAction(AgentContext context, ReActDecision decision, Set<String> calledToolSignatures) {
        long startMs = System.currentTimeMillis();
        String toolName = decision.getActionName();
        Map<String, Object> arguments = safeArguments(decision.getArguments());
        if (isKnowledgeSearchTool(toolName)) {
            enrichKnowledgeSearchArguments(context, arguments);
        }
        String input = toJson(arguments);
        ToolCallResult result = null;
        String observation;
        boolean recordToolResult = false;

        if (!StringUtils.hasText(toolName)) {
            observation = "Action is blank.";
        } else if (!isToolCallingEnabled(context.getRequest())) {
            observation = "Tool calling disabled.";
        } else if (isKnowledgeSearchTool(toolName) && !isKnowledgeSearchEnabled(context.getRequest())) {
            observation = "Knowledge search disabled for this request.";
        } else {
            String signature = toolName + ":" + input;
            if (calledToolSignatures.contains(signature)) {
                observation = "Skipped duplicate tool call: " + toolName;
            } else {
                calledToolSignatures.add(signature);
                result = callTool(toolName, arguments);
                recordToolResult = true;
                observation = result.isSuccess() ? result.getRawResult() : result.getErrorMessage();
            }
        }

        if (recordToolResult) {
            context.getToolResults().add(result);
        }
        addStep(context, AgentActionType.MCP_TOOL_CALL, StringUtils.hasText(toolName) ? toolName : "unknown",
                input, observation, System.currentTimeMillis() - startMs, decision.getThoughtSummary());
        log.info("Agent ReAct action observed, sessionId={}, tool={}, recorded={}, latencyMs={}",
                context.getRequest().getSessionId(), toolName, recordToolResult, System.currentTimeMillis() - startMs);
    }

    private ToolCallResult callTool(String toolName, Map<String, Object> arguments) {
        long startMs = System.currentTimeMillis();
        return toolRegistry.findByName(toolName)
                .map(tool -> {
                    try {
                        ToolCallResult result = tool.call(arguments);
                        if (result.getLatencyMs() <= 0) {
                            result.setLatencyMs(System.currentTimeMillis() - startMs);
                        }
                        return result;
                    } catch (Exception ex) {
                        return ToolCallResult.builder()
                                .toolName(toolName)
                                .arguments(arguments)
                                .rawResult("")
                                .success(false)
                                .errorMessage(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                                .latencyMs(System.currentTimeMillis() - startMs)
                                .build();
                    }
                })
                .orElseGet(() -> ToolCallResult.builder()
                        .toolName(toolName)
                        .arguments(arguments)
                        .rawResult("")
                        .success(false)
                        .errorMessage("Tool not found: " + toolName)
                        .latencyMs(System.currentTimeMillis() - startMs)
                        .build());
    }

    private AgentPlannerResult finish(AgentContext context, String answer, long latencyMs, String thoughtSummary) {
        String safeAnswer = answer == null ? "" : answer;
        addStep(context, AgentActionType.LLM_ANSWER, "final-answer", "", safeAnswer, latencyMs,
                StringUtils.hasText(thoughtSummary) ? thoughtSummary : "Generated final answer.");
        return AgentPlannerResult.builder()
                .answer(safeAnswer)
                .steps(context.getSteps())
                .toolResults(context.getToolResults())
                .retrievedContext(context.getRetrievedContext())
                .success(true)
                .build();
    }

    private Optional<String> answerWhenKnowledgeEvidenceMissing(AgentContext context) {
        if (context == null || context.getToolResults() == null || context.getToolResults().isEmpty()) {
            return Optional.empty();
        }
        if (context.getRetrievedContext() != null && !context.getRetrievedContext().isEmpty()) {
            return Optional.empty();
        }

        boolean hasSuccessfulKnowledgeSearch = false;
        boolean hasKnowledgeEvidence = false;
        for (ToolCallResult result : context.getToolResults()) {
            if (!isKnowledgeSearchTool(result.getToolName()) || !result.isSuccess()) {
                continue;
            }
            hasSuccessfulKnowledgeSearch = true;
            if (hasKnowledgeEvidence(result)) {
                hasKnowledgeEvidence = true;
                break;
            }
        }
        return hasSuccessfulKnowledgeSearch && !hasKnowledgeEvidence
                ? Optional.of(NO_KNOWLEDGE_EVIDENCE_ANSWER)
                : Optional.empty();
    }

    private boolean hasKnowledgeEvidence(ToolCallResult result) {
        if (result == null || !result.isSuccess() || !StringUtils.hasText(result.getRawResult())) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(result.getRawResult());
            JsonNode count = root.get("count");
            if (count != null && count.isNumber() && count.asInt() > 0) {
                return true;
            }
            JsonNode chunks = root.get("chunks");
            return chunks != null && chunks.isArray() && !chunks.isEmpty();
        } catch (Exception ex) {
            log.debug("Unable to parse knowledge_search result for evidence guard: {}", ex.getMessage());
            return true;
        }
    }

    private Map<String, String> extractSections(String cleaned) {
        if (!StringUtils.hasText(cleaned)) {
            throw new IllegalArgumentException("Planner response is blank");
        }
        Matcher matcher = REACT_LABEL_PATTERN.matcher(cleaned);
        List<LabelMatch> labels = new ArrayList<>();
        while (matcher.find()) {
            labels.add(new LabelMatch(
                    matcher.group(1).toLowerCase(Locale.ROOT),
                    matcher.start(),
                    matcher.end()
            ));
        }
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Planner response does not contain ReAct labels");
        }

        Map<String, String> sections = new LinkedHashMap<>();
        for (int i = 0; i < labels.size(); i++) {
            LabelMatch label = labels.get(i);
            int end = i + 1 < labels.size() ? labels.get(i + 1).labelStart() : cleaned.length();
            sections.put(label.name(), cleaned.substring(label.contentStart(), end).trim());
        }
        return sections;
    }

    private Map<String, Object> parseActionInput(String rawInput) {
        String value = LLMResponseCleaner.stripMarkdownCodeFence(rawInput == null ? "" : rawInput).trim();
        if (!StringUtils.hasText(value)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = extractJsonObject(value);
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("input", value);
            return fallback;
        }
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Action Input does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private void enrichKnowledgeSearchArguments(AgentContext context, Map<String, Object> arguments) {
        Object query = arguments.get("query");
        if (!(query instanceof String queryText) || !StringUtils.hasText(queryText)) {
            arguments.put("query", context.getRequest().getUserInput());
        }
        Map<String, Object> metadata = context.getRequest().getMetadata();
        copyMetadataArgument(metadata, arguments, "topK");
        copyMetadataArgument(metadata, arguments, "collectionName");
        copyMetadataArgument(metadata, arguments, "metadataFilters");
    }

    private void copyMetadataArgument(Map<String, Object> metadata, Map<String, Object> arguments, String key) {
        if (metadata != null && metadata.containsKey(key) && !arguments.containsKey(key)) {
            arguments.put(key, metadata.get(key));
        }
    }

    private int resolveMaxIterations(AgentRequest request) {
        int configured = properties.getMaxIterations() > 0 ? properties.getMaxIterations() : 3;
        return request.getMaxIterations() > 0 ? request.getMaxIterations() : configured;
    }

    private boolean isToolCallingEnabled(AgentRequest request) {
        return properties.getTools().isEnabled() && request.isEnableToolCalling();
    }

    private boolean isKnowledgeSearchEnabled(AgentRequest request) {
        return properties.getRag().isEnabled() && request.isEnableRag();
    }

    private boolean isKnowledgeSearchTool(String toolName) {
        return AgentToolNames.KNOWLEDGE_SEARCH.equals(toolName);
    }

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        return arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
    }

    private String sanitizeThought(String value) {
        String thought = trimToNull(value);
        if (thought == null) {
            return "Selected the next ReAct step.";
        }
        return truncate(firstLine(thought), THOUGHT_LIMIT);
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstLine(String value) {
        String trimmed = value == null ? "" : value.trim();
        int lineBreak = trimmed.indexOf('\n');
        return lineBreak >= 0 ? trimmed.substring(0, lineBreak).trim() : trimmed;
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit)) + "...";
    }

    private String toJson(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
            return String.valueOf(arguments);
        }
    }

    private String fallbackFinalAnswer(String raw) {
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw == null ? "" : raw).trim();
        if (StringUtils.hasText(cleaned)) {
            return cleaned;
        }
        return "I could not produce a final answer from the available observations.";
    }

    private void validateContext(AgentContext context) {
        if (context == null || context.getRequest() == null) {
            throw new IllegalArgumentException("Agent context and request must not be null");
        }
        if (!StringUtils.hasText(context.getRequest().getUserInput())) {
            throw new IllegalArgumentException("Agent userInput must not be blank");
        }
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

    public static class ReActDecision {

        private final String thoughtSummary;
        private final String actionName;
        private final Map<String, Object> arguments;
        private final String finalAnswer;

        private ReActDecision(String thoughtSummary, String actionName,
                              Map<String, Object> arguments, String finalAnswer) {
            this.thoughtSummary = thoughtSummary;
            this.actionName = actionName;
            this.arguments = arguments;
            this.finalAnswer = finalAnswer;
        }

        static ReActDecision action(String thoughtSummary, String actionName, Map<String, Object> arguments) {
            return new ReActDecision(thoughtSummary, actionName, arguments, null);
        }

        static ReActDecision finalAnswer(String thoughtSummary, String finalAnswer) {
            return new ReActDecision(thoughtSummary, null, new LinkedHashMap<>(), finalAnswer);
        }

        boolean hasFinalAnswer() {
            return finalAnswer != null;
        }

        String getThoughtSummary() {
            return thoughtSummary;
        }

        String getActionName() {
            return actionName;
        }

        Map<String, Object> getArguments() {
            return arguments;
        }

        String getFinalAnswer() {
            return finalAnswer;
        }
    }

    private record LabelMatch(String name, int labelStart, int contentStart) {
    }
}
