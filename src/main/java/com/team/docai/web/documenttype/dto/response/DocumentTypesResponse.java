package com.team.docai.web.documenttype.dto.response;
import com.team.docai.domain.documenttype.dto.DocumentTypesInfo;
import java.util.List;
public record DocumentTypesResponse(int totalCount,List<String> popular,List<DocumentTypesInfo.Group> groups) {
 public static DocumentTypesResponse from(DocumentTypesInfo info){return new DocumentTypesResponse(info.totalCount(),info.popular(),info.groups());}
}
