package com.team.docai.web.document;

import com.team.docai.domain.document.service.CatalogService;
import com.team.docai.web.document.dto.response.CatalogResponse;
import com.team.docai.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/document-types") @RequiredArgsConstructor
public class CatalogController {private final CatalogService service;@GetMapping public ApiResponse<List<CatalogResponse>> list(){return ApiResponse.success(service.list().stream().map(CatalogResponse::from).toList());}}
