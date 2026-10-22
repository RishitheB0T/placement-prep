package com.rishikesh.placementprep.modules.notification.dto;

import java.time.Instant;

import com.rishikesh.placementprep.modules.notification.model.NotificationKind;

/**
 * One notification as the bell shows it. {@code read} is derived from whether readAt is
 * set, so the frontend needs no date logic to count unread ones.
 */
public record NotificationDTO(
    Long id,
    NotificationKind kind,
    String message,
    Long driveId,
    Instant createdAt,
    boolean read
) {}
