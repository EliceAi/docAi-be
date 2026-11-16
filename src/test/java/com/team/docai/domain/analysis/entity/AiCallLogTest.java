package com.team.docai.domain.analysis.entity;

import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.dto.AiCallLogCommand;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiCallLogTest {

    AiCallLogCommand command(String model, String errorMessage) {
        return new AiCallLogCommand(null, GptPurpose.CHAT, null, null, null, null,
                model, null, null, null, 10, AiCallStatus.FAILED, errorMessage);
    }

    @Test
    void 오류_메시지는_2000자로_자른다() {
        AiCallLog log = AiCallLog.create(command("m", "가".repeat(2000) + "나"));

        assertThat(log.getErrorMessage()).hasSize(2000).doesNotContain("나");
    }

    @Test
    void 모델_이름은_50자로_자른다() {
        AiCallLog log = AiCallLog.create(command("a".repeat(50) + "b", null));

        assertThat(log.getModel()).hasSize(50).doesNotContain("b");
    }

    @Test
    void 한도_이내는_그대로_두고_모델이_없으면_unknown() {
        String message = "x".repeat(2000);
        assertThat(AiCallLog.create(command("a".repeat(50), message)).getErrorMessage()).isEqualTo(message);
        assertThat(AiCallLog.create(command("a".repeat(50), null)).getModel()).hasSize(50);
        assertThat(AiCallLog.create(command(null, null)).getModel()).isEqualTo("unknown");
        assertThat(AiCallLog.create(command("m", null)).getErrorMessage()).isNull();
    }
}
