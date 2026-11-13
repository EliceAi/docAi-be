package com.team.docai.web.document.dto.request;

import com.team.docai.domain.document.dto.CreateCheckCommand;
import com.team.docai.domain.document.entity.DocumentType;
import com.team.docai.global.error.*;
import jakarta.validation.constraints.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;
public record CreateCheckRequest(@NotBlank(message="문서 제목은 필수입니다") @Size(max=100,message="제목은 100자 이내입니다") String title,
    @NotNull(message="문서 종류는 필수입니다") DocumentType documentType,
    @NotEmpty(message="가린 이미지를 올려 주세요") @Size(max=10,message="최대 10쪽까지 가능합니다") List<MultipartFile> pages,
    @NotNull(message="요청 식별자는 필수입니다") UUID requestKey,
    @AssertTrue(message="가린 이미지 최종 확인이 필요합니다") boolean confirmed,
    @NotNull(message="시나리오는 필수입니다") String scenario) {
    public CreateCheckCommand toCommand(){try {List<byte[]> bytes=new ArrayList<>();for(var p:pages)bytes.add(p.getBytes());return new CreateCheckCommand(title,documentType,bytes,requestKey,scenario);}catch(java.io.IOException e){throw new BusinessException(ErrorCode.FILE_INVALID);}}
}
