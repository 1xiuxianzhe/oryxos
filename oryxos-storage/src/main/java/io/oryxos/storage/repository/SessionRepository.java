package io.oryxos.storage.repository;

import io.oryxos.storage.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<SessionEntity, String> {

    Optional<SessionEntity> findFirstByChannelAndUserIdAndProfileNameAndStatus(
            String channel, String userId, String profileName, String status);

    List<SessionEntity> findAllByOrderByLastActiveAtDesc();
}
