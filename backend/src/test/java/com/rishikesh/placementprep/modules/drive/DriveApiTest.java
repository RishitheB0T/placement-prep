package com.rishikesh.placementprep.modules.drive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;

/**
 * End-to-end tests for /api/drives against a real PostgreSQL container.
 *
 * <p>These cover the behaviour that cannot be checked by reading the code: the status
 * codes the API really returns, and the eligibility rules, whose edge cases (a NULL
 * cutoff, an expired deadline, a lowercase branch) all fail silently rather than loudly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DriveApiTest {

    private static final Instant FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);
    private static final Instant PAST = Instant.now().minus(30, ChronoUnit.DAYS);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    /** Real signed tokens, not mocks, so the JWT filter and rule chain are exercised. */
    private String studentToken;
    private String adminToken;

    @BeforeEach
    void startFromAnEmptyTable() {
        driveRepository.deleteAll();
        userRepository.deleteAll();

        createUser("student@college.edu", Role.STUDENT);
        createUser("tnp@college.edu", Role.TNP_ADMIN);

        studentToken = jwtService.generateToken("student@college.edu", Role.STUDENT);
        adminToken = jwtService.generateToken("tnp@college.edu", Role.TNP_ADMIN);
    }

    // ------------------------------------------------------------------ Create

    @Test
    void createReturns201AndPersistsTheDrive() throws Exception {
        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("Zoho", FUTURE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.companyName").value("Zoho"));

        assertThat(driveRepository.count()).isEqualTo(1);
    }

    @Test
    void createRejectsABlankCompanyName() throws Exception {
        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("", FUTURE)))
                .andExpect(status().isBadRequest());

        assertThat(driveRepository.count()).isZero();
    }

    /** tier is NOT NULL in the database; without @NotNull this surfaced as a 500. */
    @Test
    void createRejectsAMissingTierWithA400NotA500() throws Exception {
        String noTier = """
                {"companyName":"Acme","role":"SDE","ctc":500000,"cgpaCutoff":7.0,"backlogsAllowed":false,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s"}
                """.formatted(FUTURE);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noTier))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------- Read

    @Test
    void listReturnsEveryDrive() throws Exception {
        seed("Zoho", "7.5", List.of("CSE"), FUTURE);
        seed("Infosys", "6.0", List.of("ECE"), FUTURE);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getByIdReturns404ForAnUnknownId() throws Exception {
        mockMvc.perform(get("/api/drives/999999").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ Update

    @Test
    void updateReplacesTheStoredFields() throws Exception {
        Drive existing = seed("Zoho", "7.5", List.of("CSE"), FUTURE);

        mockMvc.perform(put("/api/drives/" + existing.getId()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("Zoho Corp", FUTURE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("Zoho Corp"));
    }

    @Test
    void updateReturns404ForAnUnknownId() throws Exception {
        mockMvc.perform(put("/api/drives/999999").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("Zoho", FUTURE)))
                .andExpect(status().isNotFound());
    }

    /** Regression test: @Future on the deadline used to make expired drives uneditable. */
    @Test
    void anExpiredDriveCanStillBeEdited() throws Exception {
        Drive expired = seed("OldDrive", "7.0", List.of("CSE"), PAST);

        mockMvc.perform(put("/api/drives/" + expired.getId()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("OldDrive Renamed", PAST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("OldDrive Renamed"));
    }

    @Test
    void anInvalidUpdateLeavesTheStoredDriveUntouched() throws Exception {
        Drive existing = seed("Zoho", "7.5", List.of("CSE"), FUTURE);

        mockMvc.perform(put("/api/drives/" + existing.getId()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson("", FUTURE)))
                .andExpect(status().isBadRequest());

        assertThat(driveRepository.findById(existing.getId()).orElseThrow().getCompanyName())
                .isEqualTo("Zoho");
    }

    // ------------------------------------------------------------------ Delete

    @Test
    void deleteReturns204AndTheDriveIsThenGone() throws Exception {
        Drive existing = seed("Zoho", "7.5", List.of("CSE"), FUTURE);

        mockMvc.perform(delete("/api/drives/" + existing.getId()).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/drives/" + existing.getId()).header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns404ForAnUnknownId() throws Exception {
        mockMvc.perform(delete("/api/drives/999999").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------- Eligibility

    @Test
    void eligibleExcludesDrivesWhoseCutoffTheStudentMisses() throws Exception {
        seed("HighBar", "9.5", List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "CSE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eligibleExcludesOtherBranches() throws Exception {
        seed("MechOnly", "6.0", List.of("MEC"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eligibleExcludesDrivesWhoseDeadlineHasPassed() throws Exception {
        seed("Expired", "6.0", List.of("CSE"), PAST);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** A NULL cutoff means "no CGPA requirement" and must not be silently dropped. */
    @Test
    void eligibleIncludesDrivesWithNoCgpaCutoff() throws Exception {
        seed("NoCutoff", null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyName").value("NoCutoff"));
    }

    @Test
    void eligibleMatchesTheBranchCaseInsensitively() throws Exception {
        seed("Zoho", "7.5", List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "cse"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * Replaces an older test that checked a cgpa query parameter outside 0-10. That range
     * is now enforced when the profile is saved, so the interesting case here is a student
     * who has not filled their profile in at all.
     */
    @Test
    void eligibleReturns409WhenTheProfileIsIncomplete() throws Exception {
        User student = userRepository.findByEmail("student@college.edu").orElseThrow();
        student.setCgpa(null);
        student.setBranch(null);
        student.setTenthPercentage(null);
        student.setTwelfthPercentage(null);
        userRepository.save(student);

        mockMvc.perform(get("/api/drives/eligible")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isConflict());
    }


    @Test
    void eligibleExcludesDrivesWhoseTenthCutoffTheStudentMisses() throws Exception {
        seed("Strict10th", "6.0", "85", null, false, null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "70", "80", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eligibleExcludesDrivesWhoseTwelfthCutoffTheStudentMisses() throws Exception {
        seed("Strict12th", "6.0", null, "85", false, null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "70", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** A NULL percentage cutoff means the drive sets no requirement for it. */
    @Test
    void eligibleIncludesDrivesWithNoPercentageCutoffs() throws Exception {
        seed("NoPercentageBar", "6.0", null, null, false, null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "45", "45", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aStudentWithBacklogsIsExcludedFromDrivesThatForbidThem() throws Exception {
        seed("NoBacklogs", "6.0", null, null, false, null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 2, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aStudentWithBacklogsStillSeesDrivesThatPermitThem() throws Exception {
        seed("BacklogsOk", "6.0", null, null, true, 3, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 2, "CSE"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].companyName").value("BacklogsOk"));
    }

    @Test
    void aStudentWithoutBacklogsSeesDrivesRegardlessOfTheBacklogRule() throws Exception {
        seed("NoBacklogs", "6.0", null, null, false, null, List.of("CSE"), FUTURE);
        seed("BacklogsOk", "6.0", null, null, true, 3, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 0, "CSE"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void createRejectsAMissingBacklogsAllowedFlag() throws Exception {
        String noFlag = """
                {"companyName":"Acme","role":"SDE","ctc":500000,"tier":1,"cgpaCutoff":7.0,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s"}
                """.formatted(FUTURE);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noFlag))
                .andExpect(status().isBadRequest());
    }


    @Test
    void aStudentIsExcludedWhenTheyExceedTheDrivesBacklogCeiling() throws Exception {
        seed("MaxTwo", "6.0", null, null, true, 2, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 5, "CSE"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aStudentWithinTheDrivesBacklogCeilingIsIncluded() throws Exception {
        seed("MaxTwo", "6.0", null, null, true, 2, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 2, "CSE"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].maxBacklogs").value(2));
    }

    /** A permitted-backlogs drive with no stated ceiling imposes none. */
    @Test
    void aNullCeilingOnAPermissiveDriveMeansUnlimited() throws Exception {
        seed("NoCeiling", "6.0", null, null, true, null, List.of("CSE"), FUTURE);

        mockMvc.perform(eligibleFor("8.5", "80", "80", 9, "CSE"))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /** Cross-field rule: saying backlogs are allowed without a number is rejected. */
    @Test
    void createRejectsBacklogsAllowedWithoutAMaxBacklogs() throws Exception {
        String noMax = """
                {"companyName":"Acme","role":"SDE","ctc":500000,"tier":1,"cgpaCutoff":7.0,
                 "backlogsAllowed":true,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s"}
                """.formatted(FUTURE);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noMax))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createAcceptsBacklogsAllowedWithAMaxBacklogs() throws Exception {
        String withMax = """
                {"companyName":"Acme","role":"SDE","ctc":500000,"tier":1,"cgpaCutoff":7.0,
                 "backlogsAllowed":true,"maxBacklogs":2,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s"}
                """.formatted(FUTURE);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withMax))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxBacklogs").value(2));
    }

    @Test
    void createRejectsANegativeMaxBacklogs() throws Exception {
        String negative = """
                {"companyName":"Acme","role":"SDE","ctc":500000,"tier":1,"cgpaCutoff":7.0,
                 "backlogsAllowed":true,"maxBacklogs":-1,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s"}
                """.formatted(FUTURE);

        mockMvc.perform(post("/api/drives").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(negative))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------- Helpers


    /**
     * Eligibility now comes from the signed-in student's stored profile rather than query
     * parameters, so each test writes the profile it wants first and then asks.
     */
    private MockHttpServletRequestBuilder eligibleFor(String cgpa, String tenth, String twelfth,
                                                     int backlogs, String branch) {
        User student = userRepository.findByEmail("student@college.edu").orElseThrow();
        student.setCgpa(new BigDecimal(cgpa));
        student.setTenthPercentage(new BigDecimal(tenth));
        student.setTwelfthPercentage(new BigDecimal(twelfth));
        student.setBacklogs(backlogs);
        student.setBranch(branch.toUpperCase());
        userRepository.save(student);

        return get("/api/drives/eligible")
                .header(HttpHeaders.AUTHORIZATION, bearer(studentToken));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private void createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        userRepository.save(user);
    }

    private Drive seed(String company, String cgpaCutoff, List<String> branches, Instant deadline) {
        return seed(company, cgpaCutoff, null, null, false, null, branches, deadline);
    }

    private Drive seed(String company, String cgpaCutoff, String tenthCutoff,
                       String twelfthCutoff, boolean backlogsAllowed, Integer maxBacklogs,
                       List<String> branches, Instant deadline) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole("SDE");
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(cgpaCutoff == null ? null : new BigDecimal(cgpaCutoff));
        drive.setTenthCutoff(tenthCutoff == null ? null : new BigDecimal(tenthCutoff));
        drive.setTwelfthCutoff(twelfthCutoff == null ? null : new BigDecimal(twelfthCutoff));
        drive.setBacklogsAllowed(backlogsAllowed);
        drive.setMaxBacklogs(maxBacklogs);
        drive.setEligibleBranches(branches);
        drive.setApplicationDeadline(deadline);
        return driveRepository.save(drive);
    }

    private String driveJson(String company, Instant deadline) {
        return """
                {"companyName":"%s","role":"SDE","ctc":900000,"tier":1,"cgpaCutoff":7.5,
                 "backlogsAllowed":false,
                 "eligibleBranches":["CSE"],"applicationDeadline":"%s","description":"seeded"}
                """.formatted(company, deadline);
    }
}
