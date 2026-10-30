package com.rishikesh.placementprep.modules.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.agent.eval.ToolChoiceEval.Report;
import com.rishikesh.placementprep.modules.agent.service.AgentService;
import com.rishikesh.placementprep.modules.application.repository.ApplicationRepository;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.model.Role;
import com.rishikesh.placementprep.modules.auth.model.User;
import com.rishikesh.placementprep.modules.auth.repository.UserRepository;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.model.Drive;
import com.rishikesh.placementprep.modules.drive.repository.DriveRepository;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

/**
 * Runs the tool-choice evaluation against the real model.
 *
 * <p>Tagged "eval", which the normal build leaves out: every case is a paid model call,
 * and a model's answers vary from run to run, so this is run on purpose - after changing
 * the system prompt, a tool description or the model - rather than on every push:
 *
 * <pre>
 * ANTHROPIC_API_KEY=... ./mvnw test -Peval
 * </pre>
 *
 * Without a key it is skipped. The report is written to target/eval/.
 *
 * <p>Everything except the model is the real application: PostgreSQL in a container, the
 * services, the tools and the local embedding model, seeded with a small fixed world that
 * the dataset's messages talk about.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AssistantEvalTest {

    private static final String STUDENT_EMAIL = "eval.student@cse.nits.ac.in";

    @Autowired
    private AgentService agentService;

    @Autowired
    private UserService userService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private JobDescriptionService jobDescriptionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DriveRepository driveRepository;

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${spring.ai.anthropic.chat.model}")
    private String model;

    private Long razorpayDriveId;

    @Test
    void theAssistantChoosesTheRightTools() throws IOException {
        // 1. A small, fixed world for every case to run against
        UserProfileDTO student = seedWorld();

        // 2. Every case, each starting from the same single application, with no history
        Report report = ToolChoiceEval.run(ToolChoiceEval.load(), message -> {
            resetApplications(student);
            return agentService.chat(student, message, List.of());
        });

        // 3. The report to read, then the verdict
        Path written = write(report.toMarkdown("Assistant tool choice - " + model + ", " + LocalDate.now()));
        assertThat(report.meetsRequiredPassRate())
                .as("%.0f%% of cases passed - see %s", report.passRate() * 100, written.toAbsolutePath())
                .isTrue();
    }

    /**
     * One student (CSE, CGPA 8.5, no backlogs) and four drives: Zoho and Razorpay open and
     * eligible, Goldman Sachs above the student's CGPA, Infosys already closed. Razorpay
     * has a job description, so searches have something to find.
     */
    private UserProfileDTO seedWorld() throws IOException {
        jdbcTemplate.update("DELETE FROM job_description_chunks");
        applicationRepository.deleteAll();
        driveRepository.deleteAll();
        userRepository.deleteAll();

        User user = new User();
        user.setEmail(STUDENT_EMAIL);
        user.setPasswordHash(passwordEncoder.encode("correct-horse-battery"));
        user.setRole(Role.STUDENT);
        user.setCgpa(new BigDecimal("8.50"));
        user.setBranch("CSE");
        user.setTenthPercentage(new BigDecimal("90"));
        user.setTwelfthPercentage(new BigDecimal("88"));
        user.setBacklogs(0);
        userRepository.save(user);

        Instant open = Instant.now().plus(20, ChronoUnit.DAYS);
        drive("Zoho", "Member Technical Staff", "7.0", open);
        razorpayDriveId = drive("Razorpay", "Backend Engineer", "7.5", open);
        drive("Goldman Sachs", "Analyst", "9.2", open);
        drive("Infosys", "Systems Engineer", "6.0", Instant.now().minus(2, ChronoUnit.DAYS));

        jobDescriptionService.index(razorpayDriveId, "Razorpay", "Backend Engineer", pdfWithLines(
                "Razorpay - Backend Engineer, Payments.",
                "Build payment APIs in Go and Java, deployed on Kubernetes.",
                "Strong PostgreSQL and distributed systems fundamentals.",
                "Selection process: an online coding test, two technical interviews,",
                "a system design round, and a final HR conversation."));

        return userService.findByEmail(STUDENT_EMAIL).orElseThrow();
    }

    /** Back to the one application every case starts with: Razorpay, still APPLIED. */
    private void resetApplications(UserProfileDTO student) {
        applicationRepository.deleteAll();
        applicationService.apply(student, razorpayDriveId);
    }

    private Long drive(String company, String role, String cgpaCutoff, Instant deadline) {
        Drive drive = new Drive();
        drive.setCompanyName(company);
        drive.setRole(role);
        drive.setCtc(new BigDecimal("1200000"));
        drive.setTier(1);
        drive.setCgpaCutoff(new BigDecimal(cgpaCutoff));
        drive.setBacklogsAllowed(false);
        drive.setEligibleBranches(List.of("CSE", "ECE"));
        drive.setApplicationDeadline(deadline);
        return driveRepository.save(drive).getId();
    }

    private static Path write(String markdown) throws IOException {
        Path file = Path.of("target", "eval", "assistant-tool-choice.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, markdown);
        System.out.println(markdown);
        return file;
    }

    /** A one-page PDF with each line on a line of its own, so none runs off the page. */
    private static byte[] pdfWithLines(String... lines) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.setLeading(16);
                content.newLineAtOffset(50, 720);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
