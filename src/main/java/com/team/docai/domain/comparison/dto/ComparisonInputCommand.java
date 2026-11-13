package com.team.docai.domain.comparison.dto;

import java.util.UUID;
public record ComparisonInputCommand(UUID previousId,UUID currentId,String label,String before,String after){}
