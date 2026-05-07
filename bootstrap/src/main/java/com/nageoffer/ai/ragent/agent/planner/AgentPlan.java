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

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlan {

    @JsonAlias("need_rag")
    private boolean needRag;

    @JsonAlias({"need_tool", "needTools"})
    private boolean needTool;

    @Default
    @JsonAlias({"tool_calls", "tools"})
    private List<ToolCallSpec> toolCalls = new ArrayList<>();

    @JsonAlias("should_answer_directly")
    private boolean shouldAnswerDirectly;

    @JsonAlias("reasoning_summary")
    private String reasoningSummary;

    private boolean finish;

    private int maxIterations;
}
