package com.team.docai.domain.chat.service.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [임시 예외, getForChat 생기면 교체] AI 질문이 비교(comparisonId)를 확인할 B 메서드
 * {@code ComparisonService.getForChat(userId, comparisonId)}(담당_연결지점 B7)가 아직 없어서(domain/comparison 폴더 없음)
 * comparison·comparison_change·contract 를 읽기 전용 SQL 로 읽는다. 쓰기는 하지 않는다.
 * 분석 결과 조회의 ContractReadOnlyQuery 와 같은 방식이다. B 가 getForChat 을 만들면 이 클래스를 지우고 그 메서드를 부른다.
 * <ul>
 *   <li>소유자 확인: comparison.user_id = 요청 사용자.</li>
 *   <li>삭제 확인: 이전본 계약서가 소프트 삭제(deleted_at)됐으면 없는 것으로 본다 (삭제된 계약서는 모든 API 에서 없는 것).
 *       비교 자체에는 삭제 칸이 없다.</li>
 *   <li>바뀐 곳 목록(changes)·그대로인 곳 요약은 getForChat 반환 값(B7: comparisonId, previousContractId, status)에 없다.
 *       챗봇이 비교 결과 질문에 답하려면 필요해서 함께 읽는다 (B 에 반환 값 추가를 요청할 것).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ComparisonReadOnlyQuery {

    /** 챗봇에 보내는 바뀐 곳 수 상한 (맥락이 너무 길어지지 않게) */
    static final int MAX_CHANGES = 20;

    private final JdbcTemplate jdbc;

    /** @return 없거나, 남의 것이거나, 이전본 계약서가 삭제됐으면 empty (부르는 쪽이 COMPARISON_NOT_FOUND) */
    public Optional<ComparisonForChat> find(UUID userId, Long comparisonId) {
        if (userId == null || comparisonId == null) {
            return Optional.empty();
        }
        List<ComparisonForChat> rows = jdbc.query("""
                        select cm.comparison_id, cm.previous_contract_id, cm.status, cm.unchanged_summary
                          from comparison cm
                          join contract c on c.contract_id = cm.previous_contract_id
                         where cm.comparison_id = ? and cm.user_id = ? and c.user_id = ? and c.deleted_at is null
                        """,
                (rs, i) -> new ComparisonForChat(rs.getLong("comparison_id"), rs.getLong("previous_contract_id"),
                        rs.getString("status"), rs.getString("unchanged_summary"), List.of()),
                comparisonId, userId, userId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        List<Change> changes = jdbc.query("""
                        select type, category, label, before_text, after_text, note
                          from comparison_change
                         where comparison_id = ?
                         order by change_id
                         limit ?
                        """,
                (rs, i) -> new Change(rs.getString("type"), rs.getString("category"), rs.getString("label"),
                        rs.getString("before_text"), rs.getString("after_text"), rs.getString("note")),
                comparisonId, MAX_CHANGES);
        ComparisonForChat row = rows.get(0);
        return Optional.of(new ComparisonForChat(row.comparisonId(), row.previousContractId(), row.status(),
                row.unchangedSummary(), changes));
    }

    /**
     * @param status PENDING_CONFIRM / CONFIRMED / DISCARDED / SUPERSEDED
     */
    public record ComparisonForChat(Long comparisonId, Long previousContractId, String status, String unchangedSummary,
                                    List<Change> changes) {
    }

    /** type: CHANGED / ADDED / REMOVED / UNCOMPARED */
    public record Change(String type, String category, String label, String before, String after, String note) {
    }
}
