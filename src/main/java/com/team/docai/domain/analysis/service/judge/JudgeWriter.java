package com.team.docai.domain.analysis.service.judge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.ScheduleCandidateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2차 판단·검증 결과와 완료 전 결과(일정 후보)의 저장 (아키텍처 9단계). 메서드마다 짧은 트랜잭션 하나.
 * GPT 를 기다리는 동안 DB 연결·잠금을 쥐지 않도록, 계산은 JudgeStepHandler 가 트랜잭션 밖에서 끝내고 여기서는 넣기만 한다.
 * 조항 변경은 조건부 UPDATE: 판정 중(JUDGING)이고 제외되지 않은 그 분석의 조항일 때만 바꾼다. 계약서 행은 잠그지 않는다.
 */
@Component
@RequiredArgsConstructor
public class JudgeWriter {

    static final String LOG_KEY = "judge";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AnalysisRepository analysisRepository;
    private final ScheduleCandidateRepository scheduleCandidateRepository;

    /**
     * 조항 하나의 최종 판단 (검증을 통과한 값).
     *
     * @param citedLabels 인용하고 검증을 통과한 근거 ID (analysis_clause_ref.cited = true 로)
     * @param relatedIds  함께 볼 조항 ID (같은 분석, 자기 자신 아님)
     * @param priority    우선 확인 순서 (NEEDS_CHECK·UNCERTAIN 만, 그 밖은 null)
     */
    record ClauseJudgment(Long clauseId, ClauseVerdict verdict, String headline, String easyExplanation,
                          String whyCheck, List<String> citedLabels, List<Long> relatedIds,
                          List<String> askCounterpart, Integer priority) {
    }

    /**
     * 2차 결과 저장 (한 트랜잭션).
     * <ol>
     *   <li>분석 중인지 확인하며 진행 단계 SUMMARIZING·heartbeat 갱신 (조건부). 이미 끝난 분석이면 아무것도 저장하지 않고 false</li>
     *   <li>조항 판정·설명·물어볼 것·우선순서. 규칙 결과 조항(basis_type = RULE)의 headline·why_check·basis_type 은 그대로 둔다</li>
     *   <li>인용한 근거 cited = true</li>
     *   <li>관련 조항 (같은 쌍이 있으면 넘어감)</li>
     *   <li>의심 표시 없는 판정 중 조항 → OK</li>
     *   <li>analysis.questions_json, validation_log_json(앞 단계 기록에 "judge" 로 이어 붙임)</li>
     * </ol>
     */
    @Transactional
    public boolean saveJudgments(Long analysisId, List<ClauseJudgment> judgments, List<String> questions,
                                 List<Map<String, Object>> log) {
        if (analysisRepository.advance(analysisId, AnalysisStep.SUMMARIZING, OffsetDateTime.now()) != 1) {
            return false;
        }
        for (ClauseJudgment j : judgments) {
            int updated = jdbc.update("""
                            update analysis_clause
                               set verdict = ?, basis_type = coalesce(basis_type, 'AI_INTERPRETATION'),
                                   headline = coalesce(headline, ?), easy_explanation = ?,
                                   why_check = case when basis_type = 'RULE' then why_check else ? end,
                                   ask_counterpart_json = cast(? as jsonb), priority = ?
                             where clause_id = ? and analysis_id = ? and verdict = 'JUDGING' and excluded = false
                            """,
                    j.verdict().name(), j.headline(), j.easyExplanation(), j.whyCheck(), toJson(j.askCounterpart()),
                    j.priority(), j.clauseId(), analysisId);
            if (updated != 1) {
                continue;
            }
            for (String label : j.citedLabels()) {
                jdbc.update("update analysis_clause_ref set cited = true where clause_id = ? and ref_label = ?",
                        j.clauseId(), label);
            }
            for (Long related : j.relatedIds()) {
                jdbc.update("""
                                insert into analysis_clause_relation (analysis_id, clause_id, related_clause_id)
                                values (?, ?, ?) on conflict do nothing
                                """, analysisId, j.clauseId(), related);
            }
        }
        jdbc.update("""
                update analysis_clause set verdict = 'OK'
                 where analysis_id = ? and verdict = 'JUDGING' and excluded = false and issue_found = false
                """, analysisId);

        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("분석이 없습니다: " + analysisId));
        analysis.recordJudgment(questions, mergeValidationLog(analysis.getValidationLogJson(), log));
        return true;
    }

    /** 일정 후보 저장. 이미 이 분석의 후보가 있으면(다시 돈 경우) 넣지 않는다. @return 넣은 수 */
    @Transactional
    public int saveScheduleCandidates(Long analysisId, List<ScheduleCandidate> candidates) {
        if (candidates.isEmpty() || scheduleCandidateRepository.countByAnalysisId(analysisId) > 0) {
            return 0;
        }
        scheduleCandidateRepository.saveAll(candidates);
        return candidates.size();
    }

    /** 기존 기록(다른 단계)에 2차 기록을 "judge" 칸으로 더한다. 이미 "judge" 가 있으면 뒤에 이어 붙인다 */
    String mergeValidationLog(String existing, List<Map<String, Object>> judgeLog) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(existing, Map.class);
                parsed.forEach((k, v) -> root.put(String.valueOf(k), v));
            } catch (JsonProcessingException e) {
                root.put("previous", existing);
            }
        }
        List<Object> merged = new java.util.ArrayList<>();
        if (root.get(LOG_KEY) instanceof List<?> previous) {
            merged.addAll(previous);
        }
        merged.addAll(judgeLog);
        root.put(LOG_KEY, merged);
        return toJson(root);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 으로 바꿀 수 없습니다: " + e.getClass().getSimpleName());
        }
    }
}
