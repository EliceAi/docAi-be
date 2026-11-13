package com.team.docai.domain.schedule.dto;

import java.util.*;
import java.time.LocalDate;
public record ArchiveCommand(List<Item> schedules,UUID previousId,boolean sameContractConfirmed){public record Item(String key,LocalDate date){}}
