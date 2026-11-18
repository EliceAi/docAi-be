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
  var selected=all.stream().filter(t->java.util.Set.of("EMPLOYMENT","LEASE").contains(t.getCode())).filter(t->category==null||category.equals(t.getCategory()))
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
  jdbc.update("INSERT INTO document_type(code,name,category,situation,description,check_level,supported,popular,sort_order) VALUES ('EMPLOYMENT','근로계약서','WORK','취업·근로 시작','필수 기재사항, 최저임금, 주휴수당','A',true,true,1),('LEASE','임대차계약서','HOME','집 구하기','임대인 = 등기부 소유자인지, 특약, 관리비','A',true,true,2) ON CONFLICT (code) DO NOTHING");
 }
 public String getCheckLevel(String documentTypeCode){
  if(documentTypeCode==null)throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
  return repository.findById(documentTypeCode).map(t->t.getCheckLevel()).orElseThrow(()->new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE));
 }
 public void requireSupported(String code){
  if(!java.util.Set.of("EMPLOYMENT","LEASE").contains(code))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
  Boolean supported=jdbc.query("SELECT supported FROM document_type WHERE code=?",rs->rs.next()?rs.getBoolean(1):false,code);
  if(!Boolean.TRUE.equals(supported))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
 }
}
