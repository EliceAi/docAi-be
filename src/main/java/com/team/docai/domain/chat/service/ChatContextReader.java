package com.team.docai.domain.chat.service;

import com.team.docai.domain.analysis.dto.AnalysisChatInfo;
import com.team.docai.domain.analysis.entity.AnalysisStatus;
import com.team.docai.domain.analysis.service.query.AnalysisChatQueryService;
import com.team.docai.domain.chat.dto.AskCommand;
import com.team.docai.domain.chat.dto.LinkedDocumentInfo;
import com.team.docai.domain.chat.entity.ChatMessage;
import com.team.docai.domain.chat.entity.Conversation;
import com.team.docai.domain.chat.repository.ChatMessageRepository;
import com.team.docai.domain.chat.repository.ConversationRepository;
import com.team.docai.domain.chat.service.query.ComparisonReadOnlyQuery;
import com.team.docai.domain.chat.service.query.ComparisonReadOnlyQuery.ComparisonForChat;
import com.team.docai.domain.contract.dto.ContractSummaryInfo;
import com.team.docai.domain.contract.service.ContractService;
import com.team.docai.domain.law.dto.ArticleNumber;
import com.team.docai.domain.law.dto.LawArticleInfo;
import com.team.docai.domain.law.service.LawSearchService;
import com.team.docai.global.error.BusinessException;
import com.team.docai.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * AI 질문 맥락 모으기 (아키텍처 챗봇 2단계). 읽기 트랜잭션 하나에서 확인과 읽기를 끝내고, GPT 는 트랜잭션이 끝난 뒤 부른다.
 * <p>
 * 확인 순서 (어긋나면 바로 에러, 아무것도 저장하지 않음):
 * <ol>
 *   <li>기존 대화: 내 대화가 아니면 CONVERSATION_NOT_FOUND. 다른 contractId 를 보내면(일반 대화에 계약서를 보낸 경우 포함)
 *       CONVERSATION_DOCUMENT_MISMATCH. 연결된 계약서는 대화 중에 바꾸지 않는다. analysisId·comparisonId 는 무시
 *       (새 대화에서만 정함).</li>
 *   <li>계약서: B {@code ContractService.getSummary} (남의 것·삭제됨이면 CONTRACT_NOT_FOUND 를 그대로 올림).</li>
 *   <li>새 대화의 분석: 내 분석이 아니면 ANALYSIS_NOT_FOUND. contractId 와 다른 계약서의 분석이어도 ANALYSIS_NOT_FOUND.
 *       contractId 없이 analysisId 만 오면 그 분석의 계약서를 연결한다.</li>
 *   <li>새 대화의 비교: 내 비교가 아니면 COMPARISON_NOT_FOUND. 비교의 이전본이 contractId 와 달라도 COMPARISON_NOT_FOUND.
 *       contractId 없이 오면 이전본을 연결한다.</li>
 *   <li>조항: 답변에 쓰는 완료 분석의 보이는 조항이 아니면 CLAUSE_NOT_FOUND.</li>
 * </ol>
 * 답변에 쓰는 분석: 요청·대화의 분석이 COMPLETED 면 그것, 아니면(분석 중·실패) 계약서의 최신 완료 분석, 없으면 내용 없이 답한다
 * ("분석 전 계약서는 내용을 근거로 답하지 않는다").
 */
@Component
@RequiredArgsConstructor
class ChatContextReader {

    /** 최근 대화로 그대로 보내는 메시지 수 (질문·답변 3쌍). 그 앞은 대화 요약으로 */
    static final int RECENT_MESSAGES = 6;
    /** 분석 근거에서 가져오는 조문 수, 질문으로 검색하는 조문 수 */
    static final int MAX_ANALYSIS_SOURCES = 4;
    static final int MAX_SEARCH_SOURCES = 4;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ContractService contractService;
    private final AnalysisChatQueryService analysisChatQueryService;
    private final ComparisonReadOnlyQuery comparisonQuery;
    private final LawSearchService lawSearchService;
    private final LinkedDocuments linkedDocuments;

    @Transactional(readOnly = true)
    public ChatContext read(UUID userId, AskCommand command) {
        Long conversationId = null;
        String previousSummary = null;
        Long contractId;
        Long requestedAnalysisId;
        Long comparisonId;
        Long analysisIdToStore = null;
        ContractSummaryInfo contract = null;
        AnalysisChatInfo requested = null;
        ComparisonForChat comparison = null;

        if (command.conversationId() != null) {
            Conversation conversation = conversationRepository.findByConversationIdAndUserId(command.conversationId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONVERSATION_NOT_FOUND));
            if (command.contractId() != null && !command.contractId().equals(conversation.getContractId())) {
                throw new BusinessException(ErrorCode.CONVERSATION_DOCUMENT_MISMATCH);
            }
            conversationId = conversation.getConversationId();
            previousSummary = conversation.getSummary();
            contractId = conversation.getContractId();
            requestedAnalysisId = conversation.getAnalysisId();
            comparisonId = conversation.getComparisonId();
            if (contractId != null) {
                contract = contractService.getSummary(userId, contractId);
            }
            if (requestedAnalysisId != null) {
                // 대화를 만들 때 확인한 분석. 그 사이 볼 수 없게 됐으면 최신 완료 분석으로 대신한다
                requested = analysisChatQueryService.findForChat(userId, requestedAnalysisId)
                        .filter(a -> Objects.equals(a.contractId(), conversation.getContractId()))
                        .orElse(null);
            }
            if (comparisonId != null) {
                comparison = comparisonQuery.find(userId, comparisonId).orElse(null);
            }
        } else {
            contractId = command.contractId();
            if (contractId != null) {
                contract = contractService.getSummary(userId, contractId);
            }
            if (command.analysisId() != null) {
                requested = analysisChatQueryService.findForChat(userId, command.analysisId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND));
                if (contractId == null) {
                    contractId = requested.contractId();
                    contract = contractService.getSummary(userId, contractId);
                } else if (!contractId.equals(requested.contractId())) {
                    throw new BusinessException(ErrorCode.ANALYSIS_NOT_FOUND);
                }
                analysisIdToStore = requested.analysisId();
            }
            comparisonId = command.comparisonId();
            if (comparisonId != null) {
                comparison = comparisonQuery.find(userId, comparisonId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.COMPARISON_NOT_FOUND));
                if (contractId == null) {
                    contractId = comparison.previousContractId();
                    contract = contractService.getSummary(userId, contractId);
                } else if (!contractId.equals(comparison.previousContractId())) {
                    throw new BusinessException(ErrorCode.COMPARISON_NOT_FOUND);
                }
            }
        }

        // 답변에 쓰는 완료 분석
        AnalysisChatInfo analysis = requested != null && requested.completed() ? requested : null;
        AnalysisStatus pendingStatus = requested != null && !requested.completed() ? requested.status() : null;
        if (analysis == null && contract != null && contract.latestAnalysisId() != null) {
            Long finalContractId = contractId;
            analysis = analysisChatQueryService.findForChat(userId, contract.latestAnalysisId())
                    .filter(AnalysisChatInfo::completed)
                    .filter(a -> Objects.equals(a.contractId(), finalContractId))
                    .orElse(null);
        }

        AnalysisChatInfo.Clause focus = null;
        if (command.clauseId() != null) {
            focus = analysis == null ? null : analysis.clauses().stream()
                    .filter(c -> c.clauseId().equals(command.clauseId()))
                    .findFirst()
                    .orElse(null);
            if (focus == null) {
                throw new BusinessException(ErrorCode.CLAUSE_NOT_FOUND);
            }
        }

        LinkedDocumentInfo linked = contract == null ? null : linkedDocuments.from(contract);
        List<ChatContext.Source> sources = sources(command.message(), analysis, focus);
        List<ChatContext.PastMessage> recent = conversationId == null ? List.of() : recent(conversationId);

        return new ChatContext(userId, conversationId, previousSummary, command.message(), command.screen(),
                focus == null ? null : focus.clauseId(), contractId, analysisIdToStore, comparisonId, linked,
                analysis, pendingStatus, focus, comparison, sources, recent);
    }

    /**
     * 법령 자료: ① 보고 있는 조항의 근거 ② 분석의 다른 확인 필요 조항 근거 (①②는 분석 당시 스냅샷, 합쳐 4개까지)
     * ③ 질문 낱말로 찾은 조문 (문서 종류의 관련 법령 안에서, 4개까지). 같은 조문은 한 번만. ID 는 L1 부터.
     */
    private List<ChatContext.Source> sources(String question, AnalysisChatInfo analysis, AnalysisChatInfo.Clause focus) {
        List<ChatContext.Source> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        if (analysis != null) {
            List<AnalysisChatInfo.LawRef> refs = new ArrayList<>();
            if (focus != null) {
                analysis.lawRefs().stream().filter(r -> r.clauseId().equals(focus.clauseId())).forEach(refs::add);
            }
            analysis.lawRefs().stream().filter(r -> focus == null || !r.clauseId().equals(focus.clauseId()))
                    .forEach(refs::add);
            for (AnalysisChatInfo.LawRef r : refs) {
                if (out.size() >= MAX_ANALYSIS_SOURCES) {
                    break;
                }
                if (seen.add(r.lawArticleId())) {
                    out.add(new ChatContext.Source("L" + (out.size() + 1), r.lawArticleId(), r.label(),
                            r.title() == null ? r.label() : r.label() + " (" + r.title() + ")", r.text()));
                }
            }
        }
        List<String> keywords = ChatKeywords.extract(question);
        if (!keywords.isEmpty()) {
            List<Long> lawIds = analysis == null ? List.of() : lawSearchService.findLawIds(analysis.documentTypeCode());
            int added = 0;
            for (LawArticleInfo a : lawSearchService.search(keywords, lawIds, null, MAX_SEARCH_SOURCES)) {
                if (added >= MAX_SEARCH_SOURCES) {
                    break;
                }
                if (seen.add(a.lawArticleId())) {
                    String label = a.lawName() + " " + ArticleNumber.label(a.articleNo(), a.articleBranchNo(), null);
                    out.add(new ChatContext.Source("L" + (out.size() + 1), a.lawArticleId(), label,
                            a.title() == null ? a.label() : a.label() + " (" + a.title() + ")", a.content()));
                    added++;
                }
            }
        }
        return List.copyOf(out);
    }

    private List<ChatContext.PastMessage> recent(Long conversationId) {
        List<ChatMessage> latest = chatMessageRepository.findByConversationIdOrderByMessageIdDesc(
                conversationId, PageRequest.of(0, RECENT_MESSAGES));
        List<ChatContext.PastMessage> out = new ArrayList<>();
        for (int i = latest.size() - 1; i >= 0; i--) {
            out.add(new ChatContext.PastMessage(latest.get(i).getRole(), latest.get(i).getContent()));
        }
        return List.copyOf(out);
    }
}
