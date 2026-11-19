package com.team.docai.domain.documenttype;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.documenttype.entity.DocumentType;
import com.team.docai.domain.documenttype.repository.DocumentTypeRepository;
import com.team.docai.domain.documenttype.service.DocumentTypeService;
import com.team.docai.global.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class DocumentTypeServiceTest {
 @Test void all23TypesGroupFilterSearchAndSupportedFlags() throws Exception {
  var mapper=new ObjectMapper();List<DocumentType> rows=new ArrayList<>();
  try(var in=new ClassPathResource("document-types.json").getInputStream()){
   for(var node:mapper.readTree(in)){
    var c=DocumentType.class.getDeclaredConstructor();c.setAccessible(true);var item=c.newInstance();
    for(String field:List.of("code","name","category","situation","description","checkLevel"))ReflectionTestUtils.setField(item,field,node.get(field).asText());
    for(String field:List.of("supported","popular"))ReflectionTestUtils.setField(item,field,node.get(field).asBoolean());
    ReflectionTestUtils.setField(item,"sortOrder",node.get("sortOrder").asInt());rows.add(item);
   }
  }
  var repo=mock(DocumentTypeRepository.class);when(repo.findAllByOrderBySortOrderAscCodeAsc()).thenReturn(rows);
  var service=new DocumentTypeService(mock(JdbcTemplate.class),repo);
  var all=service.list(null,null);assertEquals(23,all.totalCount());assertEquals(5,all.groups().size());
  assertEquals(Set.of("EMPLOYMENT","LEASE"),new HashSet<>(all.popular()));
  assertEquals(2,all.groups().stream().flatMap(g->g.documentTypes().stream()).filter(i->i.supported()).count());
  assertEquals(10,service.list("WORK",null).totalCount());assertEquals(13,service.list("HOME",null).totalCount());
  assertEquals(2,service.list(null," 보증금 ").totalCount());assertEquals(0,service.list(null,"%_").totalCount());
  assertThrows(BusinessException.class,()->service.list("home",null));
 }
}
