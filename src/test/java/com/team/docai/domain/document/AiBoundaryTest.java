package com.team.docai.domain.document;

import com.team.docai.domain.document.client.*;
import com.team.docai.domain.document.entity.DocumentType;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AiBoundaryTest {
    @Test void mockResultsAlwaysDiscloseThatImagesWereNotRead() {
        var client = new MockDocumentAiClient();
        for(var type:DocumentType.values()) {
            var result=client.analyze(type,List.of(new byte[]{1,2}),"normal");
            assertThat(result.mode()).isEqualTo("MOCK");
            assertThat(result.summary()).contains("읽거나 법률을 검증한 결과가 아닙니다");
            assertThat(result.conditions()).allMatch(c->c.source().equals("시연 예시"));
        }
    }
    @Test void partialAndFailureScenariosAreDistinct() {
        var client=new MockDocumentAiClient();
        assertThat(client.analyze(DocumentType.LEASE,List.of(),"partial").partial()).isTrue();
        assertThatThrownBy(()->client.analyze(DocumentType.LEASE,List.of(),"failure")).isInstanceOf(BusinessException.class);
    }
    @Test void openAiIsAnInertConnectionPointWithoutNetworkCalls() {
        var client=new OpenAiDocumentClient();
        assertThatThrownBy(()->client.analyze(DocumentType.LEASE,List.of(),"normal"))
            .isInstanceOfSatisfying(BusinessException.class,e->assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED));
        assertThatThrownBy(()->client.answer("context","question")).isInstanceOf(BusinessException.class);
    }
}
