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

package com.nageoffer.ai.ragent.rag.core.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RagEvaluationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LlmAsJudgeService implements RagJudgeService {

    private final LLMService llmService;
    private final ObjectMapper objectMapper;
    private final RagEvaluationProperties properties;

    @Override
    public JudgeResult judge(EvalSample sample) {
        String prompt = buildPrompt(sample);
        int retries = Math.max(0, properties.getJudgeMaxRetries() == null ? 0 : properties.getJudgeMaxRetries());
        RuntimeException lastError = null;
        for (int i = 0; i <= retries; i++) {
            try {
                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(ChatMessage.user(prompt)))
                        .temperature(0D)
                        .topP(0.1D)
                        .thinking(false)
                        .build();
                String raw = llmService.chat(request, properties.getJudgeModel());
                return parse(raw);
            } catch (RuntimeException ex) {
                lastError = ex;
            }
        }
        throw lastError == null ? new IllegalStateException("Judge failed") : lastError;
    }

    JudgeResult parse(String raw) {
        try {
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            String json = extractJsonObject(cleaned);
            return objectMapper.readValue(json, JudgeResult.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid judge JSON", ex);
        }
    }

    private String buildPrompt(EvalSample sample) {
        EvalCase evalCase = sample.getEvalCase();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", evalCase.getQuery());
        payload.put("expectedAnswer", evalCase.getExpectedAnswer());
        payload.put("retrievedChunks", sample.getRetrievedChunks());
        payload.put("actualAnswer", sample.getActualAnswer());
        payload.put("requiredCitationChunkIds", evalCase.getRequiredCitationChunkIds());
        payload.put("noAnswerExpected", evalCase.isNoAnswerExpected());
        return """
                你是一个专业的 RAG 系统评估员。只能根据输入中的 query、expectedAnswer、retrievedChunks 和 actualAnswer 评分，禁止使用外部知识。

                评分标准统一为 1~5 分：
                5：完全正确 / 完全支持
                4：基本正确，只有轻微缺失或措辞差异
                3：部分正确，有明显遗漏或额外推断
                2：大部分不正确，只有少量相关内容
                1：严重错误、编造、与证据矛盾

                请评估四个维度：
                - faithfulness：答案是否忠实于 retrievedChunks，有没有编造 chunk 中没有的信息。
                - answerRelevancy：答案是否回答了 query。
                - answerCorrectness：答案是否与 expectedAnswer 语义一致。
                - citationSupport：逐条判断答案中每个 [chunkId] 引用是否支持其前面的 claim。

                对无答案样本：如果没有证据且系统明确兜底，answerCorrectness 可以给高分；如果强行回答事实，faithfulness 应给低分。

                只返回 JSON，不要返回 Markdown 或解释性正文。JSON 结构必须是：
                {
                  "faithfulness": {"score": 1-5, "reason": "..."},
                  "answerRelevancy": {"score": 1-5, "reason": "..."},
                  "answerCorrectness": {"score": 1-5, "reason": "..."},
                  "citationSupport": [
                    {"claim": "...", "citationChunkId": "...", "score": 1-5, "supported": true|false, "reason": "..."}
                  ],
                  "overallPass": true|false
                }

                输入：
                %s
                """.formatted(toJson(payload));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Judge prompt payload serialization failed", ex);
        }
    }

    private String extractJsonObject(String cleaned) {
        if (cleaned == null) {
            throw new IllegalArgumentException("Judge response is blank");
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Judge response does not contain JSON object");
        }
        return cleaned.substring(start, end + 1);
    }
}
