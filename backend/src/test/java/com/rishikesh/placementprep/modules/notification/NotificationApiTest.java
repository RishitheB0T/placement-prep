package com.rishikesh.placementprep.modules.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.application.model.Application;
import com.rishikesh.placementprep.modules.application.model.ApplicationStatus;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.rishikesh.placementprep.modules.notification.model.Notification;
import com.rishikesh.placementprep.modules.notification.model.NotificationKind;
import com.rishikesh.placementprep.modules.notification.repository.NotificationRepository;
import com.rishikesh.placementprep.modules.notification.service.DeadlineReminderJob;

/**
 * Deadline reminders end to end: a real reminder pass over real PostgreSQL, then the bell's
 * endpoints. The scheduled run is pushed a day out by the test properties, so every pass
 * here is one the test started itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeadlineReminderJob reminderJob;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    private Long studentId;
    private String studentToken;

    @BeforeEach
    void startFromEmptyTables() {
        notificationRepository.deleteAll();
        applicationRepository.deleteAll();
        driveRepository.deleteAll();
        userRepository.deleteAll();

        studentId = createUser("student@cse.nits.ac.in", Role.STUDENT).getId();
        studentToken = jwtService.generateToken("student@cse.nits.ac.in", Role.STUDENT);
    }

    // ------------------------------------------------------------- The job

    @Test
    void anEligibleStudentHearsAboutANewDriveOnceNoMatterHowOftenTheJobRuns() {
        seedDrive("Zoho", Instant.now().plus(20, ChronoUnit.DAYS), "CSE");

        assertThat(reminderJob.sendDueReminders(Instant.now())).isEqualTo(1);
        assertThat(reminderJob.sendDueReminders(Instant.now())).isZero();

        List<Notification> sent = notificationRepository.findAll();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getKind()).isEqualTo(NotificationKind.NEW_DRIVE);
        assertThat(sent.get(0).getMessage()).contains("Zoho - SDE");
    }

    @Test
    void aDriveClosingSoonSendsTheDeadlineReminder() {
        seedDrive("Zoho", Instant.now().plus(2, ChronoUnit.DAYS), "CSE");

        reminderJob.sendDueReminders(Instant.now());

        assertThat(notificationRepository.findAll()).extracting(Notification::getKind)
                .containsExactly(NotificationKind.DEADLINE_3_DAYS);
    }

    @Test
    void noReminderForADriveTheStudentAlreadyAppliedTo() {
        Drive drive = seedDrive("Zoho", Instant.now().plus(2, ChronoUnit.DAYS), "CSE");
        Application application = new Application();
        application.setStudentId(studentId);
        application.setDriveId(drive.getId());
        application.setStatus(ApplicationStatus.APPLIED);
        applicationRepository.save(application);

        assertThat(reminderJob.sendDueReminders(Instant.now())).isZero();
    }

    @Test
    void noReminderForADriveTheStudentIsNotEligibleFor() {
        seedDrive("Zoho", Instant.now().plus(2, ChronoUnit.DAYS), "MECH");

        assertThat(reminderJob.sendDueReminders(Instant.now())).isZero();
    }

    // --------------------------------------------------------- The endpoints

    @Test
    void aStudentReadsTheirNotificationsAndMarksThemRead() throws Exception {
        seedDrive("Zoho", Instant.now().plus(20, ChronoUnit.DAYS), "CSE");
        seedDrive("Infosys", Instant.now().plus(20, ChronoUnit.DAYS), "CSE");
        reminderJob.sendDueReminders(Instant.now());
        Long firstId = notificationRepository.findAll().get(0).getId();

        mockMvc.perform(get("/api/notifications/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].read").value(false));

        mockMvc.perform(post("/api/notifications/" + firstId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));

        mockMvc.perform(post("/api/notifications/read-all").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNoContent());

        assertThat(notificationRepository.findAll()).allSatisfy(n -> assertThat(n.getReadAt()).isNotNull());
    }

    @Test
    void anotherStudentsNotificationIs404() throws Exception {
        seedDrive("Zoho", Instant.now().plus(20, ChronoUnit.DAYS), "CSE");
        reminderJob.sendDueReminders(Instant.now());
        Long theirs = notificationRepository.findAll().get(0).getId();
        createUser("other@cse.nits.ac.in", Role.STUDENT);
        String otherToken = jwtService.generateToken("other@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/notifications/" + theirs + "/read").header(HttpHeaders.AUTHORIZATION, bearer(otherToken)))
                .andExpect(status().isNotFound());
    }

    /** Notifications are written only for students, so only students can read them. */
    @Test
    void recruitersAndTheAdminHaveNoNotifications() throws Exception {
        createUser("hr@zoho.com", Role.RECRUITER);
        createUser("pic@tnp.nits.ac.in", Role.TNP_PIC);

        mockMvc.perform(get("/api/notifications/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtService.generateToken("hr@zoho.com", Role.RECRUITER))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/notifications/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtService.generateToken("pic@tnp.nits.ac.in", Role.TNP_PIC))))
                .andExpect(status().isForbidden());
    }

    @Test
    void notificationsNeedAToken() throws Exception {
        mockMvc.perform(get("/api/notifications/me")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- Helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        if (role == Role.STUDENT) {
            user.setCgpa(new BigDecimal("8.50"));
            user.setBranch("CSE");
            user.setTenthPercentage(new BigDecimal("90"));
            user.setTwelfthPercentage(new BigDecimal("88"));
        }
        return userRepository.save(user);
    }

    private Drive seedDrive(String company, Instant deadline, String branch) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole("SDE");
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal("7.5"));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(List.of(branch));
        drive.setApplicationDeadline(deadline);
        return driveRepository.save(drive);
    }
}
