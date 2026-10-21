package com.rishikesh.placementprep.modules.knowledgebase.dto;

/**
 * Returned after a job-description PDF is uploaded.
 *
 * @param passages how many searchable passages the PDF was split into
 */
public record IndexedJobDescription(Long driveId, int passages) {}
