package com.rishikesh.placementprep.modules.notification.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rishikesh.placementprep.modules.notification.dto.NotificationDTO;
import com.rishikesh.placementprep.modules.notification.model.Notification;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;
import com.rishikesh.placementprep.modules.notification.repository.NotificationRepository;

/**
 * Storing and reading students' notifications.
 *
 * <p>Depends on nothing but its own repository, on purpose. ApplicationService calls it
 * when a status changes, and DeadlineReminderJob calls it for reminders; if this class
 * depended back on either, Spring could not build the two beans.
 */
@Service
public class NotificationService {

    /** The message column is VARCHAR(500); company and role can be 255 characters each. */
    private static final int MAX_MESSAGE = 500;

    private final NotificationRepository notificationRepository;

    public NotificationService(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /** A student's newest 50 notifications, newest first. */
    @Transactional(readOnly = true)
    public List<NotificationDTO> findMine(Long userId) {
        return notificationRepository.findTop50ByUserIdOrderByCreatedAtDescIdDesc(userId)
                .stream().map(this::toDto).toList();
    }

    /**
     * Records a notification unconditionally - used for status changes, which can happen
     * more than once per application. Joins the caller's transaction, so if the status
     * change rolls back, its notification does too.
     */
    @Transactional
    public void notify(Long userId, Long driveId, NotificationKind kind, String message) {
        notificationRepository.save(build(userId, driveId, kind, message));
    }

    /**
     * Records a reminder only if this student has not had this kind for this drive before.
     * The partial unique index in V15 enforces the same rule in the database, so even two
     * overlapping runs could not send a reminder twice.
     *
     * @return whether a new notification was created
     */
    @Transactional
    public boolean notifyOnce(Long userId, Long driveId, NotificationKind kind, String message) {
        if (notificationRepository.existsByUserIdAndDriveIdAndKind(userId, driveId, kind)) {
            return false;
        }
        notificationRepository.save(build(userId, driveId, kind, message));
        return true;
    }

    /**
     * Marks one of my notifications read. Keyed by owner as well as id, so another
     * student's notification answers empty - the controller's 404 - like one that does not
     * exist. Marking an already-read one again keeps its original readAt.
     */
    @Transactional
    public Optional<NotificationDTO> markRead(Long id, Long userId) {
        return notificationRepository.findByIdAndUserId(id, userId)
                .map(notification -> {
                    if (notification.getReadAt() == null) {
                        notification.setReadAt(Instant.now());
                    }
                    return toDto(notificationRepository.save(notification));
                });
    }

    /** Marks all of my unread notifications read. Returns how many changed. */
    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, Instant.now());
    }

    private Notification build(Long userId, Long driveId, NotificationKind kind, String message) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setDriveId(driveId);
        notification.setKind(kind);
        notification.setMessage(message.length() > MAX_MESSAGE
                ? message.substring(0, MAX_MESSAGE - 3) + "..."
                : message);
        return notification;
    }

    private NotificationDTO toDto(Notification notification) {
        return new NotificationDTO(
                notification.getId(),
                notification.getKind(),
                notification.getMessage(),
                notification.getDriveId(),
                notification.getCreatedAt(),
                notification.getReadAt() != null);
    }
}
