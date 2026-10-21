package com.rishikesh.placementprep.modules.resume.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.resume.service.ResumeService.ReviewTask;

/**
 * Works through uploaded resumes in the background, one at a time.
 *
 * <p>Uploading never waits for the model. The upload request saves the file, writes a
 * PENDING row and answers straight away; this job, running on its own schedule, is what
 * turns PENDING rows into reviews. A model call can take half a minute, and a browser
 * request held open that long is one a student will refresh, cancel, or retry.
 *
 * <p>The resumes table is the queue. There is no message broker in this project, and for
 * one server a table the job polls does the same job: work survives a restart, and the
 * order is simply oldest upload first.
 *
 * <p>Not {@code @Transactional} itself. Each ResumeService call below is its own short
 * transaction, so no database connection is held while the model is thinking.
 */
@Component
public class ResumeReviewJob {

    private static final Logger log = LoggerFactory.getLogger(ResumeReviewJob.class);

    static final String NOT_CONFIGURED =
            "AI feedback is not switched on for this server yet. Your resume is saved - try again once it is.";

    static final String REVIEW_FAILED =
            "The review could not be completed this time. Your resume is saved - try again in a minute.";

    private final ResumeService resumeService;
    private final ResumeReviewer resumeReviewer;
    private final DriveService driveService;

    public ResumeReviewJob(ResumeService resumeService,
                           ResumeReviewer resumeReviewer,
                           DriveService driveService) {
        this.resumeService = resumeService;
        this.resumeReviewer = resumeReviewer;
        this.driveService = driveService;
    }

    /** Puts back any review that was cut off by the last shutdown. */
    @EventListener(ApplicationReadyEvent.class)
    public void requeueInterruptedReviews() {
        int requeued = resumeService.requeueInterrupted();
        if (requeued > 0) {
            log.info("Re-queued {} resume review(s) interrupted by the last shutdown", requeued);
        }
    }

    /**
     * Runs every resume.review.poll-delay (five seconds by default), counted from the end
     * of the previous run - fixedDelay, not fixedRate, so two runs can never overlap even
     * when one review is slow.
     *
     * <p>Drains the queue rather than taking one resume per run, so ten uploads in the same
     * minute do not wait ten polls between them.
     */
    @Scheduled(fixedDelayString = "${resume.review.poll-delay}",
               initialDelayString = "${resume.review.poll-delay}")
    public void reviewWaitingResumes() {
        while (reviewNext()) {
            // reviewNext does the work; the loop only decides when to stop.
        }
    }

    /**
     * Reviews the oldest waiting resume, if there is one.
     *
     * <p>Every failure after the claim ends with the row marked FAILED rather than left
     * PROCESSING, so a student is never stuck watching a spinner that will not stop. The
     * real exception goes to the log; the student sees a message they can act on.
     *
     * @return whether there was a resume to review
     */
    public boolean reviewNext() {
        // 1. Take the oldest waiting resume off the queue
        Optional<ReviewTask> claimed = resumeService.claimNextPending();
        if (claimed.isEmpty()) {
            return false;
        }
        ReviewTask task = claimed.get();

        // 2. Without an API key there is nothing to call
        if (!resumeReviewer.isConfigured()) {
            resumeService.failReview(task.resumeId(), NOT_CONFIGURED);
            return true;
        }

        try {
            // 3. The drive's job description is what the resume is scored against. If the
            //    drive was deleted after the claim, the cascade already removed this
            //    resume's row too, so there is nothing left to finish.
            Optional<DriveDTO> drive = driveService.findDriveById(task.driveId());
            if (drive.isEmpty()) {
                return true;
            }

            // 4. Read the PDF back and ask the model
            byte[] pdf = resumeService.readFile(task.storageKey());
            ResumeReviewer.Review review = resumeReviewer.review(pdf, drive.get());

            // 5. Save the review
            resumeService.completeReview(task.resumeId(), review);
        } catch (RuntimeException e) {
            log.warn("Review of resume {} failed", task.resumeId(), e);
            resumeService.failReview(task.resumeId(), REVIEW_FAILED);
        }
        return true;
    }
}
