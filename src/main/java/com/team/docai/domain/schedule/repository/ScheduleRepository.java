package com.team.docai.domain.schedule.repository;
import com.team.docai.domain.schedule.entity.Schedule;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.*;
public interface ScheduleRepository extends JpaRepository<Schedule,Long> {
 long countByUserIdAndContractId(UUID userId,Long contractId);
 Optional<Schedule> findByScheduleIdAndUserId(Long scheduleId,UUID userId);
 Optional<Schedule> findByCandidateId(Long candidateId);
 List<Schedule> findByUserIdAndContractIdOrderByDateAscScheduleIdAsc(UUID userId,Long contractId);
 List<Schedule> findByUserIdAndDateGreaterThanEqualOrderByDateAscScheduleIdAsc(UUID userId,LocalDate date,Pageable pageable);
 List<Schedule> findByDateInOrderByDateAscScheduleIdAsc(Collection<LocalDate> dates);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select s from Schedule s where s.scheduleId=:id and s.userId=:user")
 Optional<Schedule> lockOwned(@Param("user") UUID user,@Param("id") Long id);
 @Lock(LockModeType.PESSIMISTIC_WRITE)
 @Query("select s from Schedule s where s.scheduleId=:id")
 Optional<Schedule> lockById(@Param("id") Long id);
}
