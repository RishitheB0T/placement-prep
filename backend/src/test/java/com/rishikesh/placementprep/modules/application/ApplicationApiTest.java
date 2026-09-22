package com.rishikesh.placementprep.modules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

/**
 * End-to-end tests for /api/applications against a real PostgreSQL container.
 *
 * <p>The rules worth exercising here are exactly the ones that fail silently rather than
 * loudly: a duplicate application, a student reaching for another student's row by id,
 * and the split between what needs only an account and what needs staff.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApplicationApiTest {

    private static final Instant FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);

    @Autowired
    private MockMvc mockMvc;

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

    private String studentToken;
    private String secondStudentToken;
    private String picToken;

    private Long studentId;
    private Long secondStudentId;
    private Long driveId;

    @BeforeEach
    void startFromAnEmptyTable() {
        applicationRepository.deleteAll();
        driveRepository.deleteAll();
        userRepository.deleteAll();

        studentId = createUser("student@cse.nits.ac.in", Role.STUDENT).getId();
        secondStudentId = createUser("second@cse.nits.ac.in", Role.STUDENT).getId();
        createUser("pic@tnp.nits.ac.in", Role.TNP_PIC);

        studentToken = jwtService.generateToken("student@cse.nits.ac.in", Role.STUDENT);
        secondStudentToken = jwtService.generateToken("second@cse.nits.ac.in", Role.STUDENT);
        picToken = jwtService.generateToken("pic@tnp.nits.ac.in", Role.TNP_PIC);

        driveId = seedDrive("Zoho").getId();
    }

    // ------------------------------------------------------------------ Apply

    @Test
    void applyReturns201AndPersistsTheApplication() throws Exception {
        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPLIED"))
                .andExpect(jsonPath("$.driveId").value(driveId));

        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    void applyingTwiceToTheSameDriveIs409() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isConflict());

        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    @Test
    void reapplyingAfterWithdrawingReopensTheSameApplicationRow() throws Exception {
        Application withdrawn = seedApplication(studentId, driveId, ApplicationStatus.WITHDRAWN);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(withdrawn.getId()))
                .andExpect(jsonPath("$.status").value("APPLIED"));

        // Reopened, not duplicated: still exactly one row for this (student, drive) pair.
        assertThat(applicationRepository.count()).isEqualTo(1);
    }

    /**
     * Only WITHDRAWN is re-openable. A rejected application is a decision the cell already
     * made, not a state a second POST should quietly erase.
     */
    @Test
    void applyingAgainAfterBeingRejectedIsStill409() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.REJECTED);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isConflict());

        assertThat(applicationRepository.findByStudentIdAndDriveId(studentId, driveId).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
    }

    @Test
    void twoDifferentStudentsCanApplyToTheSameDrive() throws Exception {
        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(secondStudentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isCreated());

        assertThat(applicationRepository.count()).isEqualTo(2);
    }

    // ------------------------------------------------------------------- Read

    @Test
    void myApplicationsOnlyEverShowsMyOwn() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);
        seedApplication(secondStudentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].studentId").value(studentId));
    }

    @Test
    void aStudentCannotListApplicantsForADrive() throws Exception {
        mockMvc.perform(get("/api/applications/drive/" + driveId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theCellCanListApplicantsForADrive() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);
        seedApplication(secondStudentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/drive/" + driveId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ----------------------------------------------------------------- Status

    @Test
    void theCellCanShortlistAnApplication() throws Exception {
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHORTLISTED","note":"Strong fundamentals."}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHORTLISTED"))
                .andExpect(jsonPath("$.note").value("Strong fundamentals."));
    }

    @Test
    void aStudentCannotChangeTheirOwnApplicationStatus() throws Exception {
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SELECTED"}
                                """))
                .andExpect(status().isForbidden());
    }

    // --------------------------------------------------------------- Withdraw

    @Test
    void withdrawingSetsStatusToWithdrawn() throws Exception {
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(post("/api/applications/" + application.getId() + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    /**
     * The core of the ownership guard: withdraw is keyed by student id as well as
     * application id, so guessing another student's application id must not work.
     */
    @Test
    void aStudentCannotWithdrawSomeoneElsesApplication() throws Exception {
        Application application = seedApplication(secondStudentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(post("/api/applications/" + application.getId() + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNotFound());

        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    // ----------------------------------------------------------------- Helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        return userRepository.save(user);
    }

    private Drive seedDrive(String company) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole("SDE");
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal("7.5"));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(java.util.List.of("CSE"));
        drive.setApplicationDeadline(FUTURE);
        return driveRepository.save(drive);
    }

    private Application seedApplication(Long studentId, Long driveId, ApplicationStatus status) {
        Application application = new Application();
        application.setStudentId(studentId);
        application.setDriveId(driveId);
        application.setStatus(status);
        return applicationRepository.save(application);
    }

    private String applyJson(Long driveId) {
        return """
                {"driveId":%d}
                """.formatted(driveId);
    }
}
