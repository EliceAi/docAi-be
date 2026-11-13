package com.team.docai.web.schedule.dto.request;

import com.team.docai.domain.schedule.dto.ArchiveCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import java.time.LocalDate;
public record ArchiveRequest(@NotNull(message="일정 목록은 필수입니다") @Size(max=20,message="일정은 최대 20개입니다") List<@Valid Item> schedules,UUID previousId,boolean sameContractConfirmed) {
    public record Item(@NotBlank(message="일정 식별자는 필수입니다") String key,@NotNull(message="날짜를 입력해 주세요") LocalDate date){}
    public ArchiveCommand toCommand(){return new ArchiveCommand(schedules.stream().map(s->new ArchiveCommand.Item(s.key(),s.date())).toList(),previousId,sameContractConfirmed);}
}
