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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentProperties;
import com.nageoffer.ai.ragent.agent.prompt.AgentPromptBuilder;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class DefaultAgentPlanner implements AgentPlanner {

    private static final List<String> RAG_KEYWORDS = List.of(
            "knowledge", "document", "docs", "reference", "citation", "policy", "manual", "internal",
            "知识库", "文档", "资料", "依据", "引用", "内部", "项目", "规范", "制度", "手册", "流程"
    );
    private static final List<String> TOOL_KEYWORDS = List.of(
            "mcp", "tool", "api", "database", "file", "status", "live", "real-time", "ticket", "weather",
            "sales", "order", "query system", "external system",
            "工具", "外部系统", "实时", "状态", "天气", "工单", "订单", "接口", "数据库", "文件", "销售", "查询系统"
    );

    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final AgentPromptBuilder promptBuilder;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public AgentPlan plan(AgentContext context) {
        String prompt = promptBuilder.buildPlannerPrompt(context, toolRegistry.listTools());
        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(ChatMessage.user(prompt)))
                    .temperature(0.1D)
                    .topP(0.3D)
                    .thinking(false)
                    .build();
            String raw = llmService.chat(request, properties.getPlanner().getModel());
            AgentPlan plan = parsePlan(raw);
            return normalize(plan, context);
        } catch (Exception e) {
            log.warn("Agent planner LLM JSON parse failed, fallback to rules. reason={}", e.getMessage());
            return fallbackPlan(context);
        }
    }

    public AgentPlan parsePlan(String raw) {
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            String json = extractJsonObject(cleaned);
            return objectMapper.readValue(json, AgentPlan.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid planner JSON", e);
        }
    }

    public AgentPlan fallbackPlan(AgentContext context) {
        String userInput = context != null && context.getRequest() != null
                ? context.getRequest().getUserInput()
                : "";
        String normalized = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        boolean needRag = containsAny(normalized, RAG_KEYWORDS);
        boolean needTool = containsAny(normalized, TOOL_KEYWORDS) && toolRegistry.size() > 0;
        List<ToolCallSpec> toolCalls = needTool ? List.of(defaultToolCall(normalized, userInput)) : List.of();
        return AgentPlan.builder()
                .needRag(needRag)
                .needTool(needTool)
                .toolCalls(toolCalls)
                .shouldAnswerDirectly(!needRag && !needTool)
                .reasoningSummary("Fallback planner selected actions from request keywords.")
                .maxIterations(properties.getMaxIterations())
                .build();
    }

    private AgentPlan normalize(AgentPlan plan, AgentContext context) {
        AgentPlan safePlan = plan == null ? fallbackPlan(context) : plan;
        if (safePlan.getToolCalls() == null) {
            safePlan.setToolCalls(new ArrayList<>());
        }
        if (safePlan.getMaxIterations() <= 0) {
            safePlan.setMaxIterations(properties.getMaxIterations());
        }
        if (safePlan.isNeedTool() && toolRegistry.size() == 0) {
            safePlan.setNeedTool(false);
            safePlan.setToolCalls(List.of());
        }
        if (safePlan.isNeedTool() && safePlan.getToolCalls().isEmpty()) {
            safePlan.setToolCalls(List.of(defaultToolCall(
                    context.getRequest().getUserInput().toLowerCase(Locale.ROOT),
                    context.getRequest().getUserInput()
            )));
        }
        if (!safePlan.isNeedRag() && !safePlan.isNeedTool() && !safePlan.isFinish()) {
            safePlan.setShouldAnswerDirectly(true);
        }
        if (!StringUtils.hasText(safePlan.getReasoningSummary())) {
            safePlan.setReasoningSummary("Planner selected the next Agent action.");
        }
        return safePlan;
    }

    private ToolCallSpec defaultToolCall(String normalizedInput, String rawInput) {
        AgentTool selected = selectTool(normalizedInput);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", rawInput);
        arguments.put("input", rawInput);
        return ToolCallSpec.builder()
                .toolName(selected != null ? selected.name() : null)
                .arguments(arguments)
                .build();
    }

    private AgentTool selectTool(String normalizedInput) {
        List<AgentTool> tools = toolRegistry.listTools();
        if (tools.isEmpty()) {
            return null;
        }
        return tools.stream()
                .filter(tool -> containsToolHint(normalizedInput, tool))
                .findFirst()
                .orElse(tools.get(0));
    }

    private boolean containsToolHint(String normalizedInput, AgentTool tool) {
        String name = tool.name() == null ? "" : tool.name().toLowerCase(Locale.ROOT);
        String description = tool.description() == null ? "" : tool.description().toLowerCase(Locale.ROOT);
        return (!name.isBlank() && normalizedInput.contains(name))
                || (!description.isBlank() && TOOL_KEYWORDS.stream().anyMatch(description::contains));
    }

    private boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private String extractJsonObject(String cleaned) {
        if (!StringUtils.hasText(cleaned)) {
            throw new IllegalArgumentException("Planner response is blank");
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Planner response does not contain JSON object");
        }
        return cleaned.substring(start, end + 1);
    }
}
