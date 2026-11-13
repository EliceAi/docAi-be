package com.team.docai.domain.document.service;

import com.team.docai.domain.document.dto.CatalogInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
@Service @Transactional(readOnly=true)
public class CatalogService {
    private final List<CatalogInfo> catalog;
    public CatalogService(ObjectMapper mapper) throws java.io.IOException {try(var in=new ClassPathResource("catalog.json").getInputStream()){catalog=List.of(mapper.readValue(in,CatalogInfo[].class));}}
    public List<CatalogInfo> list(){return catalog;}
}
