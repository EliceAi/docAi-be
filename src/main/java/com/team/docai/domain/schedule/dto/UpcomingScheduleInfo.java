package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
public record UpcomingScheduleInfo(Long scheduleId,String title,LocalDate date,long dDay,Long contractId,String contractTitle) {}
