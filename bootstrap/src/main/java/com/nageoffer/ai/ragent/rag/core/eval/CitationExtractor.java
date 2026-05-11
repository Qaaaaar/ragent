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
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CitationExtractor {

    private static final Pattern BRACKET_CITATION = Pattern.compile("\\[([^\\[\\]]+)]");
    private static final Pattern SEPARATOR = Pattern.compile("[,，;；\\s]+");

    public List<CitationReference> extract(String answer) {
        if (!StringUtils.hasText(answer)) {
            return List.of();
        }
        List<CitationReference> references = new ArrayList<>();
        Matcher matcher = BRACKET_CITATION.matcher(answer);
        while (matcher.find()) {
            String claim = extractClaim(answer, matcher.start());
            String[] ids = SEPARATOR.split(matcher.group(1).trim());
            for (String id : ids) {
                if (StringUtils.hasText(id)) {
                    references.add(CitationReference.builder()
                            .claim(claim)
                            .chunkId(id.trim())
                            .build());
                }
            }
        }
        return references;
    }

    private String extractClaim(String answer, int citationStart) {
        int start = 0;
        for (int i = citationStart - 1; i >= 0; i--) {
            char ch = answer.charAt(i);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                start = i + 1;
                break;
            }
        }
        String claim = answer.substring(start, citationStart)
                .replaceAll("\\[[^\\[\\]]+]", "")
                .trim();
        return StringUtils.hasText(claim) ? claim : answer.substring(0, citationStart).trim();
    }
}
