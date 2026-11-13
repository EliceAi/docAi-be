package com.team.docai.web.schedule.dto.response;

import com.team.docai.domain.schedule.dto.ScheduleInfo;
import java.util.UUID;
import java.time.LocalDate;
public record ScheduleResponse(UUID id,UUID documentId,String title,LocalDate date,String source){public static ScheduleResponse from(ScheduleInfo i){return new ScheduleResponse(i.id(),i.documentId(),i.title(),i.date(),i.source());}}
