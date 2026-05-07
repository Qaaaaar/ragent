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

package com.nageoffer.ai.ragent.rag.core.router;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class DefaultChatRoutePlanner implements ChatRoutePlanner {

    private static final List<String> IDENTITY_KEYWORDS = List.of(
            "\u4f60\u662f\u8c01",
            "\u4f60\u662f\u8ab0",
            "\u4f60\u53eb\u4ec0\u4e48",
            "\u4f60\u53eb\u4ec0\u9ebc",
            "\u4ecb\u7ecd\u4e00\u4e0b\u81ea\u5df1",
            "\u81ea\u6211\u4ecb\u7ecd",
            "\u4f60\u80fd\u505a\u4ec0\u4e48",
            "\u4f60\u80fd\u505a\u4ec0\u9ebc",
            "\u4f60\u53ef\u4ee5\u505a\u4ec0\u4e48",
            "\u4f60\u53ef\u4ee5\u505a\u4ec0\u9ebc",
            "\u4f60\u662f\u4ec0\u4e48ai",
            "\u5e95\u5c42\u6a21\u578b",
            "\u4ec0\u4e48\u6a21\u578b",
            "\u662f\u4e0d\u662fchatgpt",
            "whoareyou",
            "whatcanyoudo"
    );

    private static final List<String> GREETING_KEYWORDS = List.of(
            "\u4f60\u597d",
            "\u60a8\u597d",
            "\u55e8",
            "\u5728\u5417",
            "\u65e9\u4e0a\u597d",
            "\u4e0b\u5348\u597d",
            "\u665a\u4e0a\u597d",
            "hello",
            "hey"
    );

    private static final List<String> EXACT_GREETING_KEYWORDS = List.of("hi");

    private static final List<String> RETRIEVAL_HINTS = List.of(
            "\u77e5\u8bc6\u5e93",
            "\u77e5\u8b58\u5eab",
            "\u6587\u6863",
            "\u6587\u6a94",
            "\u8d44\u6599",
            "\u8cc7\u6599",
            "\u4f9d\u636e",
            "\u4f9d\u64da",
            "\u5f15\u7528",
            "\u653f\u7b56",
            "\u5236\u5ea6",
            "\u89c4\u8303",
            "\u898f\u7bc4",
            "\u6d41\u7a0b",
            "\u62a5\u9500",
            "\u5831\u92b7",
            "\u8003\u52e4",
            "\u85aa\u8d44",
            "\u85aa\u8cc7",
            "\u53d1\u7968",
            "\u767c\u7968",
            "\u6570\u636e\u5b89\u5168",
            "\u6578\u64da\u5b89\u5168",
            "redis",
            "rocketmq",
            "oa",
            "\u4fdd\u9669",
            "\u67e5\u8be2",
            "\u67e5\u8a62",
            "\u68c0\u7d22",
            "\u6aa2\u7d22",
            "\u5e2e\u6211\u67e5",
            "\u5e6b\u6211\u67e5"
    );

    @Override
    public ChatRoutePlan plan(StreamChatContext context) {
        List<SubQuestionIntent> subIntents = context.getSubIntents();

        if (isSystemOnly(subIntents)) {
            return ChatRoutePlan.directSystem("system-only intent", findSystemPrompt(subIntents));
        }

        if (isGeneralChat(context)) {
            return ChatRoutePlan.directSystem("general chat or assistant identity question", null);
        }

        return ChatRoutePlan.ragRetrieval("requires retrieval or tool context");
    }

    private boolean isSystemOnly(List<SubQuestionIntent> subIntents) {
        return CollUtil.isNotEmpty(subIntents)
                && subIntents.stream().allMatch(si -> CollUtil.isNotEmpty(si.nodeScores())
                && si.nodeScores().size() == 1
                && isSystem(si.nodeScores().get(0)));
    }

    private String findSystemPrompt(List<SubQuestionIntent> subIntents) {
        return subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(NodeScore::getNode)
                .filter(node -> node != null && StrUtil.isNotBlank(node.getPromptTemplate()))
                .map(node -> node.getPromptTemplate())
                .findFirst()
                .orElse(null);
    }

    private boolean isGeneralChat(StreamChatContext context) {
        String original = normalize(context.getQuestion());
        String rewritten = context.getRewriteResult() == null
                ? ""
                : normalize(context.getRewriteResult().rewrittenQuestion());
        return isGeneralChatText(original) || isGeneralChatText(rewritten);
    }

    private boolean isGeneralChatText(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        boolean retrievalLike = RETRIEVAL_HINTS.stream().anyMatch(text::contains);
        boolean identityQuestion = IDENTITY_KEYWORDS.stream().anyMatch(text::contains);
        if (identityQuestion && !retrievalLike && text.length() <= 40) {
            return true;
        }
        boolean greeting = GREETING_KEYWORDS.stream().anyMatch(text::contains)
                || EXACT_GREETING_KEYWORDS.contains(text);
        return greeting && !retrievalLike && text.length() <= 24;
    }

    private boolean isSystem(NodeScore nodeScore) {
        return nodeScore != null
                && nodeScore.getNode() != null
                && nodeScore.getNode().getKind() == IntentKind.SYSTEM;
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\\p{P}]+", "");
    }
}
