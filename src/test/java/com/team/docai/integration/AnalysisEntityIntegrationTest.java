package com.team.docai.integration;

import com.team.docai.domain.analysis.dto.AnalysisSummaryInfo;
import com.team.docai.domain.analysis.dto.ComparisonSourceInfo;
import com.team.docai.domain.analysis.dto.ScheduleCandidateInfo;
import com.team.docai.domain.analysis.dto.VersionAnalysisStateInfo;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisChunk;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelationId;
import com.team.docai.domain.analysis.entity.AnalysisFailReason;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.AnalysisKeyTerm;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.CandidateSource;
import com.team.docai.domain.analysis.entity.ChunkStatus;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.entity.KeyTermSource;
import com.team.docai.domain.analysis.entity.KeyTermState;
import com.team.docai.domain.analysis.entity.LawHint;
import com.team.docai.domain.analysis.entity.NumberComparison;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.entity.ScheduleCandidate;
import com.team.docai.domain.analysis.repository.AnalysisChunkRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisKeyTermRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.repository.ScheduleCandidateRepository;
import com.team.docai.domain.analysis.service.AnalysisService;
import com.team.docai.domain.chat.service.ChatService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분석 엔티티 8개가 실제 DB(V2 스키마, ddl-auto validate)에 저장·조회되는지, JSONB 칸이 JSON 으로 들어가는지,
 * 조건부 상태 전환이 바뀐 행 수를 돌려주는지, B 가 부르는 AnalysisService 조회가 실제 데이터로 맞는지 확인한다.
 * 로컬 PostgreSQL 이 필요하다. 같은 DB 를 다른 프로세스가 함께 쓸 수 있으므로 이 테스트가 만든 행만 ID 로 지운다.
 * users, document_type, contract, contract_version 은 B 테이블이다. B 의 생성 서비스가 아직 없어 JDBC 로 넣는다.
 * issue_tag, guidance 는 A 테이블이지만 엔티티가 아직 없어 JDBC 로 넣는다 (근거 행의 guidance_id FK 용).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DOCAI_INTEGRATION_TESTS", matches = "true")
class AnalysisEntityIntegrationTest {

    @Autowired AnalysisRepository analysisRepository;
    @Autowired AnalysisChunkRepository chunkRepository;
    @Autowired AnalysisKeyTermRepository keyTermRepository;
    @Autowired AnalysisClauseRepository clauseRepository;
    @Autowired AnalysisClauseRefRepository refRepository;
    @Autowired AnalysisClauseRelationRepository relationRepository;
    @Autowired AnalysisFindingRepository findingRepository;
    @Autowired ScheduleCandidateRepository candidateRepository;
    @Autowired AnalysisService analysisService;
    @Autowired ChatService chatService;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    UUID userId;
    String typeCode;
    String tagCode;
    Long contractId;
    Long versionId;
    Long guidanceId;
    /** 이 테스트가 만든 분석 ID. 지울 때 이 ID 의 하위 행만 지운다 */
    final List<Long> analysisIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        long n = System.nanoTime();
        userId = UUID.randomUUID();
        typeCode = "TEST_ANALYSIS_" + n;
        tagCode = "TEST_TAG_" + n;
        jdbc.update("insert into users (user_id, device_id_hash, created_at) values (?, ?, now())",
                userId, String.format("%064x", n));
        jdbc.update("insert into document_type (code, name, category, situation, description, check_level,"
                + " supported, popular, sort_order) values (?, 'test', 'TEST', 'test', 'test', 'A', false, false, 999)",
                typeCode);
        contractId = jdbc.queryForObject("insert into contract (user_id, document_type_code, archived,"
                + " current_version_no, created_at, updated_at) values (?, ?, false, 1, now(), now())"
                + " returning contract_id", Long.class, userId, typeCode);
        versionId = jdbc.queryForObject("insert into contract_version (contract_id, version_no, page_count,"
                + " created_at, updated_at) values (?, 1, 3, now(), now()) returning version_id", Long.class, contractId);
        jdbc.update("insert into issue_tag (code, document_type_code, name) values (?, ?, 'test')", tagCode, typeCode);
        guidanceId = jdbc.queryForObject("insert into guidance (issue_tag_code, title, content, source_note, reviewed_at)"
                + " values (?, 'test', 'test', 'test', current_date) returning guidance_id", Long.class, tagCode);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : analysisIds) {
            jdbc.update("delete from analysis_clause_relation where analysis_id = ?", id);
            jdbc.update("delete from analysis_clause_ref where clause_id in"
                    + " (select clause_id from analysis_clause where analysis_id = ?)", id);
            jdbc.update("delete from analysis_key_term where analysis_id = ?", id);
            jdbc.update("delete from analysis_finding where analysis_id = ?", id);
            jdbc.update("delete from schedule_candidate where analysis_id = ?", id);
            jdbc.update("delete from analysis_clause where analysis_id = ?", id);
            jdbc.update("delete from analysis_chunk where analysis_id = ?", id);
            jdbc.update("delete from analysis where analysis_id = ?", id);
        }
        jdbc.update("delete from guidance where guidance_id = ?", guidanceId);
        jdbc.update("delete from issue_tag where code = ?", tagCode);
        jdbc.update("delete from contract_version where version_id = ?", versionId);
        jdbc.update("delete from contract where contract_id = ?", contractId);
        jdbc.update("delete from document_type where code = ?", typeCode);
        jdbc.update("delete from users where user_id = ?", userId);
    }

    Analysis newAnalysis() {
        Analysis saved = analysisRepository.save(Analysis.create(versionId, typeCode, "extract-v1/judge-v1", OffsetDateTime.now()));
        analysisIds.add(saved.getAnalysisId());
        return saved;
    }

    <T> T inTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    @Test
    void 엔티티_8개를_저장하고_다시_읽는다() {
        Long analysisId = newAnalysis().getAnalysisId();

        // analysis: JSONB 칸 (배열, 원문 JSON 문자열)
        jdbc.update("update analysis set partial_pages = '[2, 3]'::jsonb,"
                + " extraction_json = '{\"clauses\": []}'::jsonb, questions_json = '[\"시급을 고쳐 주실 수 있나요?\"]'::jsonb"
                + " where analysis_id = ?", analysisId);
        Analysis analysis = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(analysis.getStatus()).isEqualTo(AnalysisStatus.ANALYZING);
        assertThat(analysis.getProgressStep()).isEqualTo(AnalysisStep.READING_TEXT);
        assertThat(analysis.getPartialPages()).containsExactly(2, 3);
        assertThat(analysis.getExtractionJson()).contains("\"clauses\"");
        assertThat(analysis.getQuestionsJson()).containsExactly("시급을 고쳐 주실 수 있나요?");
        assertThat(analysis.getStartedAt()).isNotNull();

        // analysis_chunk
        Long chunkId = chunkRepository.save(AnalysisChunk.create(analysisId, 1, 1, 3)).getChunkId();
        AnalysisChunk chunk = chunkRepository.findById(chunkId).orElseThrow();
        assertThat(chunk.getStatus()).isEqualTo(ChunkStatus.PENDING);
        assertThat(chunk.getPageTo()).isEqualTo(3);

        // analysis_clause: verdict·excluded 기본값을 create() 가 채움, JSONB(law_hints, search_keywords)
        AnalysisClause c1 = clauseRepository.save(AnalysisClause.create(analysisId, "c1", "제7조", "교육비 반환",
                "근로자가 1년 이내 퇴사하면 교육비 30만 원을 반환한다.", "교육비 반환", 2, "2쪽 특약", true,
                "퇴사하면 미리 정한 금액을 물어냄", tagCode,
                List.of(new LawHint("근로기준법", "제20조")), List.of("위약금", "교육비")));
        AnalysisClause c2 = clauseRepository.save(AnalysisClause.create(analysisId, "c2", "제1조", null, null, null,
                1, null, false, null, null, null, null));
        AnalysisClause readC1 = clauseRepository.findById(c1.getClauseId()).orElseThrow();
        assertThat(readC1.getVerdict()).isEqualTo(ClauseVerdict.JUDGING);
        assertThat(readC1.isExcluded()).isFalse();
        assertThat(readC1.getLawHints()).containsExactly(new LawHint("근로기준법", "제20조"));
        assertThat(readC1.getSearchKeywords()).containsExactly("위약금", "교육비");
        assertThat(readC1.getIssueTagCode()).isEqualTo(tagCode);
        assertThat(text("select verdict from analysis_clause where clause_id = ?", c1.getClauseId())).isEqualTo("JUDGING");
        assertThat(text("select jsonb_typeof(law_hints) from analysis_clause where clause_id = ?", c1.getClauseId()))
                .isEqualTo("array");
        assertThat(text("select law_hints->0->>'law' from analysis_clause where clause_id = ?", c1.getClauseId()))
                .isEqualTo("근로기준법");
        assertThat(clauseRepository.findById(c2.getClauseId()).orElseThrow().getVerdict()).isEqualTo(ClauseVerdict.UNREADABLE);

        // comparison_json, ask_counterpart_json: 판정 단계 메서드가 아직 없어 JDBC 로 넣고 엔티티로 읽는다
        jdbc.update("update analysis_clause set comparison_json = '{\"leftLabel\":\"계약서 시급\",\"leftValue\":\"10,000원\","
                + "\"operator\":\"<\",\"rightLabel\":\"2026년 최저임금\",\"rightValue\":\"10,320원\"}'::jsonb,"
                + " ask_counterpart_json = '[\"교육비 반환 조항은 빼 주실 수 있나요?\"]'::jsonb where clause_id = ?",
                c1.getClauseId());
        AnalysisClause judged = clauseRepository.findById(c1.getClauseId()).orElseThrow();
        assertThat(judged.getComparisonJson())
                .isEqualTo(new NumberComparison("계약서 시급", "10,000원", "<", "2026년 최저임금", "10,320원"));
        assertThat(judged.getAskCounterpartJson()).containsExactly("교육비 반환 조항은 빼 주실 수 있나요?");

        // analysis_key_term: value_json(JSONB 객체)과 clause_id
        Long termId = keyTermRepository.save(AnalysisKeyTerm.create(analysisId, c1.getClauseId(), "임금", "시급 10,000원",
                Map.of("type", "HOURLY", "amount", 10000), KeyTermState.NEEDS_CHECK, KeyTermSource.WRITTEN,
                "1쪽 제4조", 1)).getKeyTermId();
        AnalysisKeyTerm term = keyTermRepository.findById(termId).orElseThrow();
        assertThat(term.getValueJson()).containsEntry("type", "HOURLY").containsEntry("amount", 10000);
        assertThat(term.getClauseId()).isEqualTo(c1.getClauseId());
        assertThat(text("select jsonb_typeof(value_json) from analysis_key_term where key_term_id = ?", termId))
                .isEqualTo("object");

        // analysis_clause_ref (해석 메모 근거)
        Long refId = refRepository.save(AnalysisClauseRef.create(c1.getClauseId(), "G1", null, guidanceId,
                RefFoundBy.TAG_MAP, false, "교육비 반환 해석 메모", "미리 정한 금액을 퇴사만으로 물게 하면 위반 소지")).getRefId();
        AnalysisClauseRef ref = refRepository.findById(refId).orElseThrow();
        assertThat(ref.getGuidanceId()).isEqualTo(guidanceId);
        assertThat(ref.getLawArticleId()).isNull();
        assertThat(ref.getFoundBy()).isEqualTo(RefFoundBy.TAG_MAP);
        assertThat(refRepository.findByClauseIdOrderByRefIdAsc(c1.getClauseId())).hasSize(1);

        // analysis_clause_relation (복합 키)
        relationRepository.save(AnalysisClauseRelation.create(analysisId, c1.getClauseId(), c2.getClauseId()));
        AnalysisClauseRelation relation = relationRepository
                .findById(new AnalysisClauseRelationId(c1.getClauseId(), c2.getClauseId())).orElseThrow();
        assertThat(relation.getAnalysisId()).isEqualTo(analysisId);
        assertThat(relationRepository.findByClauseId(c1.getClauseId())).hasSize(1);

        // analysis_finding
        Long findingId = findingRepository.save(AnalysisFinding.create(analysisId, FindingKind.MISSING_ITEM, "휴일",
                "주휴일을 꼭 적어야 해요", null, null, 1)).getFindingId();
        AnalysisFinding finding = findingRepository.findById(findingId).orElseThrow();
        assertThat(finding.getKind()).isEqualTo(FindingKind.MISSING_ITEM);
        assertThat(finding.getDueDate()).isNull();

        // schedule_candidate
        Long candidateId = candidateRepository.save(ScheduleCandidate.create(analysisId, "CONTRACT_START",
                "첫 출근 (계약 시작일)", LocalDate.of(2026, 12, 1), CandidateSource.WRITTEN, "1쪽 제1조", 1, null, true))
                .getCandidateId();
        ScheduleCandidate candidate = candidateRepository.findById(candidateId).orElseThrow();
        assertThat(candidate.getDate()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(candidate.getSource()).isEqualTo(CandidateSource.WRITTEN);
        assertThat(candidate.isDefaultSelected()).isTrue();
    }

    @Test
    void 조건부_상태_전환은_기대한_상태일_때만_1행을_바꾼다() {
        Long first = newAnalysis().getAnalysisId();

        assertThat(inTx(() -> analysisRepository.markCompleted(first, AnalysisStatus.ANALYZING, OffsetDateTime.now())))
                .isEqualTo(1);
        // 이미 COMPLETED: 다시 완료하거나 늦게 실패로 덮어쓰지 못한다
        assertThat(inTx(() -> analysisRepository.markCompleted(first, AnalysisStatus.ANALYZING, OffsetDateTime.now())))
                .isZero();
        assertThat(inTx(() -> analysisRepository.markFailed(first, AnalysisStatus.ANALYZING,
                AnalysisFailReason.TEMPORARY_ERROR, null, null, OffsetDateTime.now()))).isZero();
        Analysis completed = analysisRepository.findById(first).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(completed.getProgressStep()).isEqualTo(AnalysisStep.DONE);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getFailReason()).isNull();

        // 같은 버전의 두 번째 분석 (ANALYZING 은 버전당 하나라 첫 분석이 끝난 뒤 만든다)
        Long second = newAnalysis().getAnalysisId();
        assertThat(inTx(() -> analysisRepository.markFailed(second, AnalysisStatus.ANALYZING,
                AnalysisFailReason.PAGE_UNREADABLE, 2, null, OffsetDateTime.now()))).isEqualTo(1);
        assertThat(inTx(() -> analysisRepository.markCompleted(second, AnalysisStatus.ANALYZING, OffsetDateTime.now())))
                .isZero();
        Analysis failed = analysisRepository.findById(second).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.getFailReason()).isEqualTo(AnalysisFailReason.PAGE_UNREADABLE);
        assertThat(failed.getFailPageNo()).isEqualTo(2);
        assertThat(failed.getProgressStep()).isEqualTo(AnalysisStep.READING_TEXT);
        assertThat(failed.getCompletedAt()).isNotNull();
    }

    @Test
    void B가_부르는_조회는_완료_분석만_판정중과_제외_조항을_빼고_준다() {
        Long analysisId = newAnalysis().getAnalysisId();

        // 분석 중: 상태 재료는 분석 중, 완료 전용 조회는 비어 있음
        VersionAnalysisStateInfo analyzing = analysisService.getVersionState(versionId);
        assertThat(analyzing.hasAnalyzing()).isTrue();
        assertThat(analyzing.latestAnalysis().analysisId()).isEqualTo(analysisId);
        assertThat(analyzing.latestAnalysis().status()).isEqualTo("ANALYZING");
        assertThat(analyzing.latestAnalysis().documentTypeCode()).isEqualTo(typeCode);

        AnalysisChunk done = chunkRepository.save(AnalysisChunk.create(analysisId, 1, 1, 2));
        chunkRepository.save(AnalysisChunk.create(analysisId, 2, 3, 3));
        jdbc.update("update analysis_chunk set status = 'DONE' where chunk_id = ?", done.getChunkId());
        AnalysisClause needsCheck = clauseRepository.save(AnalysisClause.create(analysisId, "c1", "제4조", "임금",
                "시급 10,000원", "시급", 1, null, true, "최저임금 미달", null, null, null));
        AnalysisClause unreadable = clauseRepository.save(AnalysisClause.create(analysisId, "c2", "제5조", null, null,
                null, 2, null, false, null, null, null, null));
        AnalysisClause judging = clauseRepository.save(AnalysisClause.create(analysisId, "c3", "제6조", "휴일",
                "휴일은 ...", null, 2, null, true, null, null, null, null));
        AnalysisClause excluded = clauseRepository.save(AnalysisClause.create(analysisId, "c4", "제9조", "없는 조항",
                "지어낸 원문", null, 3, null, true, null, null, null, null));
        // 판정·검증 단계 메서드가 아직 없어 판정 결과는 JDBC 로 넣는다
        jdbc.update("update analysis_clause set verdict = 'NEEDS_CHECK' where clause_id = ?", needsCheck.getClauseId());
        jdbc.update("update analysis_clause set verdict = 'UNCERTAIN', excluded = true where clause_id = ?",
                excluded.getClauseId());
        keyTermRepository.save(AnalysisKeyTerm.create(analysisId, needsCheck.getClauseId(), "임금", "시급 10,000원",
                Map.of("type", "HOURLY", "amount", 10000), KeyTermState.NEEDS_CHECK, KeyTermSource.WRITTEN, null, 1));
        findingRepository.save(AnalysisFinding.create(analysisId, FindingKind.MISSING_ITEM, "휴일", "주휴일을 꼭 적어야 해요",
                null, null, 1));
        findingRepository.save(AnalysisFinding.create(analysisId, FindingKind.DEADLINE, "신고", "30일 안에 신고",
                null, null, 2));
        candidateRepository.save(ScheduleCandidate.create(analysisId, "CONTRACT_START", "첫 출근",
                LocalDate.of(2026, 12, 1), CandidateSource.WRITTEN, "1쪽 제1조", 1, null, true));
        candidateRepository.save(ScheduleCandidate.create(analysisId, "RENEWAL_NOTICE", "갱신 통보 기한",
                null, CandidateSource.REVIEW, null, null, null, false));

        // 제외된 조항은 판정 중이어도 완료를 막지 않는다
        AnalysisClause excludedJudging = clauseRepository.save(AnalysisClause.create(analysisId, "c5", "제10조", null,
                "지어낸 원문 2", null, 3, null, true, null, null, null, null));
        jdbc.update("update analysis_clause set excluded = true where clause_id = ?", excludedJudging.getClauseId());

        assertThat(analysisService.getSummary(analysisId)).isEmpty();
        assertThat(analysisService.getScheduleCandidates(analysisId)).isEmpty();
        assertThat(analysisService.getComparisonSource(analysisId)).isEmpty();

        // 제외되지 않은 판정 중(c3) 조항이 남아 있으면 완료로 바꾸지 않는다
        assertThat(inTx(() -> analysisRepository.markCompleted(analysisId, AnalysisStatus.ANALYZING, OffsetDateTime.now())))
                .isZero();
        assertThat(analysisRepository.findById(analysisId).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.ANALYZING);
        jdbc.update("update analysis_clause set verdict = 'OK' where clause_id = ?", judging.getClauseId());
        assertThat(inTx(() -> analysisRepository.markCompleted(analysisId, AnalysisStatus.ANALYZING, OffsetDateTime.now())))
                .isEqualTo(1);
        // 완료 뒤 판정 중 조항이 섞여 있어도 조회에는 나오지 않는지 확인하려고 되돌린다 (방어 확인용)
        jdbc.update("update analysis_clause set verdict = 'JUDGING' where clause_id = ?", judging.getClauseId());

        VersionAnalysisStateInfo completedState = analysisService.getVersionState(versionId);
        assertThat(completedState.hasAnalyzing()).isFalse();
        assertThat(completedState.latestAnalysis().status()).isEqualTo("COMPLETED");

        AnalysisSummaryInfo summary = analysisService.getSummary(analysisId).orElseThrow();
        assertThat(summary.analysisId()).isEqualTo(analysisId);
        assertThat(summary.completedAt()).isNotNull();
        assertThat(summary.title()).isNull();
        assertThat(summary.needsCheckCount()).isEqualTo(1); // 제외된 UNCERTAIN 은 세지 않음
        assertThat(summary.missingCount()).isEqualTo(1);    // DEADLINE 은 세지 않음
        assertThat(summary.scheduleCandidateCount()).isEqualTo(2);

        List<ScheduleCandidateInfo> candidates = analysisService.getScheduleCandidates(analysisId);
        assertThat(candidates).extracting(ScheduleCandidateInfo::dateKind).containsExactly("CONTRACT_START", "RENEWAL_NOTICE");
        assertThat(candidates.get(1).date()).isNull();
        assertThat(candidates.get(1).source()).isEqualTo("REVIEW");

        ComparisonSourceInfo source = analysisService.getComparisonSource(analysisId).orElseThrow();
        assertThat(source.documentTypeCode()).isEqualTo(typeCode);
        assertThat(source.pageCount()).isEqualTo(3);
        assertThat(source.readPages()).containsExactly(1, 2);
        assertThat(source.partialPages()).isEmpty();
        assertThat(source.keyTerms()).containsExactly(new ComparisonSourceInfo.KeyTerm("임금", "시급 10,000원",
                Map.of("type", "HOURLY", "amount", 10000), "NEEDS_CHECK"));
        assertThat(source.clauses()).extracting(ComparisonSourceInfo.Clause::clauseId)
                .containsExactly(needsCheck.getClauseId(), unreadable.getClauseId())
                .doesNotContain(judging.getClauseId(), excluded.getClauseId(), excludedJudging.getClauseId());
        assertThat(source.clauses().get(1).originalText()).isNull();
        assertThat(source.clauses().get(1).verdict()).isEqualTo("UNREADABLE");
    }

    @Test
    void clearSimilarContract는_비슷한_계약서를_지우고_미리_읽은_엔티티의_변경을_지킨다() {
        Long analysisId = newAnalysis().getAnalysisId();
        jdbc.update("update analysis set similar_contract_id = ? where analysis_id = ?", contractId, analysisId);
        OffsetDateTime changed = OffsetDateTime.parse("2030-01-01T00:00:00Z");

        inTx(() -> {
            // B 의 확정 트랜잭션처럼: 엔티티를 먼저 읽고, A6 를 부른 뒤, 읽어 둔 엔티티를 바꾼다
            Analysis preloaded = analysisRepository.findById(analysisId).orElseThrow();
            analysisService.clearSimilarContract(versionId);
            // 변경 메서드가 아직 없어 필드를 직접 바꾼다. 영속성 컨텍스트가 비워졌다면 이 변경은 저장되지 않는다
            ReflectionTestUtils.setField(preloaded, "heartbeatAt", changed);
            return null;
        });

        assertThat(jdbc.queryForObject("select similar_contract_id from analysis where analysis_id = ?", Long.class,
                analysisId)).isNull();
        assertThat(analysisRepository.findById(analysisId).orElseThrow().getHeartbeatAt().toInstant())
                .isEqualTo(changed.toInstant());
    }

    @Test
    void A6_A7은_트랜잭션_없이_부르면_막힌다() {
        assertThatThrownBy(() -> analysisService.clearSimilarContract(versionId))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> chatService.moveContract(contractId, contractId))
                .isInstanceOf(IllegalTransactionStateException.class);
        // 트랜잭션 안에서는 통과 (A7 은 지금 빈 구현)
        inTx(() -> {
            chatService.moveContract(contractId, contractId);
            return null;
        });
    }

    @Test
    void getVersionState는_가장_최근_분석을_고르고_시작_시각이_같으면_ID가_큰_것() {
        Long first = newAnalysis().getAnalysisId();
        inTx(() -> analysisRepository.markFailed(first, AnalysisStatus.ANALYZING, AnalysisFailReason.TEMPORARY_ERROR,
                null, null, OffsetDateTime.now()));
        Long second = newAnalysis().getAnalysisId();

        // 시작 시각이 같으면 ID 가 큰 두 번째
        jdbc.update("update analysis set started_at = '2026-11-17 10:00:00+09' where analysis_id in (?, ?)", first, second);
        VersionAnalysisStateInfo tie = analysisService.getVersionState(versionId);
        assertThat(tie.hasAnalyzing()).isTrue();
        assertThat(tie.latestAnalysis().analysisId()).isEqualTo(second);

        // 첫 번째가 더 늦게 시작했으면 첫 번째
        jdbc.update("update analysis set started_at = '2026-11-17 11:00:00+09' where analysis_id = ?", first);
        VersionAnalysisStateInfo later = analysisService.getVersionState(versionId);
        assertThat(later.latestAnalysis().analysisId()).isEqualTo(first);
        assertThat(later.latestAnalysis().status()).isEqualTo("FAILED");
    }
}
