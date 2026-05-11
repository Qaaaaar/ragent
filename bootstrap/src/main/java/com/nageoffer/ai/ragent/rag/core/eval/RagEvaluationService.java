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

import com.nageoffer.ai.ragent.rag.config.RagEvaluationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final RetrievalMetricsCalculator retrievalMetricsCalculator;
    private final CitationMetricsCalculator citationMetricsCalculator;
    private final RagJudgeService judgeService;
    private final RagEvaluationProperties properties;

    public EvalReport evaluate(List<EvalSample> samples) {
        List<EvalCaseResult> caseResults = safe(samples).stream()
                .map(this::evaluateOne)
                .toList();
        Map<String, Double> metrics = aggregate(caseResults);
        return EvalReport.builder()
                .caseResults(caseResults)
                .metrics(metrics)
                .failedThresholds(failedThresholds(metrics))
                .build();
    }

    private EvalCaseResult evaluateOne(EvalSample sample) {
        RetrievalMetricResult retrieval = retrievalMetricsCalculator.evaluate(sample.getEvalCase(), sample.getRetrievedChunks());
        JudgeResult judge = judgeService.judge(sample);
        CitationMetricResult citation = citationMetricsCalculator.evaluate(
                sample.getEvalCase(),
                sample.getRetrievedChunks(),
                sample.getActualAnswer(),
                judge
        );
        return EvalCaseResult.builder()
                .sample(sample)
                .retrieval(retrieval)
                .judge(judge)
                .citation(citation)
                .build();
    }

    private Map<String, Double> aggregate(List<EvalCaseResult> results) {
        List<EvalCaseResult> retrievalResults = results.stream()
                .filter(result -> result.getRetrieval() != null && !result.getRetrieval().isSkipped())
                .toList();
        List<EvalCaseResult> citationResults = results.stream()
                .filter(result -> result.getCitation() != null && !result.getCitation().isSkipped())
                .toList();
        Map<String, Double> metrics = new LinkedHashMap<>();
        if (!retrievalResults.isEmpty()) {
            metrics.put("hitRate", average(retrievalResults, result -> result.getRetrieval().isHit() ? 1D : 0D));
            metrics.put("mrr", average(retrievalResults, result -> result.getRetrieval().getReciprocalRank()));
            metrics.put("recallAtK", average(retrievalResults, result -> result.getRetrieval().getRecallAtK()));
        }
        metrics.put("averageFaithfulness", average(results, result -> score(result.getJudge().getFaithfulness())));
        metrics.put("averageRelevancy", average(results, result -> score(result.getJudge().getAnswerRelevancy())));
        metrics.put("averageCorrectness", average(results, result -> score(result.getJudge().getAnswerCorrectness())));
        metrics.put("hallucinationRate", average(results, result -> score(result.getJudge().getFaithfulness()) <= 2 ? 1D : 0D));
        metrics.put("answerCorrectRate", average(results, result -> score(result.getJudge().getAnswerCorrectness()) >= 4 ? 1D : 0D));
        metrics.put("fallbackRate", average(results, result -> isFallback(result.getSample().getActualAnswer()) ? 1D : 0D));
        if (!citationResults.isEmpty()) {
            metrics.put("citationPrecision", average(citationResults, result -> result.getCitation().getPrecision()));
            metrics.put("citationRecall", average(citationResults, result -> result.getCitation().getRecall()));
            metrics.put("citationAccuracy", average(citationResults, result -> result.getCitation().isFullyCorrect() ? 1D : 0D));
        }
        return metrics;
    }

    private List<String> failedThresholds(Map<String, Double> metrics) {
        RagEvaluationProperties.Thresholds thresholds = properties.getThresholds();
        List<String> failed = new ArrayList<>();
        min(metrics, failed, "hitRate", thresholds.getHitRate());
        min(metrics, failed, "mrr", thresholds.getMrr());
        min(metrics, failed, "recallAtK", thresholds.getRecallAtK());
        min(metrics, failed, "averageFaithfulness", thresholds.getAverageFaithfulness());
        min(metrics, failed, "averageRelevancy", thresholds.getAverageRelevancy());
        min(metrics, failed, "averageCorrectness", thresholds.getAverageCorrectness());
        min(metrics, failed, "answerCorrectRate", thresholds.getAnswerCorrectRate());
        min(metrics, failed, "citationPrecision", thresholds.getCitationPrecision());
        min(metrics, failed, "citationRecall", thresholds.getCitationRecall());
        min(metrics, failed, "citationAccuracy", thresholds.getCitationAccuracy());
        max(metrics, failed, "hallucinationRate", thresholds.getHallucinationRate());
        if (metrics.containsKey("fallbackRate")) {
            double fallbackRate = metrics.get("fallbackRate");
            if (fallbackRate < thresholds.getFallbackRateLower() || fallbackRate > thresholds.getFallbackRateUpper()) {
                failed.add("fallbackRate=" + fallbackRate + " outside ["
                        + thresholds.getFallbackRateLower() + ", " + thresholds.getFallbackRateUpper() + "]");
            }
        }
        return failed;
    }

    private void min(Map<String, Double> metrics, List<String> failed, String key, Double threshold) {
        if (threshold != null && metrics.containsKey(key) && metrics.get(key) < threshold) {
            failed.add(key + "=" + metrics.get(key) + " < " + threshold);
        }
    }

    private void max(Map<String, Double> metrics, List<String> failed, String key, Double threshold) {
        if (threshold != null && metrics.containsKey(key) && metrics.get(key) > threshold) {
            failed.add(key + "=" + metrics.get(key) + " > " + threshold);
        }
    }

    private double average(List<EvalCaseResult> results, ToDoubleFunction<EvalCaseResult> mapper) {
        return results == null || results.isEmpty()
                ? 0D
                : results.stream().mapToDouble(mapper).average().orElse(0D);
    }

    private int score(ScoreResult scoreResult) {
        return scoreResult == null ? 0 : scoreResult.getScore();
    }

    private boolean isFallback(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        return answer.contains("抱歉")
                || answer.contains("找不到")
                || answer.contains("没有找到")
                || answer.contains("未检索到")
                || answer.toLowerCase().contains("not found");
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
