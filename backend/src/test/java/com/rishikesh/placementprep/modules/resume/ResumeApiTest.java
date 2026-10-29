package com.rishikesh.placementprep.modules.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.rishikesh.placementprep.modules.resume.model.Resume;
import com.rishikesh.placementprep.modules.resume.model.ResumeStatus;
import com.rishikesh.placementprep.modules.resume.repository.ResumeRepository;
import com.rishikesh.placementprep.modules.resume.service.ResumeReviewJob;
import com.rishikesh.placementprep.modules.resume.service.ResumeReviewer;

/**
 * End-to-end tests for /api/resumes and the background review, against a real
 * PostgreSQL container.
 *
 * <p>Two things are swapped out, and only two. ResumeReviewer is a mock, so no test ever
 * calls the real model - a test that needs a network and a paid API key is not one CI
 * can run. And the poll delay is an hour, so the scheduled job never fires on its own
 * mid-test: each test that wants a review to happen calls reviewNext() itself, at the
 * moment it chooses, which is what makes the assertions after it deterministic.
 */
@SpringBootTest(properties = {
        "resume.review.poll-delay=PT1H",
        "resume.storage.directory=target/test-resumes"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ResumeApiTest {

    private static final Instant FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);
    private static final Path STORAGE = Path.of("target/test-resumes");

    /** Starts with the five bytes every PDF starts with, which is all the upload checks. */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n% a stand-in resume for tests\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ResumeReviewJob resumeReviewJob;

    @MockitoBean
    private ResumeReviewer resumeReviewer;

    private String studentToken;
    private String secondStudentToken;
    private String coordinatorToken;
    private String picToken;
    private Long driveId;

    @BeforeEach
    void startFromEmptyTablesAndAnEmptyDirectory() throws IOException {
        resumeRepository.deleteAll();
        driveRepository.deleteAll();
        userRepository.deleteAll();
        FileSystemUtils.deleteRecursively(STORAGE);

        createUser("student@cse.nits.ac.in", Role.STUDENT);
        createUser("second@cse.nits.ac.in", Role.STUDENT);
        createUser("coordinator@tnp.nits.ac.in", Role.TNP_COORDINATOR);
        createUser("pic@tnp.nits.ac.in", Role.TNP_PIC);

        studentToken = jwtService.generateToken("student@cse.nits.ac.in", Role.STUDENT);
        secondStudentToken = jwtService.generateToken("second@cse.nits.ac.in", Role.STUDENT);
        coordinatorToken = jwtService.generateToken("coordinator@tnp.nits.ac.in", Role.TNP_COORDINATOR);
        picToken = jwtService.generateToken("pic@tnp.nits.ac.in", Role.TNP_PIC);

        driveId = seedDrive("Zoho").getId();
    }

    // ----------------------------------------------------------------- Upload

    @Test
    void uploadReturns201AsPendingAndWritesTheFileToDisk() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("my resume.pdf")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.driveId").value(driveId))
                .andExpect(jsonPath("$.originalFilename").value("my resume.pdf"))
                .andExpect(jsonPath("$.sizeBytes").value(PDF_BYTES.length))
                // Where the file lives on the server is never part of the response.
                .andExpect(jsonPath("$.storageKey").doesNotExist());

        Resume saved = resumeRepository.findAll().getFirst();
        Path stored = STORAGE.resolve(saved.getStorageKey());
        assertThat(Files.readAllBytes(stored)).isEqualTo(PDF_BYTES);
    }

    /**
     * The client chooses the original filename, so it is the one thing here that could
     * smuggle "../" into a path. The stored file must be named by the server instead.
     */
    @Test
    void theStoredFileIsNamedByTheServerNotTheClient() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("../../evil.pdf")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.originalFilename").value("evil.pdf"));

        Resume saved = resumeRepository.findAll().getFirst();
        assertThat(saved.getStorageKey()).doesNotContain("evil").endsWith(".pdf");
        assertThat(Files.exists(STORAGE.resolve(saved.getStorageKey()))).isTrue();
    }

    @Test
    void aCoordinatorCanUploadBecauseACoordinatorIsAStudent() throws Exception {
        mockMvc.perform(upload(coordinatorToken, driveId, pdf("resume.pdf")))
                .andExpect(status().isCreated());
    }

    @Test
    void thePersonInChargeCannotUpload() throws Exception {
        mockMvc.perform(upload(picToken, driveId, pdf("resume.pdf")))
                .andExpect(status().isForbidden());

        assertThat(resumeRepository.count()).isZero();
    }

    /** The extension and content type both say PDF; the bytes say otherwise. */
    @Test
    void aFileThatIsNotReallyAPdfIs400() throws Exception {
        MockMultipartFile disguised = new MockMultipartFile("file", "resume.pdf", "application/pdf",
                "just some text pretending".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(upload(studentToken, driveId, disguised))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Only PDF resumes are accepted"));

        assertThat(resumeRepository.count()).isZero();
    }

    @Test
    void anEmptyFileIs400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[0]);

        mockMvc.perform(upload(studentToken, driveId, empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadingForADriveThatDoesNotExistIs404() throws Exception {
        mockMvc.perform(upload(studentToken, 999_999L, pdf("resume.pdf")))
                .andExpect(status().isNotFound());

        assertThat(resumeRepository.count()).isZero();
    }

    /** Otherwise a few impatient clicks would each queue their own model call. */
    @Test
    void aSecondUploadForTheSameDriveWhileTheFirstIsPendingIs409() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("first.pdf")))
                .andExpect(status().isCreated());

        mockMvc.perform(upload(studentToken, driveId, pdf("second.pdf")))
                .andExpect(status().isConflict());

        assertThat(resumeRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------- Read

    @Test
    void myResumesOnlyEverShowsMyOwn() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("mine.pdf"))).andExpect(status().isCreated());
        mockMvc.perform(upload(secondStudentToken, driveId, pdf("theirs.pdf"))).andExpect(status().isCreated());

        mockMvc.perform(get("/api/resumes/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].originalFilename").value("mine.pdf"));
    }

    /** 404, not 403: a 403 would confirm that a resume with that id exists at all. */
    @Test
    void aStudentCannotReadOrDownloadSomeoneElsesResume() throws Exception {
        mockMvc.perform(upload(secondStudentToken, driveId, pdf("theirs.pdf"))).andExpect(status().isCreated());
        Long theirs = resumeRepository.findAll().getFirst().getId();

        mockMvc.perform(get("/api/resumes/" + theirs).header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/resumes/" + theirs + "/file").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadingMyResumeReturnsTheSamePdfBytes() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());
        Long id = resumeRepository.findAll().getFirst().getId();

        mockMvc.perform(get("/api/resumes/" + id + "/file").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(PDF_BYTES));
    }

    // -------------------------------------------------------- Background review

    @Test
    void theJobFillsInTheReviewAndMarksItCompleted() throws Exception {
        when(resumeReviewer.isConfigured()).thenReturn(true);
        when(resumeReviewer.review(any(), any(), any())).thenReturn(new ResumeReviewer.Review(
                82,
                "A strong fit for a Java backend role.",
                List.of("Two Spring Boot projects"),
                List.of("Add measurable results to the internship")));

        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());

        assertThat(resumeReviewJob.reviewNext()).isTrue();

        mockMvc.perform(get("/api/resumes/me").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].score").value(82))
                .andExpect(jsonPath("$[0].summary").value("A strong fit for a Java backend role."))
                .andExpect(jsonPath("$[0].strengths[0]").value("Two Spring Boot projects"))
                .andExpect(jsonPath("$[0].improvements[0]").value("Add measurable results to the internship"))
                .andExpect(jsonPath("$[0].reviewedAt").isNotEmpty());
    }

    @Test
    void withNoApiKeyTheReviewFailsWithAMessageAndTheModelIsNeverCalled() throws Exception {
        when(resumeReviewer.isConfigured()).thenReturn(false);

        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());
        resumeReviewJob.reviewNext();

        Resume reviewed = resumeRepository.findAll().getFirst();
        assertThat(reviewed.getStatus()).isEqualTo(ResumeStatus.FAILED);
        assertThat(reviewed.getFailureReason()).contains("not switched on");
        verify(resumeReviewer, never()).review(any(), any(), any());
    }

    /** A failed model call must never leave the row PROCESSING - that spinner would never stop. */
    @Test
    void aModelErrorMarksTheReviewFailedAndRetryQueuesItAgain() throws Exception {
        when(resumeReviewer.isConfigured()).thenReturn(true);
        when(resumeReviewer.review(any(), any(), any())).thenThrow(new IllegalStateException("model unavailable"));

        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());
        resumeReviewJob.reviewNext();

        Long id = resumeRepository.findAll().getFirst().getId();
        assertThat(resumeRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ResumeStatus.FAILED);

        mockMvc.perform(post("/api/resumes/" + id + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void onlyAFailedReviewCanBeRetried() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());
        Long id = resumeRepository.findAll().getFirst().getId();

        mockMvc.perform(post("/api/resumes/" + id + "/retry").header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isConflict());
    }

    @Test
    void theJobReportsWhenThereIsNothingToReview() {
        assertThat(resumeReviewJob.reviewNext()).isFalse();
    }

    /** A review cut off by a restart would otherwise sit at PROCESSING forever. */
    @Test
    void aReviewInterruptedByARestartIsPutBackInTheQueue() throws Exception {
        mockMvc.perform(upload(studentToken, driveId, pdf("resume.pdf"))).andExpect(status().isCreated());
        Resume resume = resumeRepository.findAll().getFirst();
        resume.setStatus(ResumeStatus.PROCESSING);
        resumeRepository.save(resume);

        resumeReviewJob.requeueInterruptedReviews();

        assertThat(resumeRepository.findById(resume.getId()).orElseThrow().getStatus())
                .isEqualTo(ResumeStatus.PENDING);
    }

    // ----------------------------------------------------------------- Helpers

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("file", filename, "application/pdf", PDF_BYTES);
    }

    private static org.springframework.test.web.servlet.RequestBuilder upload(String token, Long driveId,
                                                                             MockMultipartFile file) {
        return multipart("/api/resumes")
                .file(file)
                .param("driveId", driveId.toString())
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
    }

    private void createUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(role);
        user.setBacklogs(0);
        userRepository.save(user);
    }

    private Drive seedDrive(String company) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole("SDE");
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal("7.5"));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(List.of("CSE"));
        drive.setApplicationDeadline(FUTURE);
        drive.setDescription("Java, Spring Boot and SQL.");
        return driveRepository.save(drive);
    }
}
