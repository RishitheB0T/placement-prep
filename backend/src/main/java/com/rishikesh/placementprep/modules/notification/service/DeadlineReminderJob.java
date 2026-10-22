package com.rishikesh.placementprep.modules.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;

/**
 * Tells students about drives they should not miss.
 *
 * <p>Every run looks at each student (and coordinator - they are students too) whose
 * profile is complete, takes the open drives they are eligible for - the same query as the
 * "Eligible for me" tab - and skips the ones they have already applied to. For each drive
 * left, it sends at most one notification of each kind:
 * <ul>
 *   <li>more than 3 days left - NEW_DRIVE, so they hear about it at all;</li>
 *   <li>3 days or less - DEADLINE_3_DAYS;</li>
 *   <li>1 day or less - DEADLINE_1_DAY.</li>
 * </ul>
 * A drive posted with two days to go therefore skips NEW_DRIVE and starts at the 3-day
 * reminder, whose message says the same thing - no student gets two messages about one
 * drive in the same run.
 *
 * <p>"At most once" is what makes running every few minutes safe: NotificationService
 * checks before inserting, and V15's unique index backs that up in the database. A
 * student who completes their profile later, or a drive whose criteria are relaxed, is
 * simply picked up by the next run.
 *
 * <p>Not {@code @Transactional}: each notification is its own short transaction, so one
 * failing student is logged and skipped without undoing the reminders already sent.
 */
@Component
public class DeadlineReminderJob {

    private static final Logger log = LoggerFactory.getLogger(DeadlineReminderJob.class);

    static final Duration ONE_DAY = Duration.ofDays(1);
    static final Duration THREE_DAYS = Duration.ofDays(3);

    /** Deadlines are shown in Indian time, where the college is. */
    private static final DateTimeFormatter DEADLINE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a").withZone(ZoneId.of("Asia/Kolkata"));

    private final UserService userService;
    private final DriveService driveService;
    private final ApplicationService applicationService;
    private final NotificationService notificationService;

    public DeadlineReminderJob(UserService userService,
                               DriveService driveService,
                               ApplicationService applicationService,
                               NotificationService notificationService) {
        this.userService = userService;
        this.driveService = driveService;
        this.applicationService = applicationService;
        this.notificationService = notificationService;
    }

    /**
     * The scheduled entry point. The first run waits a minute after startup, then the job
     * repeats a fixed delay after each run finishes, so two runs never overlap.
     */
    @Scheduled(initialDelayString = "${notification.reminders.initial-delay:PT1M}",
               fixedDelayString = "${notification.reminders.poll-delay:PT15M}")
    public void run() {
        int sent = sendDueReminders(Instant.now());
        if (sent > 0) {
            log.info("Sent {} drive notification(s)", sent);
        }
    }

    /**
     * One pass over every student. Public, with "now" passed in, so tests can run a pass
     * on demand and decide what time it is.
     *
     * @return how many notifications were created
     */
    public int sendDueReminders(Instant now) {
        int sent = 0;
        for (UserProfileDTO student : studentsWithCompleteProfiles()) {
            try {
                sent += remind(student, now);
            } catch (RuntimeException e) {
                // One bad row must not stop everyone else's reminders.
                log.warn("Could not send drive notifications to user {}", student.id(), e);
            }
        }
        return sent;
    }

    private List<UserProfileDTO> studentsWithCompleteProfiles() {
        return Stream.concat(
                        userService.findAll(Optional.of(Role.STUDENT)).stream(),
                        userService.findAll(Optional.of(Role.TNP_COORDINATOR)).stream())
                .filter(UserProfileDTO::isComplete)
                .toList();
    }

    private int remind(UserProfileDTO student, Instant now) {
        // Drives with an active application need no reminder. A withdrawn one does - the
        // student can apply again, and the drive is as much "not applied to" as ever.
        Set<Long> appliedTo = applicationService.findMine(student.id()).stream()
                .filter(application -> application.status() != ApplicationStatus.WITHDRAWN)
                .map(ApplicationDTO::driveId)
                .collect(Collectors.toSet());

        List<DriveDTO> eligible = driveService.findEligibleDrives(
                student.cgpa(), student.tenthPercentage(), student.twelfthPercentage(),
                student.backlogs(), student.branch());

        int sent = 0;
        for (DriveDTO drive : eligible) {
            if (appliedTo.contains(drive.id()) || drive.applicationDeadline() == null) {
                continue;
            }
            Duration left = Duration.between(now, drive.applicationDeadline());
            if (left.isNegative() || left.isZero()) {
                continue; // closed between the query and now
            }

            String name = drive.companyName() + " - " + drive.role();
            String closes = DEADLINE_FORMAT.format(drive.applicationDeadline());

            boolean created;
            if (left.compareTo(ONE_DAY) <= 0) {
                created = notificationService.notifyOnce(student.id(), drive.id(), NotificationKind.DEADLINE_1_DAY,
                        "Last day to apply: " + name + " closes on " + closes + ". You have not applied yet.");
            } else if (left.compareTo(THREE_DAYS) <= 0) {
                created = notificationService.notifyOnce(student.id(), drive.id(), NotificationKind.DEADLINE_3_DAYS,
                        name + " closes in less than 3 days, on " + closes
                                + ". You are eligible and have not applied yet.");
            } else {
                created = notificationService.notifyOnce(student.id(), drive.id(), NotificationKind.NEW_DRIVE,
                        "New drive you are eligible for: " + name + ". Applications close on " + closes + ".");
            }
            if (created) {
                sent++;
            }
        }
        return sent;
    }
}
