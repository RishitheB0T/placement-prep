package com.rishikesh.placementprep.modules.knowledgebase.dto;

/**
 * One passage of a job-description PDF that matched a search, and the drive it came from.
 *
 * @param score how close in meaning the passage is to the search, from 0 (unrelated) to
 *              1 (the same meaning). Results arrive best first.
 */
public record JobDescriptionMatch(
    Long driveId,
    String companyName,
    String role,
    String excerpt,
    double score
) {}
