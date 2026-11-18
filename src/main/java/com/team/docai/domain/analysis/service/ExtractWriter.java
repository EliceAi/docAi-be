package com.team.docai.domain.analysis.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석 1차(ExtractStep)의 저장. 메서드마다 짧은 트랜잭션 하나 (GPT 를 기다리는 동안 DB 연결·잠금을 쥐지 않게).
 * 계약서 행은 잠그지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExtractWriter {

    private final AnalysisRepository analysisRepository;
    private final AnalysisChunkRepository chunkRepository;
    private final AnalysisClauseRepository clauseRepository;
    private final AnalysisClauseRelationRepository relationRepository;
    private final AnalysisKeyTermRepository keyTermRepository;
    private final ObjectMapper objectMapper;

    public List<AnalysisChunk> findChunks(Long analysisId) {
        return chunkRepository.findByAnalysisIdOrderByChunkNoAsc(analysisId);
    }

    /** 묶음 성공: status = DONE, result_json, retry_count */
    @Transactional
    public void completeChunk(Long chunkId, String resultJson, int retryCount) {
        chunkRepository.findById(chunkId).orElseThrow(() -> new IllegalStateException("묶음이 없습니다: " + chunkId))
                .complete(resultJson, retryCount);
    }

    /** 묶음 최종 실패: status = FAILED, retry_count */
    @Transactional
    public void failChunk(Long chunkId, int retryCount) {
        chunkRepository.findById(chunkId).ifPresent(c -> c.fail(retryCount));
    }

    /**
     * 병합 결과 저장 (한 트랜잭션).
     * <ol>
     *   <li>진행 단계 FINDING_TERMS (분석 중일 때만, 조건부). 이미 끝난 분석이면 아무것도 저장하지 않고 false</li>
     *   <li>조항 (verdict = JUDGING, 원문 없으면 UNREADABLE: AnalysisClause.create 규칙)</li>
     *   <li>관련 조항 (병합에서 중복 쌍 제거됨)</li>
     *   <li>주요 조건 (조항 키 → clause_id)</li>
     *   <li>analysis: extraction_json, clause_index_json, partial_pages, law_base_date, validation_log_json</li>
     * </ol>
     */
    @Transactional
    public boolean saveMerged(Long analysisId, ExtractMerger.Result result) {
        if (analysisRepository.advance(analysisId, AnalysisStep.FINDING_TERMS, OffsetDateTime.now()) != 1) {
            return false;
        }
        List<AnalysisClause> clauses = new ArrayList<>();
        for (ExtractMerger.MergedClause c : result.clauses()) {
            clauses.add(AnalysisClause.create(analysisId, c.clauseKey(), c.articleNo(), c.title(), c.originalText(),
                    c.oneLine(), c.pageNo(), c.location(), c.issueFound(), c.issueSummary(), c.issueTagCode(),
                    c.lawHints(), c.searchKeywords()));
        }
        Map<String, Long> idByKey = new HashMap<>();
        for (AnalysisClause saved : clauseRepository.saveAll(clauses)) {
            idByKey.put(saved.getClauseKey(), saved.getClauseId());
        }
        List<AnalysisClauseRelation> relations = new ArrayList<>();
        for (ExtractMerger.Relation r : result.relations()) {
            relations.add(AnalysisClauseRelation.create(analysisId, idByKey.get(r.clauseKey()),
                    idByKey.get(r.relatedClauseKey())));
        }
        relationRepository.saveAll(relations);
        List<AnalysisKeyTerm> keyTerms = new ArrayList<>();
        for (ExtractMerger.MergedKeyTerm t : result.keyTerms()) {
            keyTerms.add(AnalysisKeyTerm.create(analysisId, t.clauseKey() == null ? null : idByKey.get(t.clauseKey()),
                    t.label(), t.value(), t.valueJson(), t.state(), t.source(), t.evidence(), t.sortOrder()));
        }
        keyTermRepository.saveAll(keyTerms);

        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("분석이 없습니다: " + analysisId));
        analysis.recordExtraction(toJson(result.extraction()), toJson(result.clauseIndex()), result.partialPages(),
                result.lawBaseDate(), mergeValidationLog(analysis.getValidationLogJson(), result.validationLog()));
        return true;
    }

    /** 기존 기록(다른 단계)에 1차 기록을 "extract" 칸으로 더한다 */
    private String mergeValidationLog(String existing, List<Map<String, Object>> extractLog) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (existing != null && !existing.isBlank()) {
            try {
                Map<?, ?> parsed = objectMapper.readValue(existing, Map.class);
                parsed.forEach((k, v) -> root.put(String.valueOf(k), v));
            } catch (JsonProcessingException e) {
                root.put("previous", existing);
            }
        }
        root.put("extract", extractLog);
        return toJson(root);
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 으로 바꿀 수 없습니다: " + e.getClass().getSimpleName());
        }
    }
}
