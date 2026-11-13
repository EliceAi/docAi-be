package com.team.docai.domain.schedule.dto;

import java.util.UUID;
import java.time.LocalDate;
import com.team.docai.domain.schedule.entity.ContractSchedule;
public record ScheduleInfo(UUID id,UUID documentId,String title,LocalDate date,String source){public static ScheduleInfo from(ContractSchedule s){return new ScheduleInfo(s.getId(),s.getDocumentId(),s.getTitle(),s.getDate(),s.getSource());}}
