package com.rishikesh.placementprep.modules.resume.service;

import java.util.List;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;

/**
 * Asks the model to review one resume against one drive.
 *
 * <p>The only class in this module that knows a model is involved. Kept apart from
 * ResumeReviewJob so the tests can replace just this one call with a fake answer, and
 * exercise the whole queue - claiming, saving, failing, retrying - without a network.
 *
 * <p>The PDF is sent to the model as it is, as a document attachment. Claude reads PDFs
 * natively, text and layout both, so there is no step here that extracts the text first
 * and no PDF library in the build to do it with.
 */
@Service
public class ResumeReviewer {

    private static final MimeType PDF = new MimeType("application", "pdf");

    private static final String SYSTEM_PROMPT = """
            You are a placement mentor at an Indian engineering college, reviewing a
            final-year student's resume against one specific job opening. Be honest,
            specific and encouraging. Judge only what the resume actually shows - never
            assume a skill, project or experience it does not mention. If the attached
            document is not a resume at all, give it a score of 0 and say so in the
            summary.
            """;

    /**
     * The words in braces are filled in from .param(...) below. Passing the drive's
     * details as parameters rather than pasting them into this text matters: a job
     * description containing a brace of its own is then treated as plain text, not as a
     * placeholder the template engine tries to fill.
     */
    private static final String USER_PROMPT = """
            Review the attached resume for this opening.

            Company: {company}
            Role: {role}
            Job description:
            {description}

            Give a score from 0 to 100 for how well this resume fits this role. Write a
            two or three sentence summary. List three to five strengths the resume
            already shows for this role, and three to five improvements - each one saying
            exactly what to add, cut or rewrite.
            """;

    private final ChatClient chatClient;
    private final String apiKey;

    public ResumeReviewer(ChatClient chatClient,
                          @Value("${spring.ai.anthropic.api-key:}") String apiKey) {
        this.chatClient = chatClient;
        this.apiKey = apiKey;
    }

    /**
     * Whether there is an API key to call the model with at all. Without one every call
     * would fail anyway, so ResumeReviewJob checks this first and tells the student
     * plainly, rather than making a request it knows will be refused.
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Review review(byte[] pdf, DriveDTO drive) {
        // 1. Attach the PDF as a document the model can read
        Media resume = Media.builder().mimeType(PDF).data(pdf).build();

        // 2. Ask for the review. entity(Review.class) adds instructions asking for JSON in
        //    the shape of the Review record, then parses the reply into one.
        Review review = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(user -> user
                        .text(USER_PROMPT)
                        .param("company", drive.companyName())
                        .param("role", drive.role())
                        .param("description", descriptionOf(drive))
                        .media(resume))
                .call()
                .entity(Review.class);

        // 3. The reply is model output, not something to trust as-is
        return checked(review);
    }

    private String descriptionOf(DriveDTO drive) {
        if (drive.description() == null || drive.description().isBlank()) {
            return "No description was posted for this drive. Judge the resume against what "
                    + "the role usually expects.";
        }
        return drive.description();
    }

    /**
     * Holds the reply to what the resumes table will accept. The model is asked for a
     * score from 0 to 100, but asking is not the same as the CHECK constraint being
     * satisfied, so the score is clamped into range here rather than left to fail the
     * insert. Missing lists become empty ones.
     */
    private Review checked(Review review) {
        if (review == null || review.summary() == null || review.summary().isBlank()) {
            throw new IllegalStateException("The model's reply did not contain a review");
        }
        return new Review(
                Math.max(0, Math.min(100, review.score())),
                review.summary().trim(),
                review.strengths() == null ? List.of() : review.strengths(),
                review.improvements() == null ? List.of() : review.improvements());
    }

    /**
     * The shape the model is asked to answer in. Record component names become the JSON
     * field names, so these four names are part of the prompt as much as they are Java.
     */
    public record Review(int score, String summary, List<String> strengths, List<String> improvements) {}
}
