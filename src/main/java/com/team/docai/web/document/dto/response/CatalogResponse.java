package com.team.docai.web.document.dto.response;

import com.team.docai.domain.document.dto.CatalogInfo;
public record CatalogResponse(String name,String category,String group,String description,String code,boolean available){public static CatalogResponse from(CatalogInfo i){return new CatalogResponse(i.name(),i.category(),i.group(),i.description(),i.code(),i.available());}}
