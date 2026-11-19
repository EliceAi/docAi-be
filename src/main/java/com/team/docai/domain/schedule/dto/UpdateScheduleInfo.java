package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
public record UpdateScheduleInfo(Long scheduleId,String title,LocalDate date,long dDay,String source,String location){}
