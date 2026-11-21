package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.client.GptContentPart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 2차 지시문의 판단 기준과 버전 */
class JudgePromptTest {

    @Test
    void 버전은_judge_v3() {
        assertThat(JudgePrompt.VERSION).isEqualTo("judge-v3");
    }

    @Test
    void 판단_기준은_1차_의심보다_원문이_정한_내용으로_보고_불리한_내용이_없으면_근거가_없어도_OK() {
        assertThat(JudgePrompt.SYSTEM_PROMPT).contains(
                "[판단 기준] 같은 문장에는 언제나 같은 판단을 낸다.",
                "1차 의심 내용은 참고일 뿐이다",
                "불리한 내용이 없으면 근거가 없어도 OK 다",
                "불리할 수 있는 내용이 있는데 준 근거로 판단하기 부족하면 그 조항은 UNCERTAIN",
                "UNCERTAIN(불리할 수 있지만 준 근거로는 판단할 수 없음)",
                "OK(불리한 내용이 없거나, 준 근거로 보아 법에 맞음)")
                // 옛 정의(근거만 부족해도 UNCERTAIN, 근거를 보아야 OK)가 남지 않는다
                .doesNotContain("- 준 근거로 판단하기 부족하면", "의심되지만 준 근거로는", "근거를 보니 문제 될 것이 없음");
    }

    @Test
    void 준용_보충_조항과_관할법원_조항은_불리한_내용이_없으면_OK() {
        assertThat(JudgePrompt.SYSTEM_PROMPT).contains(
                "법령이나 회사·임대인이 정한 규정을 따른다는 보충 조항, 관할법원·분쟁 절차를 정한 조항은",
                "그 자체로 불리한 내용을 정하지 않으면 OK 다",
                "생길지 모르는 일만으로는",
                "다만 준 근거가 그 조항 내용 자체를 직접 문제 삼으면 그 근거로 판단한다");
    }

    @Test
    void 다른_문서에_맡긴_조항은_함께_제출됐는지와_빠진_필수_항목에_따라_판단() {
        assertThat(JudgePrompt.SYSTEM_PROMPT).contains(
                "그 문서가 전체 목차에 함께 있으면 그 내용으로 판단한다",
                "목차에 없고 맡긴 항목이 [규칙 결과: 빠진 필수 항목]에 있으면 OK 로 두지 않는다",
                "그 밖에는 다른 문서를 언급한 것만으로 확인이 필요하다고 보지 않는다");
        // 사용자 내용의 규칙 결과 머리말과 같은 이름을 가리킨다
        GptContentPart head = JudgePrompt.userContent(List.of(), List.of(), List.of(), false).get(0);
        assertThat(((GptContentPart.Text) head).text()).contains("[규칙 결과: 빠진 필수 항목]");
    }

    @Test
    void 판단_기준은_근거_규칙_뒤_판단_칸_설명_앞에_있고_특정_계약서_문구를_담지_않는다() {
        String p = JudgePrompt.SYSTEM_PROMPT;
        assertThat(p.indexOf("[판단 기준]")).isGreaterThan(p.indexOf("[근거 규칙]"))
                .isLessThan(p.indexOf("[판단 judgments]"));
        assertThat(p).doesNotContain("노동관계법", "제 규정", "\"갑\"", "소재지 관할법원");
    }
}
