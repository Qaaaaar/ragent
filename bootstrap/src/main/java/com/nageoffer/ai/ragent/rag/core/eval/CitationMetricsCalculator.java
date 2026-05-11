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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class CitationMetricsCalculator {

    private final CitationExtractor citationExtractor;

    public CitationMetricResult evaluate(EvalCase evalCase,
                                         List<EvalChunk> retrievedChunks,
                                         String actualAnswer,
                                         JudgeResult judgeResult) {
        List<String> requiredIds = safe(evalCase == null ? null : evalCase.getRequiredCitationChunkIds());
        if (requiredIds.isEmpty()) {
            return CitationMetricResult.builder().skipped(true).build();
        }

        List<CitationReference> references = citationExtractor.extract(actualAnswer);
        Set<String> retrievedIds = safe(retrievedChunks).stream()
                .map(EvalChunk::getChunkId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> requiredSet = new LinkedHashSet<>(requiredIds);
        Map<String, List<CitationSupportResult>> supportByChunk = safe(judgeResult == null
                ? null
                : judgeResult.getCitationSupport()).stream()
                .filter(result -> StringUtils.hasText(result.getCitationChunkId()))
                .collect(Collectors.groupingBy(CitationSupportResult::getCitationChunkId));

        List<String> errors = new ArrayList<>();
        Set<String> coveredRequired = new LinkedHashSet<>();
        int correct = 0;
        for (CitationReference reference : references) {
            String chunkId = reference.getChunkId();
            if (!StringUtils.hasText(chunkId)) {
                errors.add("Blank citation id.");
                continue;
            }
            if (!retrievedIds.contains(chunkId)) {
                errors.add("Citation chunk was not retrieved: " + chunkId);
                continue;
            }
            boolean required = requiredSet.contains(chunkId);
            boolean judgeSupported = supportByChunk.getOrDefault(chunkId, List.of()).stream()
                    .anyMatch(result -> result.isSupported() || result.getScore() >= 4);
            if (required || judgeSupported) {
                correct++;
                if (required) {
                    coveredRequired.add(chunkId);
                }
            } else {
                errors.add("Citation chunk does not support the claim: " + chunkId);
            }
        }

        if (references.isEmpty()) {
            errors.add("Answer has no structured citation but citations are required.");
        }

        double precision = references.isEmpty() ? 0D : (double) correct / references.size();
        double recall = requiredSet.isEmpty() ? 0D : (double) coveredRequired.size() / requiredSet.size();
        boolean fullyCorrect = !references.isEmpty()
                && correct == references.size()
                && coveredRequired.size() == requiredSet.size();

        return CitationMetricResult.builder()
                .skipped(false)
                .precision(precision)
                .recall(recall)
                .fullyCorrect(fullyCorrect)
                .citationCount(references.size())
                .correctCitationCount(correct)
                .requiredCitationCount(requiredSet.size())
                .coveredRequiredCitationCount(coveredRequired.size())
                .references(references)
                .errors(errors)
                .build();
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
    }
}
