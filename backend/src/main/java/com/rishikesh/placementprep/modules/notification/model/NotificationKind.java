package com.rishikesh.placementprep.modules.notification.model;

/**
 * What a notification is about. Stored by name (see V15's CHECK constraint), so adding or
 * reordering constants never changes what an existing row means.
 */
public enum NotificationKind {

    /** An open drive the student is eligible for and has not applied to. Sent once per drive. */
    NEW_DRIVE,

    /** That drive closes within three days and they still have not applied. Sent once. */
    DEADLINE_3_DAYS,

    /** That drive closes within a day and they still have not applied. Sent once. */
    DEADLINE_1_DAY,

    /** The hiring side shortlisted, rejected, selected or reopened one of their applications. */
    STATUS_CHANGED
}
