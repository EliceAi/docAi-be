package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.AnalysisStepHandler;
import com.team.docai.domain.analysis.service.AnalysisTitle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 9단계 중 완료 트랜잭션 전에 하는 일 (진행 단계 SUMMARIZING 유지): 일정 후보.
 * 1차가 읽은 날짜(extraction_json.dates)로 schedule_candidate 를 만든다 ({@link ScheduleCandidateBuilder}).
 * <p>
 * 이 단계 다음에 파이프라인이 조건부 COMPLETED(최신 분석 연결·완료 알림)를 하고, 완료된 뒤에 계약서 이름(B4)을 넣는다.
 * 이름 규칙은 {@link AnalysisTitle}: 1차가 읽은 문서 제목, 없으면 문서 종류 이름. 완료 알림 부제도 같은 이름을 쓴다.
 */
@Slf4j
@Component
@Order(FinishStepHandler.ORDER)
@RequiredArgsConstructor
public class FinishStepHandler implements AnalysisStepHandler {

    /** 2차 판단({@link JudgeStepHandler#ORDER}) 다음, 마지막 단계 */
    public static final int ORDER = 900;

    private final AnalysisRepository analysisRepository;
    private final AnalysisClauseRepository clauseRepository;
    private final JudgeWriter judgeWriter;
    private final ObjectMapper objectMapper;

    @Override
    public AnalysisStep progressStep() {
        return AnalysisStep.SUMMARIZING;
    }

    @Override
    public void execute(AnalysisRunContext ctx) {
        Analysis analysis = analysisRepository.findById(ctx.analysisId())
                .orElseThrow(() -> new IllegalStateException("분석이 없음: " + ctx.analysisId()));
        JsonNode extraction = readExtraction(analysis.getExtractionJson());

        Map<String, AnalysisClause> clausesByKey = clauseRepository
                .findByAnalysisIdOrderByPageNoAscClauseIdAsc(ctx.analysisId()).stream()
                .filter(c -> !c.isExcluded())
                .collect(Collectors.toMap(AnalysisClause::getClauseKey, Function.identity(), (a, b) -> a,
                        LinkedHashMap::new));
        ScheduleCandidateBuilder.Built built = ScheduleCandidateBuilder.build(ctx.analysisId(),
                extraction.path("dates"), clausesByKey);
        int saved = judgeWriter.saveScheduleCandidates(ctx.analysisId(), built.candidates());
        log.info("일정 후보 {}개 저장 (만들지 않은 날짜 {}개): analysisId={}", saved, built.log().size(), ctx.analysisId());
    }

    private JsonNode readExtraction(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.missingNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("extraction_json 을 읽지 못해 일정 후보 없이 진행");
            return objectMapper.missingNode();
        }
    }
}
