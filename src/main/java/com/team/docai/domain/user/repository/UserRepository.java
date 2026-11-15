package com.team.docai.domain.user.repository;
import com.team.docai.domain.user.entity.User;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.*;
public interface UserRepository extends JpaRepository<User,UUID> {
 Optional<User> findByDeviceIdHash(String hash);
 @Modifying
 @Query(value="INSERT INTO users(user_id,device_id_hash,created_at) VALUES (:id,:hash,:created) ON CONFLICT (device_id_hash) DO NOTHING",nativeQuery=true)
 int insertIfAbsent(@Param("id") UUID id,@Param("hash") String hash,@Param("created") OffsetDateTime created);
}
