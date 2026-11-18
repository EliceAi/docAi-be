package com.team.docai.domain.notification.repository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class AnalysisNotificationLock {
 private final JdbcTemplate jdbc;
 public void lock(Long analysisId){
  // 같은 분석의 동시 알림 요청을 트랜잭션 종료까지 직렬화한다. 테이블 변경은 없다.
  jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs->{return null;},"notification:analysis:"+analysisId);
 }
}
