package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
public record SavedScheduleInfo(Long scheduleId,String title,LocalDate date,long dDay){}
