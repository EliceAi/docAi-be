package com.team.docai.web.contract.dto.request;
import jakarta.validation.constraints.NotBlank;
public record ChangeDocumentTypeRequest(@NotBlank(message="문서 종류는 필수입니다") String documentTypeCode){}
