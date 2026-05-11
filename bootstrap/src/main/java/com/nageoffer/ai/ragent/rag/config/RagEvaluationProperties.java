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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag.evaluation")
public class RagEvaluationProperties {

    /**
     * Judge model id. Blank means using the default LLM routing model.
     */
    private String judgeModel;

    /**
     * Number of retries when judge output is not valid JSON.
     */
    private Integer judgeMaxRetries = 1;

    private Thresholds thresholds = new Thresholds();

    @Data
    public static class Thresholds {

        private Double hitRate = 0.85D;

        private Double mrr = 0.70D;

        private Double recallAtK = 0.75D;

        private Double citationPrecision = 0.90D;

        private Double citationRecall = 0.80D;

        private Double citationAccuracy = 0.85D;

        private Double averageFaithfulness = 4.0D;

        private Double averageRelevancy = 4.0D;

        private Double averageCorrectness = 4.0D;

        private Double hallucinationRate = 0.15D;

        private Double answerCorrectRate = 0.85D;

        private Double fallbackRateLower = 0.05D;

        private Double fallbackRateUpper = 0.15D;
    }
}
