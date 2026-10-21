package com.rishikesh.placementprep.modules.knowledgebase.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.knowledgebase.dto.IndexedJobDescription;
import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Uploading a drive's job-description PDF, and searching every uploaded one by meaning.
 *
 * <p>Uploading sits under /api/drives, so SecurityConfig's existing "changing the board
 * is the cell's job" rule already restricts it to recruiters and admins. Searching is
 * open to any signed-in account.
 */
@RestController
public class JobDescriptionController {

    /** Every PDF file starts with these five bytes. */
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final JobDescriptionService jobDescriptionService;
    private final DriveService driveService;

    public JobDescriptionController(JobDescriptionService jobDescriptionService, DriveService driveService) {
        this.jobDescriptionService = jobDescriptionService;
        this.driveService = driveService;
    }

    /** Makes a drive's job-description PDF searchable, replacing any earlier one. */
    @PostMapping(path = "/api/drives/{driveId}/job-description", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IndexedJobDescription> upload(@PathVariable Long driveId,
                                                        @RequestParam MultipartFile file) throws IOException {
        // 1. Same checks as a resume upload: something there, and really a PDF
        byte[] content = file.getBytes();
        if (content.length < PDF_SIGNATURE.length
                || !Arrays.equals(content, 0, PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF job descriptions are accepted");
        }

        // 2. The drive has to exist - its company and role label every passage
        DriveDTO drive = driveService.findDriveById(driveId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No drive with id " + driveId));

        // 3. Read, split, embed and store
        int passages = jobDescriptionService.index(drive.id(), drive.companyName(), drive.role(), content);
        if (passages == 0) {
            // 422: a real PDF, correctly sent, that simply has no text to search.
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "No text could be read from this PDF. If it is a scanned image, upload one with selectable text.");
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(new IndexedJobDescription(driveId, passages));
    }

    /**
     * Job-description passages closest in meaning to {@code q}, best first. The
     * constraints on the parameters are checked by Spring's built-in method validation,
     * which answers 400 when one fails.
     */
    @GetMapping("/api/job-descriptions/search")
    public List<JobDescriptionMatch> search(@RequestParam @NotBlank @Size(max = 500) String q,
                                            @RequestParam(defaultValue = "" + JobDescriptionService.DEFAULT_RESULTS)
                                            @Min(1) @Max(20) int limit) {
        return jobDescriptionService.search(q.trim(), limit);
    }
}
