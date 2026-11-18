package com.team.docai.domain.analysis.service.query;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * [서비스 담당과 합의한 임시 예외 (2026-11-18)] 분석 결과 조회가 필요한 B 테이블(contract, contract_version, contract_page,
 * document_type, schedule) 값을 읽기 전용 SQL 로 읽는다. 쓰기는 하지 않는다.
 * 메서드가 생기면 ContractService 호출로 바꿀 곳이다.
 * <p>
 * versionId 로 계약서 주인을 확인하는 B 메서드가 아직 없어서 둔 임시 방식이다 (담당_연결지점 A1 이 같은 이유로 막혀 있음).
 * B 가 ContractService 에 메서드를 만들면 이 클래스를 지우고 그 메서드를 부른다.
 */
@Component
@RequiredArgsConstructor
public class ContractReadOnlyQuery {

    private final JdbcTemplate jdbc;

    /**
     * 분석한 버전이 이 사용자의 삭제되지 않은 계약서 것인지 확인하고 화면에 쓸 값을 읽는다.
     * 남의 계약서이거나 삭제됐으면 empty (호출한 쪽이 ANALYSIS_NOT_FOUND).
     */
    public Optional<OwnedVersion> findOwnedVersion(UUID userId, Long versionId) {
        List<OwnedVersion> rows = jdbc.query("""
                        select v.contract_id, v.version_no, v.page_count, c.title,
                               (select coalesce(sum(p.masked_count), 0) from contract_page p
                                 where p.version_id = v.version_id) as masked_count
                          from contract_version v
                          join contract c on c.contract_id = v.contract_id
                         where v.version_id = ? and c.user_id = ? and c.deleted_at is null
                        """,
                (rs, i) -> new OwnedVersion(rs.getLong("contract_id"), rs.getInt("version_no"),
                        rs.getInt("page_count"), rs.getString("title"), rs.getInt("masked_count")),
                versionId, userId);
        return rows.stream().findFirst();
    }

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

    /** @param versionNo 분석한 버전 번호 (contract_version.version_no) */
    public record OwnedVersion(Long contractId, int versionNo, int pageCount, String title, int maskedCount) {
    }

    public record SimilarContractRow(Long contractId, String title, OffsetDateTime analyzedAt, long scheduleCount) {
    }
}
