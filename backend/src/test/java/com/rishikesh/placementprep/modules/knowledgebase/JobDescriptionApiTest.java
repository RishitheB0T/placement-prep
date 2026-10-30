package com.rishikesh.placementprep.modules.knowledgebase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.JwtService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.rishikesh.placementprep.modules.knowledgebase.repository.JobDescriptionChunkRepository;
import com.rishikesh.placementprep.modules.knowledgebase.repository.JobDescriptionChunkRepository.StoredPassage;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

/**
 * The job-description search, end to end: a real PDF, the real local embedding model,
 * and pgvector in a real PostgreSQL container. Nothing here is mocked, which is what makes
 * the first test meaningful - it can only pass if the search really works by meaning.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class JobDescriptionApiTest {

    private static final Instant FUTURE = Instant.now().plus(30, ChronoUnit.DAYS);

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobDescriptionService jobDescriptionService;

    @Autowired
    private JobDescriptionChunkRepository chunkRepository;

    private String adminToken;
    private String studentToken;
    private Long backendDriveId;
    private Long designDriveId;

    @BeforeEach
    void startFromEmptyTables() {
        jdbcTemplate.update("DELETE FROM job_description_chunks");
        driveRepository.deleteAll();
        userRepository.deleteAll();

        createUser("pic@tnp.nits.ac.in", Role.TNP_PIC);
        createUser("student@cse.nits.ac.in", Role.STUDENT);
        adminToken = jwtService.generateToken("pic@tnp.nits.ac.in", Role.TNP_PIC);
        studentToken = jwtService.generateToken("student@cse.nits.ac.in", Role.STUDENT);

        backendDriveId = seedDrive("Razorpay", "Backend Engineer");
        designDriveId = seedDrive("Canva", "Product Designer");
    }

    /** No word of the question appears in the backend job description - only its meaning. */
    @Test
    void searchFindsTheJobDescriptionByMeaningNotByMatchingWords() throws Exception {
        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());
        upload(adminToken, designDriveId, DESIGN_JD).andExpect(status().isCreated());

        mockMvc.perform(get("/api/job-descriptions/search")
                        .param("q", "server-side programming with databases")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].companyName").value("Razorpay"))
                .andExpect(jsonPath("$[0].driveId").value(backendDriveId));

        mockMvc.perform(get("/api/job-descriptions/search")
                        .param("q", "someone who makes beautiful user interfaces")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].companyName").value("Canva"));
    }

    /**
     * The resume reviewer's retrieval: only the reviewed drive's job description comes
     * back, however close in meaning another drive's passages are - a Razorpay review must
     * never be judged against Canva's requirements.
     */
    @Test
    void theReviewersRetrievalStaysInsideOneDrivesJobDescription() throws Exception {
        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());
        upload(adminToken, designDriveId, DESIGN_JD).andExpect(status().isCreated());

        List<String> excerpts = jobDescriptionService.passagesFor(backendDriveId, "required skills and design work", 8);

        assertThat(excerpts).isNotEmpty()
                .allSatisfy(text -> assertThat(text).doesNotContain("Figma"))
                .anySatisfy(text -> assertThat(text).contains("Spring Boot"));
        assertThat(jobDescriptionService.passagesFor(999_999L, "required skills", 8)).isEmpty();
    }

    /** The keyword half of search: PostgreSQL's full-text search over the same passages. */
    @Test
    void keywordSearchFindsPassagesByTheirWordsAndOneWordIsEnough() throws Exception {
        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());
        upload(adminToken, designDriveId, DESIGN_JD).andExpect(status().isCreated());

        // "indexes" finds "indexing" - both reduce to the stem "index" - and "Kotlin",
        // which appears nowhere, does not stop the other word from matching.
        assertThat(chunkRepository.searchByKeywords("Kotlin indexes", 10))
                .extracting(StoredPassage::companyName).containsExactly("Razorpay");
        assertThat(chunkRepository.searchByKeywords("wireframe", 10))
                .extracting(StoredPassage::driveId).containsExactly(designDriveId);
        // Nothing but common words leaves no stems to look for: no matches, and no error.
        assertThat(chunkRepository.searchByKeywords("the and of", 10)).isEmpty();
    }

    @Test
    void reUploadingReplacesTheDrivesPassagesInsteadOfAddingMore() throws Exception {
        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());
        long afterFirst = passageCount();

        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());

        assertThat(passageCount()).isEqualTo(afterFirst).isPositive();
    }

    @Test
    void deletingADriveRemovesItsPassagesFromSearch() throws Exception {
        upload(adminToken, backendDriveId, BACKEND_JD).andExpect(status().isCreated());

        mockMvc.perform(delete("/api/drives/" + backendDriveId).header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isNoContent());

        assertThat(passageCount()).isZero();
    }

    @Test
    void aStudentCannotUploadAJobDescription() throws Exception {
        upload(studentToken, backendDriveId, BACKEND_JD).andExpect(status().isForbidden());
        assertThat(passageCount()).isZero();
    }

    @Test
    void aFileThatIsNotAPdfIs400() throws Exception {
        MockMultipartFile notes = new MockMultipartFile("file", "jd.pdf", "application/pdf",
                "plain text pretending".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/drives/" + backendDriveId + "/job-description").file(notes)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isBadRequest());
    }

    /** A scanned job description is a real PDF with nothing in it to search. */
    @Test
    void aPdfWithNoTextIs422() throws Exception {
        upload(adminToken, backendDriveId, "").andExpect(status().isUnprocessableContent());
    }

    @Test
    void anEmptySearchIs400() throws Exception {
        mockMvc.perform(get("/api/job-descriptions/search").param("q", " ")
                        .header(HttpHeaders.AUTHORIZATION, bearer(studentToken)))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------- Helpers

    private static final String BACKEND_JD = "Build REST APIs in Java with Spring Boot. Strong SQL skills: "
            + "PostgreSQL schema design, indexing and query tuning. JUnit, Docker and CI are a plus.";

    private static final String DESIGN_JD = "Create wireframes, prototypes and polished visual designs in Figma. "
            + "Run usability tests with customers. A portfolio showing typography and colour is required.";

    private org.springframework.test.web.servlet.ResultActions upload(String token, Long driveId, String text) throws Exception {
        return mockMvc.perform(multipart("/api/drives/" + driveId + "/job-description")
                .file(new MockMultipartFile("file", "jd.pdf", "application/pdf", pdfWithText(text)))
                .header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private long passageCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM job_description_chunks", Long.class);
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

    private Long seedDrive(String company, String role) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole(role);
        drive.setCtc(new BigDecimal("900000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal("6.0"));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(List.of("CSE"));
        drive.setApplicationDeadline(FUTURE);
        return driveRepository.save(drive).getId();
    }

    private static byte[] pdfWithText(String text) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            if (!text.isEmpty()) {
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    content.newLineAtOffset(40, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
