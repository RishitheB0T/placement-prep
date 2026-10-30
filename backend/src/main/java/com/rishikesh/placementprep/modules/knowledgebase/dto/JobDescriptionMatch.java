package com.rishikesh.placementprep.modules.knowledgebase.dto;

/**
 * One passage of a job-description PDF that matched a search, and the drive it came from.
 * Results arrive best first, ranked by both halves of the search together - not by score.
 *
 * @param score     how close in meaning the passage is to the search, from 0 (unrelated) to
 *                  1 (the same meaning). 0 for a passage found by its words alone, which
 *                  the search by meaning did not put forward.
 * @param matchedBy which half of the search found the passage
 */
public record JobDescriptionMatch(
    Long driveId,
    String companyName,
    String role,
    String excerpt,
    double score,
    MatchedBy matchedBy
) {

    /** How a passage was found. */
    public enum MatchedBy {
        /** Close in meaning to the search, by the vector search. */
        MEANING,
        /** Contains words of the search, by PostgreSQL's full-text search. */
        KEYWORDS,
        /** Found by both. */
        BOTH
    }

    /** The same match, labelled with how it was found. */
    public JobDescriptionMatch withMatchedBy(MatchedBy how) {
        return new JobDescriptionMatch(driveId, companyName, role, excerpt, score, how);
    }
}
