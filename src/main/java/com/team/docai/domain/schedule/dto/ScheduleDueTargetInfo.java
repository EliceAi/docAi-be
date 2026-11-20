package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
import java.util.UUID;
public record ScheduleDueTargetInfo(Long scheduleId,UUID userId,Long contractId,String dateKind,String title,LocalDate date){}
