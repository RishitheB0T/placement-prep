package com.rishikesh.placementprep.modules.resume.model;

/**
 * Where a resume's review has got to.
 *
 * <p>The student only ever creates PENDING, by uploading, and moves FAILED back to
 * PENDING, by retrying. Every other step belongs to ResumeReviewJob.
 */
public enum ResumeStatus {

    /** Uploaded, waiting for the background job to pick it up. */
    PENDING,

    /** The background job is reviewing it right now. */
    PROCESSING,

    /** Reviewed. Score, summary, strengths and improvements are filled in. */
    COMPLETED,

    /** The review could not be done. failureReason says why, and the student can retry. */
    FAILED
}
