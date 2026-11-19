package com.team.docai.domain.schedule.dto;
import java.time.LocalDate;
public record UpdateScheduleCommand(String title,LocalDate date){}
