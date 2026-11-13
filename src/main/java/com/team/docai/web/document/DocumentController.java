package com.team.docai.web.document;

import com.team.docai.domain.document.service.DocumentService;
import com.team.docai.web.document.dto.request.CreateCheckRequest;
import com.team.docai.web.document.dto.response.CheckResponse;
import com.team.docai.global.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.*;
@RestController @RequestMapping("/api/checks") @RequiredArgsConstructor
public class DocumentController {
    private final DocumentService service;
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE) public ResponseEntity<ApiResponse<CheckResponse>> create(@AuthenticationPrincipal UUID user,@Valid @ModelAttribute CreateCheckRequest r){return ResponseEntity.status(201).body(ApiResponse.success(201,CheckResponse.from(service.create(user,r.toCommand()))));}
    @GetMapping public ApiResponse<List<CheckResponse>> list(@AuthenticationPrincipal UUID user,@RequestParam(defaultValue="false") boolean archived){return ApiResponse.success(service.list(user,archived).stream().map(CheckResponse::from).toList());}
    @GetMapping("/{id}") public ApiResponse<CheckResponse> get(@AuthenticationPrincipal UUID user,@PathVariable UUID id){return ApiResponse.success(CheckResponse.from(service.get(user,id)));}
    @PostMapping("/{id}/retries") public ApiResponse<CheckResponse> retry(@AuthenticationPrincipal UUID user,@PathVariable UUID id){return ApiResponse.success(CheckResponse.from(service.retry(user,id)));}
    @GetMapping(value="/{id}/pages/{page}",produces=MediaType.IMAGE_PNG_VALUE) public ResponseEntity<byte[]> page(@AuthenticationPrincipal UUID user,@PathVariable UUID id,@PathVariable int page){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.page(user,id,page));}
}
