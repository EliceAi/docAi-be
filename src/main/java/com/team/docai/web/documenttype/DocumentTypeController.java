package com.team.docai.web.documenttype;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.web.documenttype.dto.response.DocumentTypesResponse;
import com.team.docai.global.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/document-types") @RequiredArgsConstructor
public class DocumentTypeController {
 private final DocumentTypeService service;
 @GetMapping public ApiResponse<DocumentTypesResponse> list(@RequestParam(required=false) String category,@RequestParam(required=false) String keyword){
  return ApiResponse.success(DocumentTypesResponse.from(service.list(category,keyword)));
 }
}
