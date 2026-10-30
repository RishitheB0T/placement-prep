package com.rishikesh.placementprep.modules.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

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

import com.rishikesh.placementprep.TestcontainersConfiguration;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.resume.service.ResumeReviewer;

/**
 * Two questions about the resume review's score, asked of the real model.
 *
 * <ul>
 *   <li><b>Is it consistent?</b> A model's answers vary from call to call. The same resume
 *       reviewed {@value #RUNS} times must stay within {@value #MAX_SPREAD} points, or a
 *       student who uploads twice sees two different verdicts on the same page.</li>
 *   <li><b>Does it mean anything?</b> A resume that fits a backend job must average at
 *       least {@value #MIN_GAP} points above a designer's resume reviewed for the same job.
 *       A score that cannot tell those apart is decoration.</li>
 * </ul>
 *
 * <p>Tagged "eval" like AssistantEvalTest: left out of the normal build, run on purpose
 * with {@code ./mvnw test -Peval}, skipped without ANTHROPIC_API_KEY. Makes six model
 * calls; the report goes to target/eval/. Both resumes are invented.
 */
@Tag("eval")
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ResumeReviewEvalTest {

    private static final int RUNS = 3;
    private static final int MAX_SPREAD = 15;
    private static final int MIN_GAP = 20;

    private static final DriveDTO BACKEND_DRIVE = new DriveDTO(1L, "Razorpay", "Backend Engineer",
            new BigDecimal("1800000"), 1, new BigDecimal("7.5"), null, null, false, null, List.of("CSE"),
            Instant.now().plus(20, ChronoUnit.DAYS),
            "Build and run payment APIs in Java and Spring Boot on PostgreSQL.");

    private static final List<String> EXCERPTS = List.of(
            "Build payment APIs in Java and Spring Boot, deployed with Docker on Kubernetes.",
            "Strong PostgreSQL skills: schema design, indexing and query tuning.",
            "Selection process: an online coding test, two technical interviews and a system design round.");

    private static final String[] FITTING_RESUME = {
        "Asha Verma - B.Tech Computer Science, graduating 2027, CGPA 8.7",
        "Skills: Java, Spring Boot, PostgreSQL, Docker, REST APIs, JUnit, Git",
        "Project: payment reconciliation service in Spring Boot and PostgreSQL,",
        "  load-tested at 2,000 requests a second; indexes cut report queries from 4 s to 80 ms.",
        "Internship: backend intern at a fintech start-up, building REST APIs in Java.",
        "Achievements: 400+ problems solved on competitive programming sites."
    };

    private static final String[] UNRELATED_RESUME = {
        "Rohan Das - B.Des Communication Design, graduating 2027",
        "Skills: Figma, Adobe Illustrator, typography, brand identity, illustration",
        "Project: rebranded a campus cafe - logo, menu, signage and social media posts.",
        "Internship: visual design intern at an advertising agency.",
        "Achievements: first place in the inter-college poster design contest."
    };

    @Autowired
    private ResumeReviewer reviewer;

    @Value("${spring.ai.anthropic.chat.model}")
    private String model;

    @Test
    void reviewScoresAreConsistentAndTellAFittingResumeFromAnUnrelatedOne() throws IOException {
        // 1. Each resume reviewed several times, against the same drive and excerpts
        List<Integer> fitting = scores(pdfWithLines(FITTING_RESUME));
        List<Integer> unrelated = scores(pdfWithLines(UNRELATED_RESUME));

        // 2. The report to read, then the verdicts
        Path written = write(report(fitting, unrelated));
        assertThat(spread(fitting)).as("fitting resume scores %s - see %s", fitting, written.toAbsolutePath())
                .isLessThanOrEqualTo(MAX_SPREAD);
        assertThat(spread(unrelated)).as("unrelated resume scores %s - see %s", unrelated, written.toAbsolutePath())
                .isLessThanOrEqualTo(MAX_SPREAD);
        assertThat(average(fitting) - average(unrelated))
                .as("gap between %s and %s - see %s", fitting, unrelated, written.toAbsolutePath())
                .isGreaterThanOrEqualTo(MIN_GAP);
    }

    private List<Integer> scores(byte[] resume) {
        List<Integer> scores = new ArrayList<>();
        for (int run = 0; run < RUNS; run++) {
            scores.add(reviewer.review(resume, BACKEND_DRIVE, EXCERPTS).score());
        }
        return scores;
    }

    private static int spread(List<Integer> scores) {
        return Collections.max(scores) - Collections.min(scores);
    }

    private static double average(List<Integer> scores) {
        return scores.stream().mapToInt(Integer::intValue).average().orElse(0);
    }

    private String report(List<Integer> fitting, List<Integer> unrelated) {
        return """
                # Resume review consistency - %s, %s

                Each resume reviewed %d times for %s - %s.

                | Resume | Scores | Average | Spread (at most %d) |
                | --- | --- | --- | --- |
                | Fits the job (backend developer) | %s | %.1f | %d |
                | Unrelated (graphic designer) | %s | %.1f | %d |

                Gap between the averages: %.1f points (at least %d needed).
                """.formatted(model, LocalDate.now(), RUNS, BACKEND_DRIVE.companyName(), BACKEND_DRIVE.role(),
                MAX_SPREAD,
                joined(fitting), average(fitting), spread(fitting),
                joined(unrelated), average(unrelated), spread(unrelated),
                average(fitting) - average(unrelated), MIN_GAP);
    }

    private static String joined(List<Integer> scores) {
        return scores.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static Path write(String markdown) throws IOException {
        Path file = Path.of("target", "eval", "resume-review-consistency.md");
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
