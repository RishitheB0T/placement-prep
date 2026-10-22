package com.rishikesh.placementprep.modules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

    /** A bare numeric id is not something the cell can review anybody on. */
    @Test
    void theApplicantListLabelsEveryRowWithTheStudentsEmail() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/drive/" + driveId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentEmail").value("student@cse.nits.ac.in"));
    }

    /**
     * The other direction: a student's own list leaves the email off, because they
     * already know it and filling it in would cost a lookup per row for nothing.
     */
    @Test
    void myOwnApplicationsCarryNoStudentEmail() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentEmail").value(org.hamcrest.Matchers.nullValue()));
    }

    // ------------------------------------------------------------------ Stats

    @Test
    void theCellSeesCountsByStatusAndWhatIsWaitingPerDrive() throws Exception {
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);
        seedApplication(secondStudentId, driveId, ApplicationStatus.SHORTLISTED);

        mockMvc.perform(get("/api/applications/stats").header(HttpHeaders.AUTHORIZATION, bearer(picToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.APPLIED").value(1))
                .andExpect(jsonPath("$.byStatus.SHORTLISTED").value(1))
                // Every status is present, zero included.
                .andExpect(jsonPath("$.byStatus.SELECTED").value(0))
                .andExpect(jsonPath("$.awaitingReviewByDrive['" + driveId + "']").value(1));
    }

    @Test
    void aStudentCannotSeeTheCellsCounts() throws Exception {
        mockMvc.perform(get("/api/applications/stats").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isForbidden());
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

    // ------------------------------------------- A recruiter's own drives only

    /** A company's recruiter reviews applicants to the drives they posted. */
    @Test
    void aRecruiterCanSeeApplicantsToTheirOwnDriveAndShortlistThem() throws Exception {
        Long recruiterId = createUser("hr@zoho.com", Role.RECRUITER).getId();
        String recruiterToken = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);
        Long ownDrive = seedDrive("Zoho", recruiterId).getId();
        Application application = seedApplication(studentId, ownDrive, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/drive/" + ownDrive)
                        .header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentEmail").value("student@cse.nits.ac.in"));

        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHORTLISTED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHORTLISTED"));
    }

    /**
     * Another company's drive - here the cell's seeded one - is invisible to a recruiter:
     * not on the board, a 404 by id, and its applicants and decisions closed with a 404.
     */
    @Test
    void aRecruiterCannotSeeOrTouchSomebodyElsesDrive() throws Exception {
        createUser("hr@zoho.com", Role.RECRUITER);
        String recruiterToken = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/drives/" + driveId).header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/drives/" + driveId).header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/applications/drive/" + driveId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"REJECTED"}
                                """))
                .andExpect(status().isNotFound());

        // Nothing changed: the drive is still there, and the decision was never made.
        assertThat(driveRepository.existsById(driveId)).isTrue();
        assertThat(applicationRepository.findById(application.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
    }

    @Test
    void aDrivePostedByARecruiterIsTheirsAndOnlyTheirs() throws Exception {
        createUser("hr@zoho.com", Role.RECRUITER);
        createUser("hr@infosys.com", Role.RECRUITER);
        String zoho = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);
        String infosys = jwtService.generateToken("hr@infosys.com", Role.RECRUITER);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(zoho))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyName":"Zoho","role":"SDE","ctc":900000,"tier":1,"cgpaCutoff":7.5,
                                 "backlogsAllowed":false,"eligibleBranches":["CSE"],
                                 "applicationDeadline":"%s"}
                                """.formatted(FUTURE)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(zoho)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyName").value("Zoho"));
        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(infosys)))
                .andExpect(jsonPath("$.length()").value(0));
        // The cell and students still see the whole board: the seeded drive and Zoho's.
        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(picToken)))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void aRecruitersCountsCoverOnlyTheirOwnDrives() throws Exception {
        Long recruiterId = createUser("hr@zoho.com", Role.RECRUITER).getId();
        String recruiterToken = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);
        Long ownDrive = seedDrive("Zoho", recruiterId).getId();
        seedApplication(studentId, ownDrive, ApplicationStatus.APPLIED);
        seedApplication(secondStudentId, driveId, ApplicationStatus.APPLIED);   // the cell's drive

        mockMvc.perform(get("/api/applications/stats").header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.APPLIED").value(1))
                .andExpect(jsonPath("$.awaitingReviewByDrive['" + ownDrive + "']").value(1))
                .andExpect(jsonPath("$.awaitingReviewByDrive['" + driveId + "']").doesNotExist());
    }

    /** A recruiter with no drives yet gets zeros, not an error from an empty IN list. */
    @Test
    void aRecruiterWithNoDrivesSeesZeroCounts() throws Exception {
        createUser("hr@zoho.com", Role.RECRUITER);
        String recruiterToken = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);
        seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(get("/api/applications/stats").header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.APPLIED").value(0));
    }

    /** Job-description search spans every company, so it is closed to recruiters. */
    @Test
    void aRecruiterCannotSearchJobDescriptions() throws Exception {
        createUser("hr@zoho.com", Role.RECRUITER);
        String recruiterToken = jwtService.generateToken("hr@zoho.com", Role.RECRUITER);

        mockMvc.perform(get("/api/job-descriptions/search").param("q", "backend")
                        .header(HttpHeaders.AUTHORIZATION, bearer(recruiterToken)))
                .andExpect(status().isForbidden());
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
     * Withdrawing a rejection would let a re-application reopen it as APPLIED, undoing the
     * decision - so a decided application cannot be withdrawn.
     */
    @Test
    void aDecidedApplicationCannotBeWithdrawn() throws Exception {
        Application rejected = seedApplication(studentId, driveId, ApplicationStatus.REJECTED);

        mockMvc.perform(post("/api/applications/" + rejected.getId() + "/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isConflict());

        assertThat(applicationRepository.findById(rejected.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.REJECTED);
    }

    // ------------------------------------------------------------ Apply rules

    @Test
    void applyingToADriveThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(999_999L)))
                .andExpect(status().isNotFound());
    }

    @Test
    void applyingAfterTheDeadlineIs409() throws Exception {
        Drive closed = seedDrive("Closed Co");
        closed.setApplicationDeadline(Instant.now().minus(1, ChronoUnit.DAYS));
        driveRepository.save(closed);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(closed.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("closed")));
        assertThat(applicationRepository.count()).isZero();
    }

    /** The API enforces eligibility; hiding the Apply button is not the only guard. */
    @Test
    void applyingToADriveTheStudentIsNotEligibleForIs409() throws Exception {
        Drive strict = seedDrive("Strict Co");
        strict.setCgpaCutoff(new BigDecimal("9.5"));
        driveRepository.save(strict);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(studentToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(strict.getId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("eligibility")));
        assertThat(applicationRepository.count()).isZero();
    }

    @Test
    void applyingWithAnIncompleteProfileIs409() throws Exception {
        User blank = createUser("blank@cse.nits.ac.in", Role.STUDENT);
        blank.setCgpa(null);
        userRepository.save(blank);
        String blankToken = jwtService.generateToken("blank@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/applications").header(HttpHeaders.AUTHORIZATION, bearer(blankToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applyJson(driveId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("profile")));
    }

    // ---------------------------------------------------------- Status rules

    /** WITHDRAWN is the student's decision, made through /withdraw - never the hiring side's. */
    @Test
    void theHiringSideCannotSetWithdrawn() throws Exception {
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"WITHDRAWN"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aWithdrawnApplicationCannotBeDecided() throws Exception {
        Application withdrawn = seedApplication(studentId, driveId, ApplicationStatus.WITHDRAWN);

        mockMvc.perform(patch("/api/applications/" + withdrawn.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHORTLISTED"}
                                """))
                .andExpect(status().isConflict());

        assertThat(applicationRepository.findById(withdrawn.getId()).orElseThrow().getStatus())
                .isEqualTo(ApplicationStatus.WITHDRAWN);
    }

    /** Shortlisting someone puts a message in their notification bell. */
    @Test
    void aStatusChangeNotifiesTheStudent() throws Exception {
        Application application = seedApplication(studentId, driveId, ApplicationStatus.APPLIED);

        mockMvc.perform(patch("/api/applications/" + application.getId() + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(picToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SHORTLISTED"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/notifications/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("STATUS_CHANGED"))
                .andExpect(jsonPath("$[0].message").value(org.hamcrest.Matchers.containsString("shortlisted for Zoho")))
                .andExpect(jsonPath("$[0].read").value(false));
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

    /**
     * Student accounts get a complete profile that qualifies for the seeded drive (CSE,
     * CGPA 8.5 against a 7.5 cutoff, no backlogs) - applying checks eligibility, so a
     * student without marks could not apply to anything.
     */
    private User createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        if (role == Role.STUDENT || role == Role.TNP_COORDINATOR) {
            user.setCgpa(new BigDecimal("8.50"));
            user.setBranch("CSE");
            user.setTenthPercentage(new BigDecimal("90"));
            user.setTwelfthPercentage(new BigDecimal("88"));
        }
        return userRepository.save(user);
    }

    private Drive seedDrive(String company) {
        return seedDrive(company, null);
    }

    private Drive seedDrive(String company, Long createdBy) {
        Drive drive = new Drive();
        drive.setCreatedBy(createdBy);
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
