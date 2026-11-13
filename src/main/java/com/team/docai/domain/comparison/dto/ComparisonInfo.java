package com.team.docai.domain.comparison.dto;

import java.util.*;
public record ComparisonInfo(UUID previousId,UUID currentId,List<Change> changes,boolean partial){public record Change(String label,String before,String after,String status,String source){}}
