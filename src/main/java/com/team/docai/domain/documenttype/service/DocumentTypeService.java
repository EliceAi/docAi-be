package com.team.docai.domain.documenttype.service;
import com.team.docai.global.error.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class DocumentTypeService implements org.springframework.boot.ApplicationRunner {
 private final JdbcTemplate jdbc;
 @Override @Transactional
 public void run(org.springframework.boot.ApplicationArguments args){
  jdbc.update("INSERT INTO document_type(code,name,category,situation,description,check_level,supported,popular,sort_order) VALUES ('EMPLOYMENT','근로계약서','WORK','취업','근로 조건 확인','A',true,true,1),('LEASE','임대차계약서','HOME','집 구하기','임대차 조건 확인','A',true,true,2) ON CONFLICT (code) DO NOTHING");
 }
 public void requireSupported(String code){
  if(!java.util.Set.of("EMPLOYMENT","LEASE").contains(code))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
  Boolean supported=jdbc.query("SELECT supported FROM document_type WHERE code=?",rs->rs.next()?rs.getBoolean(1):false,code);
  if(!Boolean.TRUE.equals(supported))throw new BusinessException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE);
 }
}
