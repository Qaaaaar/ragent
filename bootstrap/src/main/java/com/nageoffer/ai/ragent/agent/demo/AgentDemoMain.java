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

package com.nageoffer.ai.ragent.agent.demo;

import com.nageoffer.ai.ragent.RagentApplication;
import com.nageoffer.ai.ragent.agent.core.Agent;
import com.nageoffer.ai.ragent.agent.core.AgentRequest;
import com.nageoffer.ai.ragent.agent.core.AgentResponse;
import com.nageoffer.ai.ragent.agent.core.AgentStep;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class AgentDemoMain {

    private static final String DEFAULT_QUESTION =
            "Use the knowledge base to explain the company HR policy, then call an MCP tool if live weather or ticket data is needed.";

    public static void main(String[] args) {
        String question = args != null && args.length > 0
                ? String.join(" ", args)
                : DEFAULT_QUESTION;
        try (ConfigurableApplicationContext applicationContext = new SpringApplicationBuilder(RagentApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)) {
            Agent agent = applicationContext.getBean(Agent.class);
            AgentResponse response = agent.run(AgentRequest.builder()
                    .sessionId("agent-demo")
                    .userInput(question)
                    .enableRag(true)
                    .enableToolCalling(true)
                    .maxIterations(3)
                    .build());
            System.out.println("Answer:");
            System.out.println(response.getAnswer());
            System.out.println();
            System.out.println("Steps:");
            for (AgentStep step : response.getSteps()) {
                System.out.printf("%d. %s %s %dms %s%n",
                        step.getIndex(),
                        step.getActionType(),
                        step.getActionName(),
                        step.getLatencyMs(),
                        step.getThoughtSummary());
            }
        }
    }
}
