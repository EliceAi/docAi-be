package com.team.docai.domain.document.dto;

import com.team.docai.domain.document.entity.DocumentType;
import java.util.*;
public record CreateCheckCommand(String title,DocumentType documentType,List<byte[]> pages,UUID requestKey,String scenario) {}
