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

package com.nageoffer.ai.ragent.agent.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private boolean enabled = true;

    private int maxIterations = 3;

    private Memory memory = new Memory();

    private Feature rag = new Feature();

    private Feature tools = new Feature();

    private Model planner = new Model();

    private Model answer = new Model();

    @Data
    public static class Memory {

        private int maxMessages = 20;
    }

    @Data
    public static class Feature {

        private boolean enabled = true;
    }

    @Data
    public static class Model {

        private String model;
    }
}
