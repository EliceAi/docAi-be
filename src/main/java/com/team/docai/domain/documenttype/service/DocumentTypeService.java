package com.team.docai.domain.documenttype.service;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DocumentTypeService implements org.springframework.boot.ApplicationRunner {
 private final JdbcTemplate jdbc;
 private final com.team.docai.domain.documenttype.repository.DocumentTypeRepository repository;
 public com.team.docai.domain.documenttype.dto.DocumentTypesInfo list(String category,String keyword){
  if(category!=null&&!java.util.Set.of("HOME","WORK").contains(category))throw new BusinessException(ErrorCode.INVALID_INPUT);
  String search=keyword==null?"":keyword.strip().toLowerCase(java.util.Locale.ROOT);
  var all=repository.findAllByOrderBySortOrderAscCodeAsc();
  var selected=all.stream().filter(t->category==null||category.equals(t.getCategory()))
   .filter(t->(t.getName()+" "+t.getDescription()).toLowerCase(java.util.Locale.ROOT).contains(search)).toList();
  record GroupKey(String category,String situation) {}
  var groups=new java.util.LinkedHashMap<GroupKey,java.util.List<com.team.docai.domain.documenttype.dto.DocumentTypesInfo.Item>>();
  for(var t:selected)groups.computeIfAbsent(new GroupKey(t.getCategory(),t.getSituation()),k->new java.util.ArrayList<>())
   .add(new com.team.docai.domain.documenttype.dto.DocumentTypesInfo.Item(t.getCode(),t.getName(),t.getDescription(),t.getCheckLevel(),t.isSupported()));
  return new com.team.docai.domain.documenttype.dto.DocumentTypesInfo(selected.size(),selected.stream().filter(t->t.isPopular()).map(t->t.getCode()).toList(),
   groups.entrySet().stream().map(e->new com.team.docai.domain.documenttype.dto.DocumentTypesInfo.Group(e.getKey().category(),e.getKey().situation(),java.util.List.copyOf(e.getValue()))).toList());
 }
 @Override @Transactional
 public void run(org.springframework.boot.ApplicationArguments args){
  try(var input=new org.springframework.core.io.ClassPathResource("document-types.json").getInputStream()){
   var items=new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
   for(var item:items)jdbc.update("INSERT INTO document_type(code,name,category,situation,description,check_level,supported,popular,sort_order) VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT (code) DO NOTHING",
    item.path("code").asText(),item.path("name").asText(),item.path("category").asText(),item.path("situation").asText(),item.path("description").asText(),item.path("checkLevel").asText(),item.path("supported").asBoolean(),item.path("popular").asBoolean(),item.path("sortOrder").asInt());
  }catch(java.io.IOException e){throw new IllegalStateException("문서 종류 목록을 읽을 수 없습니다.",e);}
 }
 public void requireSupported(String code){
  if(!java.util.Set.of("EMPLOYMENT","LEASE").contains(code))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
  Boolean supported=jdbc.query("SELECT supported FROM document_type WHERE code=?",rs->rs.next()?rs.getBoolean(1):false,code);
  if(!Boolean.TRUE.equals(supported))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
 }
}
