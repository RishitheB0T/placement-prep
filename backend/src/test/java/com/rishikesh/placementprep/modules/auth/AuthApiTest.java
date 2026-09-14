package com.rishikesh.placementprep.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.rishikesh.placementprep.infrastructure.security.JwtProperties;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;

/**
 * Registration, login, and the authorisation rules protecting /api/drives.
 *
 * <p>The second half matters more than the first. A broken registration form is obvious
 * the moment anyone tries it; a role rule that quietly lets a student delete drives is
 * not, and nothing else in the suite would catch it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void startFromAnEmptyTable() {
        driveRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ------------------------------------------------------------ Registration

    @Test
    void registerReturns201AndAToken() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "a-good-password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("student@college.edu"))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.existsByEmail("student@college.edu")).isTrue();
    }

    /** The password must never be stored in a form anyone could read back. */
    @Test
    void registerStoresAHashRatherThanThePassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "a-good-password")))
                .andExpect(status().isCreated());

        String stored = userRepository.findByEmail("student@college.edu").orElseThrow()
                .getPasswordHash();

        assertThat(stored).isNotEqualTo("a-good-password");
        assertThat(stored).startsWith("$2");   // BCrypt hashes begin with $2a/$2b/$2y
        assertThat(passwordEncoder.matches("a-good-password", stored)).isTrue();
    }

    /**
     * The critical privilege-escalation check. A role sent by the client must be ignored,
     * otherwise anyone could sign up as an administrator and publish fake drives.
     */
    @Test
    void registerIgnoresAnyRoleSuppliedByTheClient() throws Exception {
        String tryingToBeAdmin = """
                {"email":"sneaky@college.edu","password":"a-good-password","role":"TNP_ADMIN"}
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tryingToBeAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.findByEmail("sneaky@college.edu").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    @Test
    void registerRejectsADuplicateEmailWith409() throws Exception {
        registerStudent("student@college.edu", "a-good-password");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "another-password")))
                .andExpect(status().isConflict());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    /** Registering with a different casing must not create a second account. */
    @Test
    void registerTreatsEmailCaseInsensitively() throws Exception {
        registerStudent("student@college.edu", "a-good-password");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("Student@College.edu", "another-password")))
                .andExpect(status().isConflict());
    }

    @Test
    void registerRejectsAMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("not-an-email", "a-good-password")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsAShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "short")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------- Login

    @Test
    void loginReturnsATokenForCorrectCredentials() throws Exception {
        registerStudent("student@college.edu", "a-good-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "a-good-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    void loginRejectsAWrongPasswordWith401() throws Exception {
        registerStudent("student@college.edu", "a-good-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@college.edu", "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    /**
     * An unknown account and a wrong password must be indistinguishable, or the endpoint
     * becomes a way to discover which email addresses are registered.
     */
    @Test
    void loginRejectsAnUnknownAccountWithTheSame401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("nobody@college.edu", "a-good-password")))
                .andExpect(status().isUnauthorized());
    }

    // --------------------------------------------------- Protecting the drives

    @Test
    void readingDrivesWithoutATokenIs401() throws Exception {
        mockMvc.perform(get("/api/drives"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readingDrivesWithAStudentTokenIsAllowed() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** The rule that matters: students may look, but may not publish. */
    @Test
    void aStudentCannotCreateADrive() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(post("/api/drives")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson()))
                .andExpect(status().isForbidden());

        assertThat(driveRepository.count()).isZero();
    }

    @Test
    void aStudentCannotDeleteADrive() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(delete("/api/drives/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanCreateADrive() throws Exception {
        String token = tokenFor("tnp@college.edu", Role.TNP_ADMIN);

        mockMvc.perform(post("/api/drives")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson()))
                .andExpect(status().isCreated());
    }

    // ----------------------------------------------------------- Token handling

    @Test
    void aGarbageTokenIs401RatherThan500() throws Exception {
        mockMvc.perform(get("/api/drives")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A token signed with someone else's key must be refused. This is the whole point of
     * the signature: without the check, anyone could mint themselves an admin token.
     */
    @Test
    void aTokenSignedWithADifferentSecretIsRejected() throws Exception {
        JwtService attacker = new JwtService(new JwtProperties(
                "a-totally-different-secret-that-is-long-enough-to-be-valid", 86400000L));
        String forged = attacker.generateToken("tnp@college.edu", Role.TNP_ADMIN);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        createUser("student@college.edu", Role.STUDENT);
        // A negative lifetime produces a token that expired before it was even issued.
        JwtService expiring = new JwtService(new JwtProperties(
                "local-development-only-secret-change-me-in-production", -1000L));
        String expired = expiring.generateToken("student@college.edu", Role.STUDENT);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    /** The health endpoint stays open so container orchestration can probe it. */
    @Test
    void healthRemainsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }


    // ---------------------------------------------------------------- Profile

    @Test
    void meReturnsTheSignedInUserAndNoPassword() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("student@college.edu"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void meRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updatingTheProfileMakesItComplete() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("8.5", "cse", "80", "75", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cgpa").value(8.5))
                .andExpect(jsonPath("$.complete").value(true))
                // Stored upper-cased so the eligibility comparison stays consistent.
                .andExpect(jsonPath("$.branch").value("CSE"));
    }

    @Test
    void updatingTheProfileRejectsACgpaAboveTen() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("11.0", "CSE", "80", "75", 0)))
                .andExpect(status().isBadRequest());
    }

    /** A profile update must not be a back door to becoming an administrator. */
    @Test
    void updatingTheProfileCannotChangeTheRole() throws Exception {
        String token = tokenFor("student@college.edu", Role.STUDENT);

        String withRole = """
                {"cgpa":8.5,"branch":"CSE","tenthPercentage":80,"twelfthPercentage":75,
                 "backlogs":0,"role":"TNP_ADMIN"}
                """;

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withRole))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.findByEmail("student@college.edu").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    /** One student must never be able to read or write another student's profile. */
    @Test
    void theProfileEndpointOnlyEverTouchesTheCallersOwnAccount() throws Exception {
        createUser("other@college.edu", Role.STUDENT);
        String token = tokenFor("student@college.edu", Role.STUDENT);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("9.1", "ECE", "90", "88", 1)))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail("other@college.edu").orElseThrow().getCgpa())
                .isNull();
    }

    // ----------------------------------------------------------------- Helpers

    private String credentials(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private String driveJson() {
        return """
                {"companyName":"Zoho","role":"SDE","ctc":900000,"tier":1,"cgpaCutoff":7.5,
                 "backlogsAllowed":false,"eligibleBranches":["CSE"],
                 "applicationDeadline":"%s"}
                """.formatted(Instant.now().plus(30, ChronoUnit.DAYS));
    }

    private void registerStudent(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isCreated());
    }

    private String profileJson(String cgpa, String branch, String tenth, String twelfth, int backlogs) {
        return """
                {"cgpa":%s,"branch":"%s","tenthPercentage":%s,"twelfthPercentage":%s,"backlogs":%d}
                """.formatted(cgpa, branch, tenth, twelfth, backlogs);
    }

    private void createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        userRepository.save(user);
    }

    /** Creates the account and returns a token that will actually resolve to it. */
    private String tokenFor(String email, Role role) {
        createUser(email, role);
        return jwtService.generateToken(email, role);
    }
}
