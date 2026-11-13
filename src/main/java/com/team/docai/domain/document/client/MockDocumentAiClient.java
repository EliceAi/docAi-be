package com.team.docai.domain.document.client;

import com.team.docai.domain.document.dto.AnalysisInfo;
import com.team.docai.domain.document.dto.AnalysisInfo.*;
import com.team.docai.domain.document.entity.DocumentType;
import com.team.docai.global.error.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;
@Component @ConditionalOnProperty(name="docai.ai-mode",havingValue="mock",matchIfMissing=true)
public class MockDocumentAiClient implements DocumentAiClient {
    @Override public AnalysisInfo analyze(DocumentType type,List<byte[]> maskedPages,String scenario) {
        if("failure".equals(scenario))throw new BusinessException(ErrorCode.AI_FAILED);
        boolean work=type==DocumentType.EMPLOYMENT, partial="partial".equals(scenario), revised="revised".equals(scenario);
        return new AnalysisInfo("MOCK","시연용 고정 예시입니다. 업로드한 이미지의 내용을 읽거나 법률을 검증한 결과가 아닙니다.",partial,
            work?List.of(new Condition("임금",revised?"시급 12,000원":"시급 10,000원","시연 예시"),new Condition("근로시간","주 20시간","시연 예시"),new Condition("계약 시작","2026-12-01","시연 예시"),new Condition("휴일",partial?"읽지 못함":"기재 확인 필요","시연 예시")):
            List.of(new Condition("보증금","10,000,000원","시연 예시"),new Condition("월세",revised?"650,000원":"600,000원","시연 예시"),new Condition("관리비",partial?"읽지 못함":"50,000원","시연 예시"),new Condition("계약 기간","2026-12-01 ~ 2028-11-30","시연 예시")),
            work?List.of(new Clause("1","계약 기간","2026년 12월 1일부터 2027년 2월 28일까지","INFO","계약 기간과 실제 출근일을 비교해 보세요.","별도 검토 필요","시연 예시"),
                new Clause("2","교육비 반환",revised?"교육비 반환 조항 삭제":"1년 안에 퇴사하면 교육비 30만 원 반환","REVIEW","교육비의 성격과 반환 약정을 확인하는 화면 예시입니다. 위법 여부를 판정한 결과가 아닙니다.","근로기준법 제20조 · 최신 조문 별도 확인 필요","시연 예시")):
                List.of(new Clause("1","차임",revised?"매월 월세 65만 원 지급":"매월 월세 60만 원 지급","INFO","지급일과 계좌를 계약 당사자에게 확인하세요.","별도 검토 필요","시연 예시"),new Clause("2","원상복구","퇴거 시 도배 비용 전액을 임차인이 부담한다","REVIEW","통상 사용에 따른 마모와 실제 훼손의 구분을 확인하는 예시입니다.","법령·특약 별도 검토 필요","시연 예시")),
            work?List.of("휴일 기재 여부","연차유급휴가 기재 여부"):List.of("관리비 세부 항목"),
            List.of(new Suggestion("start",work?"첫 출근":"입주·전입신고 확인","2026-12-01","시연 예시","저장 전 실제 날짜를 확인하세요."),new Suggestion("end","계약 종료",work?"2027-02-28":"2028-11-30","시연 예시","계약서 원문 확인 필요"),new Suggestion("review","추가 확인 일정","","확인 필요","날짜를 직접 입력한 경우에만 저장됩니다.")));
    }
    @Override public String answer(String context,String question) {
        if(context.isBlank())return "[모의 답변] 현재 근로계약서와 임대차계약서 두 종류를 시연할 수 있어요. 문서 선택 → 올리기 → 직접 가리기 → 최종 확인 순서로 진행해 주세요. 이미지 판독과 법률 검증은 아직 연결되어 있지 않아요.";
        return "[모의 답변] 이 대화에 연결된 문서의 조건·조항 화면을 확인해 주세요. 가린 내용이나 읽지 못한 내용은 추정하지 않아요. 현재는 고정 예시 응답이며 질문에 대한 실제 AI 분석·법률 판단은 하지 않습니다. 일정은 ‘일정 확인하고 저장’에서 직접 확인해야 등록됩니다.";
    }
}
