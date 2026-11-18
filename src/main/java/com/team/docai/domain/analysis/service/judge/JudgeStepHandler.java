package com.team.docai.domain.analysis.service.judge;

import com.team.docai.domain.analysis.client.GptCallException;
import com.team.docai.domain.analysis.client.GptClient;
import com.team.docai.domain.analysis.client.GptContext;
import com.team.docai.domain.analysis.client.GptPurpose;
import com.team.docai.domain.analysis.client.GptRequest;
import com.team.docai.domain.analysis.dto.AnalysisRunContext;
import com.team.docai.domain.analysis.dto.JudgeResponse;
import com.team.docai.domain.analysis.entity.Analysis;
import com.team.docai.domain.analysis.entity.AnalysisClause;
import com.team.docai.domain.analysis.entity.AnalysisClauseRef;
import com.team.docai.domain.analysis.entity.AnalysisClauseRelation;
import com.team.docai.domain.analysis.entity.AnalysisFinding;
import com.team.docai.domain.analysis.entity.AnalysisStep;
import com.team.docai.domain.analysis.entity.BasisType;
import com.team.docai.domain.analysis.entity.ClauseVerdict;
import com.team.docai.domain.analysis.entity.FindingKind;
import com.team.docai.domain.analysis.entity.RefFoundBy;
import com.team.docai.domain.analysis.repository.AiCallLogRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRefRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRelationRepository;
import com.team.docai.domain.analysis.repository.AnalysisClauseRepository;
import com.team.docai.domain.analysis.repository.AnalysisFindingRepository;
import com.team.docai.domain.analysis.repository.AnalysisRepository;
import com.team.docai.domain.analysis.service.AnalysisStepHandler;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.Judged;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.NeedMoreRequest;
import com.team.docai.domain.analysis.service.judge.JudgeValidator.SentClause;
import com.team.docai.domain.analysis.service.judge.JudgeWriter.ClauseJudgment;
import com.team.docai.domain.analysis.service.rule.EvidenceStepHandler;
import com.team.docai.domain.analysis.service.rule.NeedMoreEvidence;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.config.AnalysisExtractConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 7단계 GPT 2차 · 7-1 추가 조회 · 8단계 검증 · 9단계 판정 저장. 진행 단계 SUMMARIZING.
 * <ol>
 *   <li>의심 조항(issue_found, 제외 아님, 판정 중)이 없으면 GPT 를 부르지 않고 의심 표시 없는 조항만 OK 로 저장한다.</li>
 *   <li>의심 조항을 {@link #BATCH_SIZE}개씩 나눠 동시에 부른다 (JUDGE, 이미지 없음). 보내는 것: 전체 목차, 판단할 조항 원문 +
 *       관련 조항 원문, 6단계 근거(L·G 라벨과 스냅샷), 규칙 결과. 실패한 묶음만 1회 다시 부르고, 그래도 실패하면 예외 →
 *       파이프라인이 FAILED · TEMPORARY_ERROR (판정 중 조항을 OK 로 채우지 않음).</li>
 *   <li>응답을 묶음마다 검증({@link JudgeValidator}): 보낸 조항이 아닌 키 버림, 준 ID 가 아닌 인용 지움, 조문 인용이 남지 않은
 *       NEEDS_CHECK 는 UNCERTAIN, 규칙 결과 우선, 단정 표현 지움, 길이 자름.</li>
 *   <li>호출은 성공했는데 판단이 빠진 의심 조항(키 누락, 판정 값 이상, 모르는 키로 옴)은 그 조항만 모아 JUDGE 로 1회 다시 부른다
 *       ({@link #MISSING_RETRIES}, 추가 조회 JUDGE_MORE 횟수와 따로 셈).</li>
 *   <li>needMore 가 있고 이 분석에서 추가 조회(JUDGE_MORE)를 한 적이 없으면 한 번만: 그 조항들에 6단계와 같은 방법으로
 *       조문을 더 붙이고({@link NeedMoreEvidence}, 새 ID 는 기존 다음 번호), 그 조항만 원문 + 기존 근거 + 새 근거로 한 번 더
 *       부른다. 그 응답의 needMore 는 받지 않는다. 추가 조회가 실패하면 첫 판단을 그대로 쓴다.</li>
 *   <li>저장({@link JudgeWriter#saveJudgments}): 판정·설명·물어볼 것·관련 조항·인용(cited), 우선순서, 의심 표시 없는 조항 OK,
 *       서명 전 질문, 검증 기록.</li>
 *   <li>다시 불러도 판단이 오지 않은 의심 조항이 있으면 판정 중으로 남기고 예외 → FAILED · TEMPORARY_ERROR
 *       (COMPLETED 로 가지 않음).</li>
 * </ol>
 * <b>구현하지 않음 (2026-11-18 사용자 결정): 아키텍처 8단계 "원문이 실제 추출 텍스트에 있는가 → 없으면 excluded = true".</b>
 * 1차 GPT 출력(조항 원문) 말고는 대조할 추출 텍스트(OCR 결과 등)가 없어, 1차 원문을 1차 원문과 대조하는 셈이라 지어낸 조항을
 * 가려낼 수 없다. 2부 과제: 별도 OCR 텍스트(또는 1차 출력과 독립된 읽기 결과)를 저장하게 되면 여기서 대조해 excluded 를 표시한다.
 * 그때까지 excluded 조항은 생기지 않는다 (조회·완료 검사는 이미 excluded 를 거른다).
 * <p>
 * 트랜잭션 없이 돈다 (GPT 호출). 계약서 행 잠금을 잡지 않는다.
 * 모든 2차 호출(JUDGE, JUDGE_MORE)은 1차·2차 공용 풀(analysisExtractExecutor)로 보낸다: OpenAI 로 나가는 동시 호출 수를
 * 서버 전체에서 한 곳으로 제한하려고. 2차 작업은 그 풀에 다른 작업을 넣지 않으므로 서로 기다리다 막히지 않는다.
 */
@Slf4j
@Component
@Order(JudgeStepHandler.ORDER)
public class JudgeStepHandler implements AnalysisStepHandler {

    /** 근거 찾기({@link EvidenceStepHandler#ORDER}) 다음, 완료 전 정리({@link FinishStepHandler#ORDER}) 앞. */
    public static final int ORDER = 700;
    /** 한 번에 판단할 의심 조항 수. 넘으면 나눠서 동시에 부른다 */
    static final int BATCH_SIZE = 6;
    /** 실패한 묶음을 다시 부르는 횟수 (아키텍처: 실패한 부분만 1회) */
    static final int BATCH_RETRIES = 1;
    /** 호출은 성공했지만 판단이 빠진 조항만 다시 부르는 횟수 */
    static final int MISSING_RETRIES = 1;

    /** 2차 응답이 비어 있음 (GptClient 가 null 을 돌려준 경우). 내용 오류로 보고 실패 묶음과 같이 다룬다 */
    static final class EmptyJudgeResponseException extends RuntimeException {
        EmptyJudgeResponseException(GptPurpose purpose) {
            super(purpose + " 응답이 비어 있음");
        }
    }

    private final AnalysisRepository analysisRepository;
    private final AnalysisClauseRepository clauseRepository;
    private final AnalysisClauseRefRepository refRepository;
    private final AnalysisClauseRelationRepository relationRepository;
    private final AnalysisFindingRepository findingRepository;
    private final AiCallLogRepository aiCallLogRepository;
    private final LawSearchService lawSearchService;
    private final NeedMoreEvidence needMoreEvidence;
    private final GptClient gptClient;
    private final JudgeWriter judgeWriter;
    private final Executor executor;

    public JudgeStepHandler(AnalysisRepository analysisRepository, AnalysisClauseRepository clauseRepository,
                            AnalysisClauseRefRepository refRepository,
                            AnalysisClauseRelationRepository relationRepository,
                            AnalysisFindingRepository findingRepository, AiCallLogRepository aiCallLogRepository,
                            LawSearchService lawSearchService, NeedMoreEvidence needMoreEvidence, GptClient gptClient,
                            JudgeWriter judgeWriter,
                            @Qualifier(AnalysisExtractConfig.EXTRACT_EXECUTOR) Executor executor) {
        this.analysisRepository = analysisRepository;
        this.clauseRepository = clauseRepository;
        this.refRepository = refRepository;
        this.relationRepository = relationRepository;
        this.findingRepository = findingRepository;
        this.aiCallLogRepository = aiCallLogRepository;
        this.lawSearchService = lawSearchService;
        this.needMoreEvidence = needMoreEvidence;
        this.gptClient = gptClient;
        this.judgeWriter = judgeWriter;
        this.executor = executor;
    }

    @Override
    public AnalysisStep progressStep() {
        return AnalysisStep.SUMMARIZING;
    }

    /** 이 실행 안에서 쓰는 조항·근거 묶음 */
    private record Material(Analysis analysis, List<AnalysisClause> visible, Map<String, AnalysisClause> byKey,
                            Map<Long, AnalysisClause> byId, Map<Long, List<Long>> relatedIds,
                            List<JudgePrompt.IndexEntry> index, List<String> missingItems) {
        /** 조항 키 → 화면에 쓸 이름 (글 속 내부 키를 바꿀 때) */
        Map<String, String> clauseNames() {
            Map<String, String> names = new HashMap<>();
            byKey.forEach((k, c) -> names.put(k, JudgeValidator.displayName(c.getArticleNo(), c.getTitle())));
            return names;
        }
    }

    @Override
    public void execute(AnalysisRunContext ctx) {
        Long analysisId = ctx.analysisId();
        Analysis analysis = analysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalStateException("분석이 없음: " + analysisId));
        List<AnalysisClause> visible = clauseRepository.findByAnalysisIdOrderByPageNoAscClauseIdAsc(analysisId).stream()
                .filter(c -> !c.isExcluded())
                .toList();
        List<AnalysisClause> suspicious = visible.stream()
                .filter(c -> c.isIssueFound() && c.getVerdict() == ClauseVerdict.JUDGING)
                .toList();
        List<Map<String, Object>> vlog = new ArrayList<>();
        if (suspicious.isEmpty()) {
            vlog.add(JudgeValidator.entry(JudgeValidator.STAGE_JUDGE, "JUDGE_SKIPPED", "analysis", "의심 조항 없음"));
            save(analysisId, List.of(), List.of(), vlog);
            log.info("2차 생략 (의심 조항 없음): analysisId={}", analysisId);
            return;
        }
        Material m = material(analysis, visible);
        GptContext gptContext = GptContext.analysis(ctx.userId(), analysisId);

        // 7. 2차: 나눠서 동시에, 실패한 묶음만 1회 더
        List<List<AnalysisClause>> batches = partition(suspicious, BATCH_SIZE);
        Map<Long, List<AnalysisClauseRef>> refs = loadRefs(suspicious);
        List<Call> calls = batches.stream().map(b -> call(m, b, refs, false)).toList();
        List<JudgeResponse> responses = runAll(GptPurpose.JUDGE, calls, gptContext, analysisId);

        // 8. 검증 (묶음마다 보낸 조항 기준)
        Map<String, Judged> judged = new LinkedHashMap<>();
        List<NeedMoreRequest> needMore = new ArrayList<>();
        Set<String> questions = new LinkedHashSet<>();
        collect(JudgeValidator.STAGE_JUDGE, calls, responses, m, judged, needMore, questions, vlog);

        // 판단이 빠진 조항만 1회 다시 (JUDGE, 추가 조회와 따로 셈)
        for (int retry = 0; retry < MISSING_RETRIES; retry++) {
            List<AnalysisClause> lacking = suspicious.stream().filter(c -> !judged.containsKey(c.getClauseKey())).toList();
            if (lacking.isEmpty()) {
                break;
            }
            vlog.add(JudgeValidator.entry(JudgeValidator.STAGE_JUDGE, "JUDGMENT_RETRY", "analysis",
                    "판단이 빠진 조항 " + lacking.stream().map(AnalysisClause::getClauseKey).toList() + " 다시 부름"));
            log.info("2차 판단이 빠진 조항만 다시 부름: analysisId={}, clauses={}", analysisId,
                    lacking.stream().map(AnalysisClause::getClauseKey).toList());
            List<Call> retryCalls = partition(lacking, BATCH_SIZE).stream().map(b -> call(m, b, refs, false)).toList();
            List<JudgeResponse> retryResponses = runAll(GptPurpose.JUDGE, retryCalls, gptContext, analysisId);
            collect(JudgeValidator.STAGE_JUDGE, retryCalls, retryResponses, m, judged, needMore, questions, vlog);
        }

        // 7-1. 추가 조회 1회
        if (!needMore.isEmpty()) {
            judgeMore(ctx, m, needMore, judged, questions, vlog, gptContext);
        }

        // 9. 저장
        List<ClauseJudgment> toSave = toClauseJudgments(judged, m);
        List<String> questionList = questions.stream().limit(JudgeValidator.MAX_QUESTIONS).toList();
        if (!save(analysisId, toSave, questionList, vlog)) {
            return;
        }
        List<String> missing = suspicious.stream().map(AnalysisClause::getClauseKey)
                .filter(k -> !judged.containsKey(k)).toList();
        log.info("2차 완료: analysisId={}, suspicious={}, batches={}, judged={}, needMore={}, logs={}",
                analysisId, suspicious.size(), batches.size(), judged.size(), needMore.size(), vlog.size());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("2차 판단이 오지 않은 조항이 남음 (판정 중): analysisId=" + analysisId
                    + ", clauses=" + missing);
        }
    }

    // ---- 7-1 추가 조회 ----

    private void judgeMore(AnalysisRunContext ctx, Material m, List<NeedMoreRequest> needMore, Map<String, Judged> judged,
                           Set<String> questions, List<Map<String, Object>> vlog, GptContext gptContext) {
        String stage = JudgeValidator.STAGE_JUDGE_MORE;
        if (aiCallLogRepository.countCalls(ctx.analysisId(), GptPurpose.JUDGE_MORE) > 0) {
            vlog.add(JudgeValidator.entry(stage, "NEED_MORE_LIMIT", "analysis", "이미 추가 조회함 (분석당 1회)"));
            return;
        }
        // 한 번의 호출로 끝내려고 한 묶음 크기까지만 다시 판단한다
        Map<String, NeedMoreRequest> unique = new LinkedHashMap<>();
        needMore.forEach(n -> unique.putIfAbsent(n.clauseKey(), n));
        needMore = List.copyOf(unique.values());
        List<NeedMoreRequest> targets = needMore.stream().limit(BATCH_SIZE).toList();
        if (needMore.size() > targets.size()) {
            vlog.add(JudgeValidator.entry(stage, "NEED_MORE_TRIMMED", "needMore",
                    needMore.size() + "개 중 " + targets.size() + "개만 추가 조회"));
        }
        List<Long> lawIds = lawSearchService.findLawIds(m.analysis().getDocumentTypeCode());
        List<AnalysisClause> clauses = new ArrayList<>();
        for (NeedMoreRequest n : targets) {
            AnalysisClause c = m.byKey().get(n.clauseKey());
            int added = needMoreEvidence.collect(m.analysis(), c.getClauseId(), n.keywords(), n.want(), lawIds);
            vlog.add(JudgeValidator.entry(stage, "NEED_MORE_FOUND", "clause " + n.clauseKey(), "새 근거 " + added + "개"));
            clauses.add(c);
        }
        Call call = call(m, clauses, loadRefs(clauses), true);
        JudgeResponse response;
        try {
            // 분석당 1건이라 다시 부르지 않는다. 호출은 공용 풀로 보낸다 (풀이 가득 차 거절되면 첫 판단을 쓴다)
            response = submitOnce(GptPurpose.JUDGE_MORE, call.request(), gptContext);
        } catch (GptCallException | EmptyJudgeResponseException | RejectedExecutionException e) {
            vlog.add(JudgeValidator.entry(stage, "JUDGE_MORE_FAILED", "analysis", reasonOf(e) + " (첫 판단을 씀)"));
            log.info("추가 조회 2차 실패, 첫 판단을 그대로 씀: analysisId={}, reason={}", ctx.analysisId(), reasonOf(e));
            return;
        }
        JudgeValidator.Result r = JudgeValidator.validate(stage, response, call.sent(), m.byKey().keySet(), false,
                m.clauseNames());
        judged.putAll(r.judgments());
        questions.addAll(r.questions());
        vlog.addAll(r.log());
    }

    // ---- 호출 만들기 ----

    /** 한 번의 호출: 보낸 조항(검증 기준)과 요청 */
    record Call(Map<String, SentClause> sent, GptRequest request) {
    }

    private Call call(Material m, List<AnalysisClause> clauses, Map<Long, List<AnalysisClauseRef>> refs,
                      boolean finalRound) {
        Map<String, SentClause> sent = new LinkedHashMap<>();
        List<JudgePrompt.ClauseInput> inputs = new ArrayList<>();
        for (AnalysisClause c : clauses) {
            List<AnalysisClauseRef> clauseRefs = refs.getOrDefault(c.getClauseId(), List.of());
            Set<String> labels = clauseRefs.stream().map(AnalysisClauseRef::getRefLabel)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> lawLabels = clauseRefs.stream()
                    .filter(r -> r.getLawArticleId() != null && r.getFoundBy() != RefFoundBy.RULE)
                    .map(AnalysisClauseRef::getRefLabel)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            sent.put(c.getClauseKey(), new SentClause(c.getClauseKey(), c.getBasisType() == BasisType.RULE, labels,
                    lawLabels));
            List<AnalysisClause> related = m.relatedIds().getOrDefault(c.getClauseId(), List.of()).stream()
                    .map(m.byId()::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            inputs.add(new JudgePrompt.ClauseInput(c, clauseRefs, related));
        }
        GptRequest request = GptRequest.of(JudgePrompt.SYSTEM_PROMPT,
                JudgePrompt.userContent(m.index(), m.missingItems(), inputs, finalRound),
                JudgePrompt.SCHEMA_NAME, JudgePrompt.schema());
        return new Call(sent, request);
    }

    /**
     * 모든 호출을 동시에 보내고 끝날 때까지 기다린다. 실패한 호출만 {@link #BATCH_RETRIES}회 다시 보낸다.
     * 그래도 실패한 것이 있으면 첫 실패를 그대로 던진다 (GptCallException 이면 파이프라인이 TEMPORARY_ERROR).
     */
    List<JudgeResponse> runAll(GptPurpose purpose, List<Call> calls, GptContext gptContext, Long analysisId) {
        JudgeResponse[] results = new JudgeResponse[calls.size()];
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            pending.add(i);
        }
        RuntimeException lastError = null;
        for (int round = 0; round <= BATCH_RETRIES && !pending.isEmpty(); round++) {
            if (round > 0) {
                log.info("2차 실패한 묶음만 다시 부름: analysisId={}, batches={}", analysisId, pending);
            }
            Map<Integer, CompletableFuture<JudgeResponse>> futures = new LinkedHashMap<>();
            for (Integer i : pending) {
                try {
                    futures.put(i, CompletableFuture.supplyAsync(
                            () -> callOnce(purpose, calls.get(i).request(), gptContext), executor));
                } catch (RejectedExecutionException e) {
                    futures.values().forEach(f -> f.cancel(true));
                    throw e;
                }
            }
            List<Integer> failed = new ArrayList<>();
            for (Map.Entry<Integer, CompletableFuture<JudgeResponse>> e : futures.entrySet()) {
                try {
                    results[e.getKey()] = e.getValue().join();
                } catch (CompletionException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    lastError = cause instanceof RuntimeException re ? re : new IllegalStateException("2차 호출 실패", cause);
                    failed.add(e.getKey());
                    log.warn("2차 묶음 실패: analysisId={}, batch={}, error={}", analysisId, e.getKey(), cause.toString());
                }
            }
            pending = failed;
        }
        if (!pending.isEmpty()) {
            throw lastError;
        }
        return List.of(results);
    }

    private JudgeResponse callOnce(GptPurpose purpose, GptRequest request, GptContext gptContext) {
        JudgeResponse response = gptClient.call(purpose, request, JudgeResponse.class, gptContext);
        if (response == null) {
            throw new EmptyJudgeResponseException(purpose);
        }
        return response;
    }

    /** 공용 풀로 한 번만 보내고 기다린다 (다시 부르지 않음). 호출 쪽 예외는 그대로 던진다 */
    private JudgeResponse submitOnce(GptPurpose purpose, GptRequest request, GptContext gptContext) {
        try {
            return CompletableFuture.supplyAsync(() -> callOnce(purpose, request, gptContext), executor).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException(purpose + " 호출 실패", cause);
        }
    }

    /** 호출마다 그 호출에 보낸 조항 기준으로 검증해 모은다. 이미 판단이 있는 조항은 덮지 않는다 */
    private static void collect(String stage, List<Call> calls, List<JudgeResponse> responses, Material m,
                                Map<String, Judged> judged, List<NeedMoreRequest> needMore, Set<String> questions,
                                List<Map<String, Object>> vlog) {
        for (int i = 0; i < calls.size(); i++) {
            JudgeValidator.Result r = JudgeValidator.validate(stage, responses.get(i), calls.get(i).sent(),
                    m.byKey().keySet(), true, m.clauseNames());
            r.judgments().forEach(judged::putIfAbsent);
            needMore.addAll(r.needMore());
            questions.addAll(r.questions());
            vlog.addAll(r.log());
        }
    }

    // ---- 재료 ----

    private Material material(Analysis analysis, List<AnalysisClause> visible) {
        Map<String, AnalysisClause> byKey = visible.stream()
                .collect(Collectors.toMap(AnalysisClause::getClauseKey, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<Long, AnalysisClause> byId = visible.stream()
                .collect(Collectors.toMap(AnalysisClause::getClauseId, Function.identity(), (a, b) -> a, HashMap::new));
        Map<Long, List<Long>> related = new HashMap<>();
        for (AnalysisClauseRelation r : relationRepository.findByAnalysisId(analysis.getAnalysisId())) {
            // 관련 조항은 방향 없이 본다 (1차 병합은 쌍을 한 방향으로만 저장)
            related.computeIfAbsent(r.getClauseId(), k -> new ArrayList<>()).add(r.getRelatedClauseId());
            related.computeIfAbsent(r.getRelatedClauseId(), k -> new ArrayList<>()).add(r.getClauseId());
        }
        List<JudgePrompt.IndexEntry> index = visible.stream().map(JudgePrompt.IndexEntry::from).toList();
        List<String> missingItems = findingRepository.findByAnalysisIdOrderBySortOrderAscFindingIdAsc(analysis.getAnalysisId())
                .stream()
                .filter(f -> f.getKind() == FindingKind.MISSING_ITEM)
                .map(AnalysisFinding::getName)
                .toList();
        return new Material(analysis, visible, byKey, byId, related, index, missingItems);
    }

    private Map<Long, List<AnalysisClauseRef>> loadRefs(List<AnalysisClause> clauses) {
        return refRepository.findByClauseIdInOrderByClauseIdAscRefIdAsc(
                        clauses.stream().map(AnalysisClause::getClauseId).toList()).stream()
                .collect(Collectors.groupingBy(AnalysisClauseRef::getClauseId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 검증한 판단 → 저장할 값. 우선순서(priority): 확인 필요(NEEDS_CHECK) 중 규칙 결과 먼저, 그다음 AI 판단,
     * 마지막에 근거 부족(UNCERTAIN). 같은 묶음 안은 쪽·조항 순서. OK 는 null (우선 목록에 안 나옴).
     */
    static List<ClauseJudgment> toClauseJudgments(Map<String, Judged> judged, Material m) {
        List<Judged> ordered = new ArrayList<>(judged.values());
        Comparator<Judged> byRank = Comparator.comparingInt(j -> rank(j, m.byKey().get(j.clauseKey())));
        Comparator<Judged> byPosition = Comparator.comparingInt(j -> m.visible().indexOf(m.byKey().get(j.clauseKey())));
        ordered.sort(byRank.thenComparing(byPosition));
        List<ClauseJudgment> out = new ArrayList<>();
        int priority = 1;
        for (Judged j : ordered) {
            AnalysisClause c = m.byKey().get(j.clauseKey());
            boolean prioritized = j.verdict() == ClauseVerdict.NEEDS_CHECK || j.verdict() == ClauseVerdict.UNCERTAIN;
            List<Long> relatedIds = j.relatedKeys().stream().map(k -> m.byKey().get(k).getClauseId()).toList();
            out.add(new ClauseJudgment(c.getClauseId(), j.verdict(), j.headline(), j.easyExplanation(), j.whyCheck(),
                    j.citedLabels(), relatedIds, j.askCounterpart(), prioritized ? priority++ : null));
        }
        return out;
    }

    private static int rank(Judged j, AnalysisClause c) {
        if (j.verdict() == ClauseVerdict.NEEDS_CHECK) {
            return c.getBasisType() == BasisType.RULE ? 0 : 1;
        }
        return j.verdict() == ClauseVerdict.UNCERTAIN ? 2 : 3;
    }

    private boolean save(Long analysisId, List<ClauseJudgment> judgments, List<String> questions,
                         List<Map<String, Object>> vlog) {
        boolean saved = judgeWriter.saveJudgments(analysisId, judgments, questions, vlog);
        if (!saved) {
            log.info("2차 저장 전에 분석이 이미 끝나 저장하지 않음: analysisId={}", analysisId);
        }
        return saved;
    }

    private static String reasonOf(RuntimeException e) {
        return e instanceof GptCallException g ? String.valueOf(g.getReason()) : e.getClass().getSimpleName();
    }

    static <T> List<List<T>> partition(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(List.copyOf(items.subList(i, Math.min(items.size(), i + size))));
        }
        return out;
    }
}
