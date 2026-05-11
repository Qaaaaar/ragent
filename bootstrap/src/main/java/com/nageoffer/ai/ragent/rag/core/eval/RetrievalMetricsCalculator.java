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

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Component
public class RetrievalMetricsCalculator {

    public RetrievalMetricResult evaluate(EvalCase evalCase, List<EvalChunk> retrievedChunks) {
        List<String> relevantIds = evalCase == null ? List.of() : safe(evalCase.getRelevantChunkIds());
        List<String> retrievedIds = safe(retrievedChunks).stream()
                .map(EvalChunk::getChunkId)
                .filter(Objects::nonNull)
                .toList();
        boolean skipped = evalCase != null && evalCase.isNoAnswerExpected() && relevantIds.isEmpty();
        if (skipped || relevantIds.isEmpty()) {
            return RetrievalMetricResult.builder()
                    .skipped(skipped)
                    .retrievedCount(retrievedIds.size())
                    .relevantCount(relevantIds.size())
                    .build();
        }

        Set<String> relevantSet = new LinkedHashSet<>(relevantIds);
        Set<String> matched = new LinkedHashSet<>();
        double reciprocalRank = 0D;
        for (int i = 0; i < retrievedIds.size(); i++) {
            String retrievedId = retrievedIds.get(i);
            if (relevantSet.contains(retrievedId)) {
                matched.add(retrievedId);
                if (reciprocalRank == 0D) {
                    reciprocalRank = 1D / (i + 1);
                }
            }
        }

        return RetrievalMetricResult.builder()
                .skipped(false)
                .hit(!matched.isEmpty())
                .reciprocalRank(reciprocalRank)
                .recallAtK((double) matched.size() / relevantSet.size())
                .retrievedCount(retrievedIds.size())
                .relevantCount(relevantSet.size())
                .matchedRelevantCount(matched.size())
                .build();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
