package com.team.docai.domain.contract.dto;
import java.util.List;
public record CreateContractCommand(String documentTypeCode,List<byte[]> pages,List<Integer> maskedCounts){}
