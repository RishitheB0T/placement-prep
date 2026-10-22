package com.rishikesh.placementprep.modules.notification.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rishikesh.placementprep.modules.notification.model.Notification;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** A student's newest 50 - the bell shows recent ones, not the whole history. */
    List<Notification> findTop50ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** Scoped by owner as well as id, so marking read can only ever touch your own row. */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    /** Whether this reminder was already sent - the job's check before creating one. */
    boolean existsByUserIdAndDriveIdAndKind(Long userId, Long driveId, NotificationKind kind);

    /** Marks every unread notification of one student read, in one UPDATE. */
    @Modifying
    @Query("UPDATE Notification n SET n.readAt = :now WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") Long userId, @Param("now") Instant now);
}
