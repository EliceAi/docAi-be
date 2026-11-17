package com.team.docai.domain.chat.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class ChatServiceTest {

    @Test
    void moveContract_옮길_대화가_없으면_아무것도_하지_않는다() {
        assertThatCode(() -> new ChatService().moveContract(21L, 15L)).doesNotThrowAnyException();
    }
}
