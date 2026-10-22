package com.rishikesh.placementprep.modules.notification.model;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message in a student's notification bell.
 *
 * <p>userId and driveId are plain foreign-key columns, the same choice Application makes:
 * listing a student's notifications never needs the whole account or drive loaded with
 * them. The message is written in full when the notification is created, so the bell is a
 * single query with nothing to look up per row.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** The drive it is about. Nullable in the schema for future notifications that are not. */
    @Column(name = "drive_id")
    private Long driveId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationKind kind;

    @Column(nullable = false, length = 500)
    private String message;

    @CreationTimestamp
    private Instant createdAt;

    /** When the student marked it read; null while it is still unread. */
    private Instant readAt;

    public Notification() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getDriveId() {
        return driveId;
    }

    public void setDriveId(Long driveId) {
        this.driveId = driveId;
    }

    public NotificationKind getKind() {
        return kind;
    }

    public void setKind(NotificationKind kind) {
        this.kind = kind;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }
}
