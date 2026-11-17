package com.team.docai.domain.documenttype.dto;
import java.util.List;
public record DocumentTypesInfo(int totalCount,List<String> popular,List<Group> groups) {
 public record Group(String category,String situation,List<Item> documentTypes) {}
 public record Item(String code,String name,String description,String checkLevel,boolean supported) {}
}
