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

package com.nageoffer.ai.ragent.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryCreateRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryPageRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryReviewRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryStatusRequest;
import com.nageoffer.ai.ragent.rag.controller.request.RagMemoryUpdateRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.RagMemoryVO;
import com.nageoffer.ai.ragent.rag.service.RagMemoryAdminService;
import com.nageoffer.ai.ragent.rag.service.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RagMemoryController {

    private final RagMemoryAdminService ragMemoryAdminService;
    private final UserMemoryService userMemoryService;

    @GetMapping("/memories")
    public Result<IPage<RagMemoryVO>> pageMemories(RagMemoryPageRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(ragMemoryAdminService.pageQuery(requestParam));
    }

    @PostMapping("/memories")
    public Result<String> createMemory(@RequestBody RagMemoryCreateRequest requestParam) {
        StpUtil.checkRole("admin");
        return Results.success(ragMemoryAdminService.create(requestParam));
    }

    @PutMapping("/memories/{id}")
    public Result<Void> updateMemory(@PathVariable String id,
                                     @RequestBody RagMemoryUpdateRequest requestParam) {
        StpUtil.checkRole("admin");
        ragMemoryAdminService.update(id, requestParam);
        return Results.success();
    }

    @DeleteMapping("/memories/{id}")
    public Result<Void> deleteMemory(@PathVariable String id) {
        StpUtil.checkRole("admin");
        ragMemoryAdminService.delete(id);
        return Results.success();
    }

    @PutMapping("/memories/{id}/status")
    public Result<Void> updateMemoryStatus(@PathVariable String id,
                                           @RequestBody RagMemoryStatusRequest requestParam) {
        StpUtil.checkRole("admin");
        ragMemoryAdminService.updateStatus(id, requestParam);
        return Results.success();
    }

    @PostMapping("/memories/{id}/review")
    public Result<Void> reviewMemory(@PathVariable String id,
                                     @RequestBody RagMemoryReviewRequest requestParam) {
        StpUtil.checkRole("admin");
        ragMemoryAdminService.review(id, requestParam);
        return Results.success();
    }

    @GetMapping("/user/memories")
    public Result<IPage<RagMemoryVO>> pageUserMemories(RagMemoryPageRequest requestParam) {
        return Results.success(userMemoryService.pageQuery(requestParam));
    }

    @PostMapping("/user/memories")
    public Result<String> createUserMemory(@RequestBody RagMemoryCreateRequest requestParam) {
        return Results.success(userMemoryService.create(requestParam));
    }

    @PutMapping("/user/memories/{id}")
    public Result<Void> updateUserMemory(@PathVariable String id,
                                         @RequestBody RagMemoryUpdateRequest requestParam) {
        userMemoryService.update(id, requestParam);
        return Results.success();
    }

    @DeleteMapping("/user/memories/{id}")
    public Result<Void> deleteUserMemory(@PathVariable String id) {
        userMemoryService.delete(id);
        return Results.success();
    }
}
