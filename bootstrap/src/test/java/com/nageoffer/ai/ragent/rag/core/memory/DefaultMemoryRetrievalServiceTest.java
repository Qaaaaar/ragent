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

package com.nageoffer.ai.ragent.rag.core.memory;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultMemoryRetrievalServiceTest {

    @Mock
    private PgMemoryVectorSupport vectorSupport;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private MemoryProperties properties;
    private DefaultMemoryRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        properties = new MemoryProperties();
        properties.setLongTermEnabled(true);
        properties.setRecallEnabled(true);
        properties.setMaxRecall(5);
        retrievalService = new DefaultMemoryRetrievalService(properties, vectorSupport, jdbcTemplate);
    }

    @Test
    void shouldNotRecallWhenDisabled() {
        properties.setRecallEnabled(false);

        String context = retrievalService.retrieveContext("user-1", "query");

        assertEquals("", context);
        verifyNoInteractions(vectorSupport, jdbcTemplate);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldQueryOnlyCurrentUserMemoriesAndActiveOrganizationMemories() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        AtomicReference<Object[]> argsRef = new AtomicReference<>();
        when(vectorSupport.vectorLiteral("query")).thenReturn("[1,0]");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    sqlRef.set(invocation.getArgument(0));
                    argsRef.set(new Object[] {
                            invocation.getArgument(2),
                            invocation.getArgument(3),
                            invocation.getArgument(4),
                            invocation.getArgument(5)
                    });
                    return List.of();
                });

        String context = retrievalService.retrieveContext("user-1", "query");

        assertEquals("", context);
        assertTrue(sqlRef.get().contains("deleted = 0"));
        assertTrue(sqlRef.get().contains("status = 'ACTIVE'"));
        assertTrue(sqlRef.get().contains("(expire_time IS NULL OR expire_time > NOW())"));
        assertTrue(sqlRef.get().contains("(scope_type = 'USER' AND owner_user_id = ?) OR scope_type = 'ORG'"));
        assertArrayEquals(new Object[] {"[1,0]", "[1,0]", "user-1", 5}, argsRef.get());
    }
}
