package com.team.docai.domain.documenttype;
import com.team.docai.domain.documenttype.entity.DocumentType;
import com.team.docai.domain.documenttype.repository.DocumentTypeRepository;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DocumentTypeServiceTest {
 @Test void twoTypesGroupFilterSearchAndSupportedFlags() throws Exception {
  List<DocumentType> rows=new ArrayList<>();
  for(String code:List.of("EMPLOYMENT","LEASE")){
   var c=DocumentType.class.getDeclaredConstructor();c.setAccessible(true);var item=c.newInstance();
   boolean work=code.equals("EMPLOYMENT");
   ReflectionTestUtils.setField(item,"code",code);
   ReflectionTestUtils.setField(item,"name",work?"근로계약서":"임대차계약서");
   ReflectionTestUtils.setField(item,"category",work?"WORK":"HOME");
   ReflectionTestUtils.setField(item,"situation",work?"취업·근로 시작":"집 구하기");
   ReflectionTestUtils.setField(item,"description",work?"최저임금":"관리비");
   ReflectionTestUtils.setField(item,"checkLevel","A");
   ReflectionTestUtils.setField(item,"supported",true);
   ReflectionTestUtils.setField(item,"popular",true);rows.add(item);
  }
  var constructor=DocumentType.class.getDeclaredConstructor();constructor.setAccessible(true);var unsupported=constructor.newInstance();
  ReflectionTestUtils.setField(unsupported,"code","REGISTRY");rows.add(unsupported);
  var repo=mock(DocumentTypeRepository.class);when(repo.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(rows);
  var service=new DocumentTypeService(mock(JdbcTemplate.class),repo);
  var all=service.list(null,null);assertEquals(2,all.totalCount());assertEquals(2,all.groups().size());
  assertEquals(Set.of("EMPLOYMENT","LEASE"),new HashSet<>(all.popular()));
  assertEquals(2,all.groups().stream().flatMap(g->g.documentTypes().stream()).filter(i->i.supported()).count());
  assertEquals(1,service.list("WORK",null).totalCount());assertEquals(1,service.list("HOME",null).totalCount());
  assertEquals(1,service.list(null," 임대 ").totalCount());assertEquals(0,service.list(null,"%_").totalCount());
  assertThrows(BusinessException.class,()->service.list("home",null));
 }
}
