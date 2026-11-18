package com.team.docai.domain.contract.dto;
import java.time.OffsetDateTime;
public record SimilarContractInfo(Long contractId,String title,OffsetDateTime analyzedAt,int scheduleCount) {}
