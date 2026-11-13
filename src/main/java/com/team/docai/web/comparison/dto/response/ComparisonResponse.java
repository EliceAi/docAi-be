package com.team.docai.web.comparison.dto.response;

import com.team.docai.domain.comparison.dto.ComparisonInfo;
import java.util.*;
public record ComparisonResponse(UUID previousId,UUID currentId,List<ComparisonInfo.Change> changes,boolean partial){public static ComparisonResponse from(ComparisonInfo i){return new ComparisonResponse(i.previousId(),i.currentId(),i.changes(),i.partial());}}
