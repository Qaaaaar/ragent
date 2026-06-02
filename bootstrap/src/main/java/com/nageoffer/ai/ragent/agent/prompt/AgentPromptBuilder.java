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

package com.nageoffer.ai.ragent.agent.prompt;

import com.nageoffer.ai.ragent.agent.core.AgentContext;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import com.nageoffer.ai.ragent.agent.memory.MemoryMessage;
import com.nageoffer.ai.ragent.agent.rag.AgentDocument;
import com.nageoffer.ai.ragent.agent.tool.AgentTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolNames;
import com.nageoffer.ai.ragent.agent.tool.ToolCallResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "agent.enabled", havingValue = "true", matchIfMissing = true)
public class AgentPromptBuilder {

    private static final int MEMORY_LIMIT = 500;
    private static final int DOCUMENT_LIMIT = 1600;
    private static final int TOOL_RESULT_LIMIT = 1200;
    private static final int STEP_OUTPUT_LIMIT = 1200;

    public String buildReActPlannerPrompt(AgentContext context, List<AgentTool> tools, boolean forceFinalAnswer) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an Agent that must solve the user request with the ReAct loop.\n");
        prompt.append("Use this sequence: Question -> Thought -> Action -> Observation -> ... -> Final Answer.\n");
        prompt.append("Return only one next step. Do not wrap the response in markdown.\n\n");
        prompt.append("Allowed response formats:\n");
        if (!forceFinalAnswer) {
            prompt.append("Thought: <short safe summary>\n");
            prompt.append("Action: <one available tool name>\n");
            prompt.append("Action Input: <JSON object>\n\n");
            prompt.append("or\n\n");
        }
        prompt.append("Thought: <short safe summary>\n");
        prompt.append("Final Answer: <answer to the user>\n\n");
        prompt.append("Rules:\n");
        prompt.append("- Thought must be a short safe summary, not detailed hidden reasoning.\n");
        prompt.append("- Use only tools listed under Available tools. Never invent a tool name.\n");
        prompt.append("- Action Input must be a valid JSON object.\n");
        prompt.append("- Use ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" for broad knowledge-base search, internal material, policies, manuals, or project-specific facts.\n");
        prompt.append("- Use ").append(AgentToolNames.DOCUMENT_SUMMARY).append(" when the user asks to summarize a known or named document.\n");
        prompt.append("- Use ").append(AgentToolNames.CITATION_LOCATE).append(" when the user asks for a citation, quote location, source, docId, chunkId, or evidence position.\n");
        prompt.append("- Use ").append(AgentToolNames.CONVERSATION_HISTORY).append(" when the user asks about previous chats, historical sessions, or past messages.\n");
        prompt.append("- Use other MCP tools for external systems, live status, files, APIs, tickets, weather, sales, or database capabilities.\n");
        prompt.append("- If the answer needs both document evidence and business output, call one tool per step and continue after each Observation.\n");
        prompt.append("- Treat ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" results as the only grounding evidence for knowledge questions.\n");
        prompt.append("- Do not use general model knowledge to supplement missing knowledge-base evidence.\n");
        prompt.append("- If a tool failed, mention the failure only when it matters to the final answer.\n");
        if (forceFinalAnswer) {
            prompt.append("- You must return Final Answer now. Do not call any Action.\n");
        }
        prompt.append("\nQuestion:\n").append(context.getRequest().getUserInput()).append("\n\n");
        prompt.append("Recent memory:\n").append(formatMemory(context.getMemoryMessages())).append("\n\n");
        prompt.append("ReAct trace so far:\n").append(formatReActTrace(context.getSteps())).append("\n\n");
        prompt.append("Available tools:\n").append(formatTools(tools));
        return prompt.toString();
    }

    public String buildPlannerPrompt(AgentContext context, List<AgentTool> tools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an Agent planner. Decide what the Agent should do next.\n");
        prompt.append("Return only a JSON object. Do not answer the user.\n\n");
        prompt.append("JSON schema:\n");
        prompt.append("{\n");
        prompt.append("  \"needRag\": false,\n");
        prompt.append("  \"needTool\": true|false,\n");
        prompt.append("  \"toolCalls\": [{\"toolName\":\"name\", \"arguments\": {}}],\n");
        prompt.append("  \"shouldAnswerDirectly\": true|false,\n");
        prompt.append("  \"reasoningSummary\": \"short safe summary\",\n");
        prompt.append("  \"finish\": true|false,\n");
        prompt.append("  \"maxIterations\": 3\n");
        prompt.append("}\n\n");
        prompt.append("Rules:\n");
        prompt.append("- All external capabilities are tools. Do not use a separate RAG path.\n");
        prompt.append("- Use ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" for broad knowledge-base search, internal material, policies, manuals, or project-specific facts.\n");
        prompt.append("- Use ").append(AgentToolNames.DOCUMENT_SUMMARY).append(" when the user asks to summarize a known or named document.\n");
        prompt.append("- Use ").append(AgentToolNames.CITATION_LOCATE).append(" when the user asks for a citation, quote location, source, docId, chunkId, or evidence position.\n");
        prompt.append("- Use ").append(AgentToolNames.CONVERSATION_HISTORY).append(" when the user asks about previous chats, historical sessions, or past messages.\n");
        prompt.append("- Use other MCP tools for external systems, live status, files, APIs, tickets, weather, sales, or database capabilities.\n");
        prompt.append("- Use multiple tools when the answer needs both document evidence and business system output.\n");
        prompt.append("- shouldAnswerDirectly is true only for simple chat or general explanation.\n");
        prompt.append("- Keep needRag false; knowledge retrieval must appear as a ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" tool call.\n");
        prompt.append("- reasoningSummary must be brief and must not reveal hidden reasoning.\n\n");
        prompt.append("User input:\n").append(context.getRequest().getUserInput()).append("\n\n");
        prompt.append("Recent memory:\n").append(formatMemory(context.getMemoryMessages())).append("\n\n");
        prompt.append("Already retrieved documents: ").append(context.getRetrievedContext().size()).append("\n");
        prompt.append("Already called tools: ").append(context.getToolResults().size()).append("\n\n");
        prompt.append("Available tools:\n").append(formatTools(tools));
        return prompt.toString();
    }

    public String buildToolSelectionPrompt(String userInput, List<AgentTool> tools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Select MCP tools for the user request and return JSON only.\n");
        prompt.append("User input:\n").append(userInput).append("\n\n");
        prompt.append("Available tools:\n").append(formatTools(tools));
        return prompt.toString();
    }

    public String buildFinalAnswerPrompt(AgentContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are the final answer generator for a personal knowledge-base Agent system.\n");
        prompt.append("Answer directly in the user's language.\n\n");
        prompt.append("Requirements:\n");
        prompt.append("- Use the user question, memory, legacy retrieved context, and MCP tool results below.\n");
        prompt.append("- Treat ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" results as the only grounding evidence for knowledge questions.\n");
        prompt.append("- Do not use general model knowledge, external knowledge, common sense, or framework knowledge to supplement missing knowledge-base evidence.\n");
        prompt.append("- If ").append(AgentToolNames.KNOWLEDGE_SEARCH).append(" returns count=0 or empty chunks, answer exactly: 未检索到与问题相关的文档内容。\n");
        prompt.append("- If knowledge evidence is present but insufficient for a key point, state only that the current evidence does not cover that point; do not add a generic fallback answer.\n");
        prompt.append("- Do not invent tool results.\n");
        prompt.append("- If a knowledge tool call failed, mention the failure reason and ask the user to retry or check the knowledge-base binding; do not provide a general-knowledge substitute.\n");
        prompt.append("- If a non-knowledge tool call failed, mention the failure reason and provide only alternatives supported by memory or successful tool results.\n");
        prompt.append("- Keep the answer focused on the user's question.\n\n");
        prompt.append("User question:\n").append(context.getRequest().getUserInput()).append("\n\n");
        prompt.append("Memory summary:\n").append(formatMemory(context.getMemoryMessages())).append("\n\n");
        prompt.append("Legacy retrieved context:\n").append(formatDocuments(context.getRetrievedContext())).append("\n\n");
        prompt.append("MCP tool results:\n").append(formatToolResults(context.getToolResults())).append("\n");
        return prompt.toString();
    }

    private String formatMemory(List<MemoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (MemoryMessage message : messages) {
            builder.append("- ")
                    .append(message.getRole())
                    .append(": ")
                    .append(truncate(message.getContent(), MEMORY_LIMIT))
                    .append("\n");
        }
        return builder.toString().trim();
    }

    private String formatDocuments(List<AgentDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            AgentDocument document = documents.get(i);
            builder.append("[doc-").append(i + 1).append("]\n");
            builder.append("id: ").append(document.getId()).append("\n");
            builder.append("source: ").append(document.getSource()).append("\n");
            builder.append("score: ").append(document.getScore()).append("\n");
            builder.append("content: ").append(truncate(document.getContent(), DOCUMENT_LIMIT)).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String formatToolResults(List<ToolCallResult> results) {
        if (results == null || results.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolCallResult result : results) {
            builder.append("- tool: ").append(result.getToolName()).append("\n");
            builder.append("  success: ").append(result.isSuccess()).append("\n");
            builder.append("  arguments: ").append(result.getArguments()).append("\n");
            if (result.isSuccess()) {
                builder.append("  result: ").append(truncate(result.getRawResult(), TOOL_RESULT_LIMIT)).append("\n");
            } else {
                builder.append("  error: ").append(truncate(result.getErrorMessage(), TOOL_RESULT_LIMIT)).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private String formatReActTrace(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : steps) {
            if (step.getThoughtSummary() != null && !step.getThoughtSummary().isBlank()) {
                builder.append("Thought: ")
                        .append(truncate(step.getThoughtSummary(), MEMORY_LIMIT))
                        .append("\n");
            }
            boolean toolStep = "MCP_TOOL_CALL".equals(step.getActionType());
            if (toolStep && step.getActionName() != null && !step.getActionName().isBlank()
                    && !"final-answer".equals(step.getActionName())) {
                builder.append("Action: ").append(step.getActionName()).append("\n");
            }
            if (toolStep && step.getInput() != null && !step.getInput().isBlank()) {
                builder.append("Action Input: ").append(truncate(step.getInput(), TOOL_RESULT_LIMIT)).append("\n");
            }
            if (step.getOutput() != null && !step.getOutput().isBlank()) {
                builder.append("Observation: ")
                        .append(truncate(step.getOutput(), STEP_OUTPUT_LIMIT))
                        .append("\n");
            }
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private String formatTools(List<AgentTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentTool tool : tools) {
            builder.append("- name: ").append(tool.name()).append("\n");
            builder.append("  description: ").append(tool.description()).append("\n");
            Map<String, Object> schema = tool.schema();
            builder.append("  schema: ").append(schema == null ? "{}" : schema).append("\n");
        }
        return builder.toString().trim();
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
