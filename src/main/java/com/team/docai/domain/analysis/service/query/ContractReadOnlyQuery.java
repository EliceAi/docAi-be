package com.team.docai.domain.analysis.service.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [서비스 담당과 합의한 임시 예외 (2026-11-18)] 분석 결과 조회가 필요한 B 테이블(contract, contract_version,
 * document_type, schedule) 값을 읽기 전용 SQL 로 읽는다. 쓰기는 하지 않는다.
 * <p>
 * 2026-11-19: 분석 주인 확인과 계약서 ID·버전 번호·제목·쪽 수·가린 곳 수는 B {@code ContractService.getVersionOwner} 로
 * 바꿨다 (결과 조회, 조항 상세, 미인식 입력, 챗봇, getRef). 남은 것은 점검 깊이(checkLevel)와 비슷한 계약서 정보뿐이고,
 * 이것도 B 메서드로 바꾸면 이 클래스를 지운다.
 */
@Component
@RequiredArgsConstructor
public class ContractReadOnlyQuery {

    private final JdbcTemplate jdbc;

    /** 문서 종류의 점검 수준 (A / B). 없으면 null */
    public String findCheckLevel(String documentTypeCode) {
        List<String> rows = jdbc.queryForList(
                "select check_level from document_type where code = ?", String.class, documentTypeCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 비슷한 계약서. 이 사용자의 보관된, 삭제되지 않은 계약서일 때만.
     * analyzedAt 은 그 계약서 최신 버전의 최신 분석 완료 시각, scheduleCount 는 그 계약서에 저장된 일정 수.
     */
    public Optional<SimilarContractRow> findSimilarContract(UUID userId, Long contractId) {
        List<SimilarContractRow> rows = jdbc.query("""
                        select c.contract_id, c.title, a.completed_at,
                               (select count(*) from schedule s
                                 where s.contract_id = c.contract_id and s.user_id = c.user_id) as schedule_count
                          from contract c
                          left join contract_version v
                                 on v.contract_id = c.contract_id and v.version_no = c.current_version_no
                          left join analysis a on a.analysis_id = v.latest_analysis_id
                         where c.contract_id = ? and c.user_id = ? and c.deleted_at is null and c.archived = true
                        """,
                (rs, i) -> new SimilarContractRow(rs.getLong("contract_id"), rs.getString("title"),
                        rs.getObject("completed_at", OffsetDateTime.class), rs.getLong("schedule_count")),
                contractId, userId);
        return rows.stream().findFirst();
    }

    public record SimilarContractRow(Long contractId, String title, OffsetDateTime analyzedAt, long scheduleCount) {
    }
}
