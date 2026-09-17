package com.rishikesh.placementprep.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
                        .content(credentials("student@cse.nits.ac.in", "a-good-password")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.email").value("student@cse.nits.ac.in"))
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.existsByEmail("student@cse.nits.ac.in")).isTrue();
    }

    /** The password must never be stored in a form anyone could read back. */
    @Test
    void registerStoresAHashRatherThanThePassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "a-good-password")))
                .andExpect(status().isCreated());

        String stored = userRepository.findByEmail("student@cse.nits.ac.in").orElseThrow()
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
                {"email":"sneaky@cse.nits.ac.in","password":"a-good-password","role":"TNP_PIC"}
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tryingToBeAdmin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.findByEmail("sneaky@cse.nits.ac.in").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    @Test
    void registerRejectsADuplicateEmailWith409() throws Exception {
        registerStudent("student@cse.nits.ac.in", "a-good-password");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "another-password")))
                .andExpect(status().isConflict());

        assertThat(userRepository.count()).isEqualTo(1);
    }

    /** Registering with a different casing must not create a second account. */
    @Test
    void registerTreatsEmailCaseInsensitively() throws Exception {
        registerStudent("student@cse.nits.ac.in", "a-good-password");

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("Student@CSE.Nits.AC.in", "another-password")))
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
    void registerRejectsAnEmailOutsideTheCollegeDomain() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("someone@gmail.com", "a-good-password")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.existsByEmail("someone@gmail.com")).isFalse();
    }

    /**
     * A 400 has to say which field was wrong, otherwise the form can only tell the user
     * that something failed. Without the advice in common, all of this is discarded and
     * the body says nothing beyond "Invalid request content.".
     */
    @Test
    void aRejectedFieldIsNamedInTheResponse() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("someone@gmail.com", "a-good-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.email").value(
                        "Registration is open only to college email addresses ending in .nits.ac.in"));
    }

    /** The message must never quote back what was submitted, because that includes it. */
    @Test
    void aRejectedPasswordIsNamedWithoutEchoingIt() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("short"))));
    }

    /** Every college address sits under a department subdomain, so this is the shape. */
    @Test
    void registerAcceptsADepartmentalSubdomain() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("learner@ece.nits.ac.in", "a-good-password")))
                .andExpect(status().isCreated());
    }

    /**
     * The bare domain is rejected because the college does not issue addresses in that
     * shape. Accepting it would only widen what the check lets through, for no real
     * address it would ever admit.
     */
    @Test
    void registerRejectsTheBareCollegeDomain() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("someone@nits.ac.in", "a-good-password")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.existsByEmail("someone@nits.ac.in")).isFalse();
    }

    /**
     * The regex requires a dot immediately before nits.ac.in. Without that boundary a
     * domain someone else could register, such as notnits.ac.in, would end with the right
     * characters and be accepted.
     */
    @Test
    void registerRejectsALookalikeDomain() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("attacker@notnits.ac.in", "a-good-password")))
                .andExpect(status().isBadRequest());
    }

    /** A suffix check anchored only at the front would let this through. */
    @Test
    void registerRejectsADomainThatMerelyStartsWithTheCollegeDomain() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("attacker@nits.ac.in.example.com", "a-good-password")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerRejectsAShortPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "short")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------- Login

    @Test
    void loginReturnsATokenForCorrectCredentials() throws Exception {
        registerStudent("student@cse.nits.ac.in", "a-good-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "a-good-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.role").value("STUDENT"));
    }

    @Test
    void loginRejectsAWrongPasswordWith401() throws Exception {
        registerStudent("student@cse.nits.ac.in", "a-good-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("student@cse.nits.ac.in", "wrong-password")))
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
                        .content(credentials("nobody@cse.nits.ac.in", "a-good-password")))
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
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** The rule that matters: students may look, but may not publish. */
    @Test
    void aStudentCannotCreateADrive() throws Exception {
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/drives")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(driveJson()))
                .andExpect(status().isForbidden());

        assertThat(driveRepository.count()).isZero();
    }

    @Test
    void aStudentCannotDeleteADrive() throws Exception {
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(delete("/api/drives/1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdminCanCreateADrive() throws Exception {
        String token = tokenFor("tnp@tnp.nits.ac.in", Role.TNP_PIC);

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
        String forged = attacker.generateToken("tnp@tnp.nits.ac.in", Role.TNP_PIC);

        mockMvc.perform(get("/api/drives").header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRejected() throws Exception {
        createUser("student@cse.nits.ac.in", Role.STUDENT);
        // A negative lifetime produces a token that expired before it was even issued.
        JwtService expiring = new JwtService(new JwtProperties(
                "local-development-only-secret-change-me-in-production", -1000L));
        String expired = expiring.generateToken("student@cse.nits.ac.in", Role.STUDENT);

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
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("student@cse.nits.ac.in"))
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
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

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
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("11.0", "CSE", "80", "75", 0)))
                .andExpect(status().isBadRequest());
    }

    /** A profile update must not be a back door to becoming an administrator. */
    @Test
    void updatingTheProfileCannotChangeTheRole() throws Exception {
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        String withRole = """
                {"cgpa":8.5,"branch":"CSE","tenthPercentage":80,"twelfthPercentage":75,
                 "backlogs":0,"role":"TNP_PIC"}
                """;

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withRole))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STUDENT"));

        assertThat(userRepository.findByEmail("student@cse.nits.ac.in").orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    /** One student must never be able to read or write another student's profile. */
    @Test
    void theProfileEndpointOnlyEverTouchesTheCallersOwnAccount() throws Exception {
        createUser("other@cse.nits.ac.in", Role.STUDENT);
        String token = tokenFor("student@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profileJson("9.1", "ECE", "90", "88", 1)))
                .andExpect(status().isOk());

        assertThat(userRepository.findByEmail("other@cse.nits.ac.in").orElseThrow().getCgpa())
                .isNull();
    }

    // --------------------------------------------------------------- Promotion

    @Test
    void thePersonInChargeCanPromoteAStudentToCoordinator() throws Exception {
        String pic = tokenFor("pic@tnp.nits.ac.in", Role.TNP_PIC);
        Long targetId = idOf("target@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/users/" + targetId + "/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pic))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("TNP_COORDINATOR"));

        assertThat(userRepository.findById(targetId).orElseThrow().getRole())
                .isEqualTo(Role.TNP_COORDINATOR);
    }

    /** The whole point of splitting the roles: the cell cannot staff itself. */
    @Test
    void aCoordinatorCannotPromoteAnybody() throws Exception {
        String coordinator = tokenFor("coordinator@tnp.nits.ac.in", Role.TNP_COORDINATOR);
        Long targetId = idOf("target@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/users/" + targetId + "/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + coordinator))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(targetId).orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    @Test
    void aStudentCannotPromoteThemselves() throws Exception {
        String student = tokenFor("student@cse.nits.ac.in", Role.STUDENT);
        Long selfId = userRepository.findByEmail("student@cse.nits.ac.in").orElseThrow().getId();

        mockMvc.perform(post("/api/users/" + selfId + "/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + student))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(selfId).orElseThrow().getRole())
                .isEqualTo(Role.STUDENT);
    }

    @Test
    void promotingWithoutATokenIs401() throws Exception {
        Long targetId = idOf("target@cse.nits.ac.in", Role.STUDENT);

        mockMvc.perform(post("/api/users/" + targetId + "/promote"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void promotingAnUnknownAccountIs404() throws Exception {
        String pic = tokenFor("pic@tnp.nits.ac.in", Role.TNP_PIC);

        mockMvc.perform(post("/api/users/999999/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pic))
                .andExpect(status().isNotFound());
    }

    @Test
    void promotingSomebodyAlreadyACoordinatorIs409() throws Exception {
        String pic = tokenFor("pic@tnp.nits.ac.in", Role.TNP_PIC);
        Long targetId = idOf("coordinator@tnp.nits.ac.in", Role.TNP_COORDINATOR);

        mockMvc.perform(post("/api/users/" + targetId + "/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pic))
                .andExpect(status().isConflict());
    }

    /** Promotion must never be usable as a back door to demote the person in charge. */
    @Test
    void promotingThePersonInChargeIs409AndLeavesTheRoleAlone() throws Exception {
        String pic = tokenFor("pic@tnp.nits.ac.in", Role.TNP_PIC);
        Long otherPicId = idOf("other-pic@tnp.nits.ac.in", Role.TNP_PIC);

        mockMvc.perform(post("/api/users/" + otherPicId + "/promote")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pic))
                .andExpect(status().isConflict());

        assertThat(userRepository.findById(otherPicId).orElseThrow().getRole())
                .isEqualTo(Role.TNP_PIC);
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

    /** Creates an account and returns its generated id, for endpoints that take one. */
    private Long idOf(String email, Role role) {
        createUser(email, role);
        return userRepository.findByEmail(email).orElseThrow().getId();
    }
}
