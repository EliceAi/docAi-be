package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContentPart;
import com.team.docai.domain.analysis.client.GptFailureReason;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AiCallLogRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.judge.JudgeWriter.ClauseJudgment;
import com.team.docai.domain.analysis.service.rule.NeedMoreEvidence;
import com.team.docai.domain.law.service.LawSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 2차 단계 (GPT·DB·저장은 가짜): 의심 조항 0개면 생략, 나눠 부르기·실패 묶음 1회 재시도, needMore 1회 상한, 판단 누락 */
class JudgeStepHandlerTest {

    static final UUID USER = UUID.randomUUID();
    static final AnalysisRunContext CTX = new AnalysisRunContext(88L, USER, 12L, 30L, "EMPLOYMENT");
    static final Pattern CLAUSE_HEAD = Pattern.compile("### (c\\d+) ");

    AnalysisRepository analysisRepository = mock(AnalysisRepository.class);
    AnalysisClauseRepository clauseRepository = mock(AnalysisClauseRepository.class);
    AnalysisClauseRefRepository refRepository = mock(AnalysisClauseRefRepository.class);
    AnalysisClauseRelationRepository relationRepository = mock(AnalysisClauseRelationRepository.class);
    AnalysisFindingRepository findingRepository = mock(AnalysisFindingRepository.class);
    AiCallLogRepository aiCallLogRepository = mock(AiCallLogRepository.class);
    LawSearchService lawSearchService = mock(LawSearchService.class);
    NeedMoreEvidence needMoreEvidence = mock(NeedMoreEvidence.class);
    GptClient gptClient = mock(GptClient.class);
    JudgeWriter judgeWriter = mock(JudgeWriter.class);
    ExecutorService pool;
    JudgeStepHandler step;

    /** 조항 ID → 근거 (needMore 로 늘어날 수 있음) */
    final Map<Long, List<AnalysisClauseRef>> refs = new ConcurrentHashMap<>();
    final List<String> purposes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(3);
        step = new JudgeStepHandler(analysisRepository, clauseRepository, refRepository, relationRepository,
                findingRepository, aiCallLogRepository, lawSearchService, needMoreEvidence, gptClient, judgeWriter, pool);
        Analysis analysis = Analysis.create(30L, "EMPLOYMENT", "test", OffsetDateTime.now());
        ReflectionTestUtils.setField(analysis, "analysisId", 88L);
        when(analysisRepository.findById(88L)).thenReturn(java.util.Optional.of(analysis));
        when(relationRepository.findByAnalysisId(88L)).thenReturn(List.of());
        when(findingRepository.findByAnalysisIdOrderBySortOrderAscFindingIdAsc(88L)).thenReturn(List.of());
        when(lawSearchService.findLawIds("EMPLOYMENT")).thenReturn(List.of(1L));
        when(judgeWriter.saveJudgments(anyLong(), anyList(), anyList(), anyList())).thenReturn(true);
        when(refRepository.findByClauseIdInOrderByClauseIdAscRefIdAsc(any())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            List<AnalysisClauseRef> out = new ArrayList<>();
            ids.forEach(id -> out.addAll(refs.getOrDefault(id, List.of())));
            return out;
        });
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    static AnalysisClause clause(long id, String key, boolean issue) {
        AnalysisClause c = AnalysisClause.create(88L, key, "제" + id + "조", "제목" + id, "원문 " + key, "한 줄 " + key,
                1, null, issue, issue ? "의심" : null, null, List.of(), List.of());
        ReflectionTestUtils.setField(c, "clauseId", id);
        return c;
    }

    static AnalysisClauseRef lawRef(long clauseId, String label, RefFoundBy foundBy) {
        return AnalysisClauseRef.create(clauseId, label, 500L + clauseId, null, foundBy, false, "근로기준법 제20조", "내용");
    }

    void givenClauses(AnalysisClause... clauses) {
        when(clauseRepository.findByAnalysisIdOrderByPageNoAscClauseIdAsc(88L)).thenReturn(List.of(clauses));
    }

    /** 요청에 든 조항 키 */
    static List<String> keysOf(GptRequest request) {
        String body = ((GptContentPart.Text) request.userContent().get(1)).text();
        Matcher m = CLAUSE_HEAD.matcher(body);
        List<String> keys = new ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    static JudgeResponse.Judgment needsCheck(String key, String label) {
        return new JudgeResponse.Judgment(key, "NEEDS_CHECK", "제목 " + key, "쉬운 설명", "확인이 필요해요.",
                List.of(label), List.of(), List.of());
    }

    /** 보낸 조항마다 L1 을 인용한 NEEDS_CHECK */
    static JudgeResponse allNeedsCheck(GptRequest request) {
        return new JudgeResponse(keysOf(request).stream().map(k -> needsCheck(k, "L1")).toList(), List.of(),
                List.of("서명 전에 물어볼 것?"));
    }

    void answer(Function<GptRequest, JudgeResponse> judge, Function<GptRequest, JudgeResponse> more) {
        when(gptClient.call(any(), any(), eq(JudgeResponse.class), any())).thenAnswer(inv -> {
            GptPurpose purpose = inv.getArgument(0);
            purposes.add(purpose.name());
            GptRequest request = inv.getArgument(1);
            return purpose == GptPurpose.JUDGE ? judge.apply(request) : more.apply(request);
        });
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ClauseJudgment>> judgmentsCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Map<String, Object>>> logCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void 진행_단계는_SUMMARIZING() {
        assertThat(step.progressStep()).isEqualTo(AnalysisStep.SUMMARIZING);
    }

    @Test
    void 의심_조항이_없으면_2차를_부르지_않고_판정_없이_저장한다() {
        givenClauses(clause(1, "c1", false), clause(2, "c2", false));

        step.execute(CTX);

        verify(gptClient, never()).call(any(), any(), any(), any());
        ArgumentCaptor<List<Map<String, Object>>> log = logCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), eq(List.of()), eq(List.of()), log.capture());
        assertThat(log.getValue()).extracting(m -> m.get("type")).containsExactly("JUDGE_SKIPPED");
    }

    @Test
    void 제외된_조항과_이미_판정된_조항은_의심_조항으로_보지_않는다() {
        AnalysisClause excluded = clause(1, "c1", true);
        ReflectionTestUtils.setField(excluded, "excluded", true);
        AnalysisClause judged = clause(2, "c2", true);
        ReflectionTestUtils.setField(judged, "verdict", ClauseVerdict.NEEDS_CHECK);
        givenClauses(excluded, judged);

        step.execute(CTX);

        verify(gptClient, never()).call(any(), any(), any(), any());
    }

    @Test
    void 의심_조항이_많으면_나눠서_부르고_실패한_묶음만_한_번_더_불러_모두_저장한다() {
        List<AnalysisClause> clauses = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            clauses.add(clause(i, "c" + i, true));
            refs.put((long) i, List.of(lawRef(i, "L1", RefFoundBy.TAG_MAP)));
        }
        givenClauses(clauses.toArray(new AnalysisClause[0]));
        AtomicInteger secondBatchCalls = new AtomicInteger();
        answer(request -> {
            if (keysOf(request).equals(List.of("c7")) && secondBatchCalls.incrementAndGet() == 1) {
                throw new GptCallException(GptFailureReason.SCHEMA_MISMATCH, "깨짐", 1);
            }
            return allNeedsCheck(request);
        }, r -> null);

        step.execute(CTX);

        assertThat(purposes).hasSize(3).containsOnly("JUDGE");
        assertThat(secondBatchCalls).hasValue(2);
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), eq(List.of("서명 전에 물어볼 것?")), anyList());
        assertThat(saved.getValue()).hasSize(7);
        assertThat(saved.getValue()).extracting(ClauseJudgment::verdict).containsOnly(ClauseVerdict.NEEDS_CHECK);
        assertThat(saved.getValue()).extracting(ClauseJudgment::priority).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(saved.getValue()).extracting(ClauseJudgment::citedLabels).containsOnly(List.of("L1"));
    }

    @Test
    void 실패한_묶음이_다시_실패하면_예외로_끝나고_판정을_저장하지_않는다() {
        List<AnalysisClause> clauses = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            clauses.add(clause(i, "c" + i, true));
        }
        givenClauses(clauses.toArray(new AnalysisClause[0]));
        answer(request -> {
            if (keysOf(request).contains("c7")) {
                throw new GptCallException(GptFailureReason.TIMEOUT, "시간 초과", 2);
            }
            return allNeedsCheck(request);
        }, r -> null);

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(GptCallException.class);

        // 첫 묶음 1번 + 실패한 묶음 2번 (재시도 1회)
        assertThat(purposes).hasSize(3);
        verify(judgeWriter, never()).saveJudgments(anyLong(), anyList(), anyList(), anyList());
    }

    @Test
    void needMore는_한_번만_법령을_더_찾아_그_조항만_다시_판단하고_두_번째_needMore는_받지_않는다() {
        givenClauses(clause(1, "c1", false), clause(7, "c7", true), clause(9, "c9", true));
        refs.put(7L, List.of(lawRef(7, "L1", RefFoundBy.TAG_MAP)));
        refs.put(9L, List.of(lawRef(9, "L1", RefFoundBy.AI_HINT)));
        when(needMoreEvidence.collect(any(), eq(9L), eq(List.of("수습", "감액")), eq("감액 기준"), eq(List.of(1L))))
                .thenAnswer(inv -> {
                    refs.put(9L, List.of(lawRef(9, "L1", RefFoundBy.AI_HINT), lawRef(9, "L2", RefFoundBy.NEED_MORE)));
                    return 1;
                });
        List<List<String>> moreKeys = new CopyOnWriteArrayList<>();
        answer(request -> new JudgeResponse(
                        List.of(needsCheck("c7", "L1"), new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, "부족",
                                List.of(), List.of(), List.of())),
                        List.of(new JudgeResponse.NeedMore("c9", "감액 기준", List.of("수습", "감액"))), List.of()),
                request -> {
                    moreKeys.add(keysOf(request));
                    assertThat(((GptContentPart.Text) request.userContent().get(1)).text())
                            .contains("이번이 마지막 판단", "[L2]");
                    return new JudgeResponse(List.of(needsCheck("c9", "L2")),
                            List.of(new JudgeResponse.NeedMore("c9", "더", List.of("더"))), List.of());
                });

        step.execute(CTX);

        assertThat(purposes).containsExactly("JUDGE", "JUDGE_MORE");
        assertThat(moreKeys).containsExactly(List.of("c9"));
        verify(needMoreEvidence, times(1)).collect(any(), anyLong(), anyList(), any(), anyList());
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        ArgumentCaptor<List<Map<String, Object>>> log = logCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), log.capture());
        ClauseJudgment c9 = saved.getValue().stream().filter(j -> j.clauseId() == 9L).findFirst().orElseThrow();
        assertThat(c9.verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
        assertThat(c9.citedLabels()).containsExactly("L2");
        assertThat(log.getValue()).extracting(m -> m.get("type")).contains("NEED_MORE_FOUND", "NEED_MORE_IGNORED");
    }

    @Test
    void 이미_추가_조회를_한_분석이면_needMore를_다시_들어주지_않는다() {
        givenClauses(clause(9, "c9", true));
        when(aiCallLogRepository.countCalls(88L, GptPurpose.JUDGE_MORE)).thenReturn(1L);
        answer(request -> new JudgeResponse(List.of(new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, null,
                        List.of(), List.of(), List.of())),
                List.of(new JudgeResponse.NeedMore("c9", "기준", List.of("감액"))), List.of()), r -> null);

        step.execute(CTX);

        assertThat(purposes).containsExactly("JUDGE");
        verify(needMoreEvidence, never()).collect(any(), anyLong(), anyList(), any(), anyList());
    }

    @Test
    void 추가_조회_호출이_실패하면_첫_판단을_그대로_쓴다() {
        givenClauses(clause(9, "c9", true));
        answer(request -> new JudgeResponse(List.of(new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, null,
                        List.of(), List.of(), List.of())),
                List.of(new JudgeResponse.NeedMore("c9", "기준", List.of("감액"))), List.of()),
                request -> {
                    throw new GptCallException(GptFailureReason.SERVER_ERROR, "5xx", 2);
                });

        step.execute(CTX);

        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), anyList());
        assertThat(saved.getValue()).extracting(ClauseJudgment::verdict).containsExactly(ClauseVerdict.UNCERTAIN);
    }

    @Test
    void 판단이_오지_않은_의심_조항이_있으면_판정_중으로_남기고_예외로_실패시킨다() {
        givenClauses(clause(7, "c7", true), clause(9, "c9", true));
        refs.put(7L, List.of(lawRef(7, "L1", RefFoundBy.TAG_MAP)));
        answer(request -> new JudgeResponse(List.of(needsCheck("c7", "L1"), needsCheck("c99", "L1")), List.of(),
                List.of()), r -> null);

        assertThatThrownBy(() -> step.execute(CTX)).isInstanceOf(IllegalStateException.class).hasMessageContaining("c9");

        // 처음 1번 + 빠진 c9 만 다시 1번
        assertThat(purposes).containsExactly("JUDGE", "JUDGE");
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), anyList());
        assertThat(saved.getValue()).extracting(ClauseJudgment::clauseId).containsExactly(7L);
    }

    @Test
    void 판단이_빠진_조항_키누락_판정값_이상은_그_조항만_JUDGE로_한_번_더_불러_채운다() {
        givenClauses(clause(7, "c7", true), clause(8, "c8", true), clause(9, "c9", true));
        refs.put(7L, List.of(lawRef(7, "L1", RefFoundBy.TAG_MAP)));
        refs.put(8L, List.of(lawRef(8, "L1", RefFoundBy.TAG_MAP)));
        refs.put(9L, List.of(lawRef(9, "L1", RefFoundBy.TAG_MAP)));
        List<List<String>> sentKeys = new CopyOnWriteArrayList<>();
        answer(request -> {
            List<String> keys = keysOf(request);
            sentKeys.add(keys);
            if (keys.size() == 3) {
                // c8: 판정 값 이상, c9: 빠짐
                return new JudgeResponse(List.of(needsCheck("c7", "L1"), new JudgeResponse.Judgment("c8", "MAYBE",
                        null, null, null, List.of(), List.of(), List.of())), List.of(), List.of());
            }
            return allNeedsCheck(request);
        }, r -> null);

        step.execute(CTX);

        assertThat(sentKeys).containsExactly(List.of("c7", "c8", "c9"), List.of("c8", "c9"));
        assertThat(purposes).containsExactly("JUDGE", "JUDGE");
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        ArgumentCaptor<List<Map<String, Object>>> log = logCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), log.capture());
        assertThat(saved.getValue()).extracting(ClauseJudgment::clauseId).containsExactlyInAnyOrder(7L, 8L, 9L);
        assertThat(log.getValue()).extracting(m -> m.get("type")).contains("VERDICT_INVALID", "JUDGMENT_MISSING",
                "JUDGMENT_RETRY");
    }

    @Test
    void 판단_누락_재호출은_추가_조회_횟수와_따로_센다() {
        givenClauses(clause(7, "c7", true), clause(9, "c9", true));
        refs.put(9L, List.of(lawRef(9, "L1", RefFoundBy.AI_HINT)));
        AtomicInteger judgeCalls = new AtomicInteger();
        answer(request -> {
            if (judgeCalls.incrementAndGet() == 1) {
                return new JudgeResponse(List.of(new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, null,
                        List.of(), List.of(), List.of())),
                        List.of(new JudgeResponse.NeedMore("c9", "기준", List.of("감액"))), List.of());
            }
            return new JudgeResponse(List.of(new JudgeResponse.Judgment("c7", "OK", null, null, null, List.of(),
                    List.of(), List.of())), List.of(), List.of());
        }, request -> new JudgeResponse(List.of(needsCheck("c9", "L1")), List.of(), List.of()));

        step.execute(CTX);

        assertThat(purposes).containsExactly("JUDGE", "JUDGE", "JUDGE_MORE");
    }

    @Test
    void 추가_조회는_공용_풀에서_돌고_빈_응답이면_첫_판단을_쓴다() {
        givenClauses(clause(9, "c9", true));
        List<String> moreThreads = new CopyOnWriteArrayList<>();
        Thread testThread = Thread.currentThread();
        answer(request -> new JudgeResponse(List.of(new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, null,
                        List.of(), List.of(), List.of())),
                List.of(new JudgeResponse.NeedMore("c9", "기준", List.of("감액"))), List.of()),
                request -> {
                    moreThreads.add(Thread.currentThread() == testThread ? "caller" : "pool");
                    return null;
                });

        step.execute(CTX);

        assertThat(moreThreads).containsExactly("pool");
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        ArgumentCaptor<List<Map<String, Object>>> log = logCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), log.capture());
        assertThat(saved.getValue()).extracting(ClauseJudgment::verdict).containsExactly(ClauseVerdict.UNCERTAIN);
        assertThat(log.getValue()).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("JUDGE_MORE_FAILED");
            assertThat((String) m.get("detail")).contains("EmptyJudgeResponseException");
        });
    }

    @Test
    void 추가_조회를_공용_풀이_거절하면_JUDGE_MORE_FAILED로_기록하고_첫_판단을_쓴다() {
        java.util.concurrent.atomic.AtomicBoolean full = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.Executor rejecting = task -> {
            if (full.get()) {
                throw new java.util.concurrent.RejectedExecutionException("가득 참");
            }
            pool.execute(task);
        };
        JudgeStepHandler rejectingStep = new JudgeStepHandler(analysisRepository, clauseRepository, refRepository,
                relationRepository, findingRepository, aiCallLogRepository, lawSearchService, needMoreEvidence,
                gptClient, judgeWriter, rejecting);
        givenClauses(clause(9, "c9", true));
        answer(request -> {
            full.set(true);   // 2차가 끝난 뒤 풀이 가득 참
            return new JudgeResponse(List.of(new JudgeResponse.Judgment("c9", "UNCERTAIN", "제목", null, null,
                    List.of(), List.of(), List.of())),
                    List.of(new JudgeResponse.NeedMore("c9", "기준", List.of("감액"))), List.of());
        }, request -> new JudgeResponse(List.of(needsCheck("c9", "L1")), List.of(), List.of()));

        rejectingStep.execute(CTX);

        assertThat(purposes).containsExactly("JUDGE");
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        ArgumentCaptor<List<Map<String, Object>>> log = logCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), log.capture());
        assertThat(saved.getValue()).extracting(ClauseJudgment::verdict).containsExactly(ClauseVerdict.UNCERTAIN);
        assertThat(log.getValue()).anySatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("JUDGE_MORE_FAILED");
            assertThat((String) m.get("detail")).contains("RejectedExecutionException");
        });
    }

    @Test
    void 판단_글의_내부_조항_키는_그_조항의_조_번호로_바꿔_저장한다() {
        givenClauses(clause(1, "c1", false), clause(7, "c7", true));
        refs.put(7L, List.of(lawRef(7, "L1", RefFoundBy.TAG_MAP)));
        answer(request -> new JudgeResponse(List.of(new JudgeResponse.Judgment("c7", "NEEDS_CHECK", "c7 확인",
                "쉬운 설명", "c1에 적힌 기간과 함께 보세요.", List.of("L1"), List.of("c1"), List.of())), List.of(),
                List.of()), r -> null);

        step.execute(CTX);

        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), anyList());
        assertThat(saved.getValue().get(0).headline()).isEqualTo("제7조 확인");
        assertThat(saved.getValue().get(0).whyCheck()).isEqualTo("제1조에 적힌 기간과 함께 보세요.");
        assertThat(saved.getValue().get(0).relatedIds()).containsExactly(1L);
    }

    @Test
    void 이미지는_보내지_않고_목차_근거_규칙_결과를_보낸다() {
        AnalysisClause wage = clause(4, "c4", true);
        ReflectionTestUtils.setField(wage, "basisType", com.team.docai.domain.analysis.entity.BasisType.RULE);
        ReflectionTestUtils.setField(wage, "headline", "시급이 2026년 최저임금보다 낮아요");
        ReflectionTestUtils.setField(wage, "whyCheck", "시간당 320원 낮아요.\n하루 근로시간이 9시간이에요.");
        givenClauses(clause(1, "c1", false), wage);
        refs.put(4L, List.of(lawRef(4, "L1", RefFoundBy.RULE)));
        List<GptRequest> requests = new CopyOnWriteArrayList<>();
        answer(request -> {
            requests.add(request);
            return new JudgeResponse(List.of(new JudgeResponse.Judgment("c4", "OK", null, null, null, List.of("L1"),
                    List.of(), List.of())), List.of(), List.of());
        }, r -> null);

        step.execute(CTX);

        GptRequest request = requests.get(0);
        assertThat(request.userContent()).allMatch(p -> p instanceof GptContentPart.Text);
        String head = ((GptContentPart.Text) request.userContent().get(0)).text();
        String body = ((GptContentPart.Text) request.userContent().get(1)).text();
        assertThat(head).contains("- c1 | 제1조 | 제목1 | 한 줄 c1", "- c4 |");
        assertThat(body).contains("규칙 결과로 고정 (NEEDS_CHECK): 시급이 2026년 최저임금보다 낮아요", "[L1] 근로기준법 제20조");
        // 규칙 둘이 한 조항에 붙으면 뒤 규칙은 why_check 에만 있어 그 내용도 보낸다 (한 줄로)
        assertThat(body).contains("규칙 결과 내용: 시간당 320원 낮아요. 하루 근로시간이 9시간이에요.\n");
        assertThat(request.systemPrompt()).isEqualTo(JudgePrompt.SYSTEM_PROMPT)
                .contains("clauseKey 를 쓰지 말고 조 번호");
        // 조항을 조 번호·제목으로 부를 수 있게 목차·판단 조항 머리에 함께 넣는다
        assertThat(body).contains("### c4 (제4조 제목4, 1쪽)");
        ArgumentCaptor<List<ClauseJudgment>> saved = judgmentsCaptor();
        verify(judgeWriter).saveJudgments(eq(88L), saved.capture(), anyList(), anyList());
        // 규칙 결과 우선: GPT 가 OK 라 해도 NEEDS_CHECK
        assertThat(saved.getValue().get(0).verdict()).isEqualTo(ClauseVerdict.NEEDS_CHECK);
    }
}
