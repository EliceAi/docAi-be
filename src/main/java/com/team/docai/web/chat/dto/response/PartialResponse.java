package com.team.docai.web.chat.dto.response;

import com.team.docai.domain.chat.entity.ChatPartial;

import java.util.List;

/** PARTIAL 일 때 {confirmed: 계약서로 확인한 것, unknown: 확인할 수 없는 것} */
public record PartialResponse(List<String> confirmed, List<String> unknown) {
    public static PartialResponse from(ChatPartial partial) {
        return partial == null ? null : new PartialResponse(partial.confirmed(), partial.unknown());
    }
}
