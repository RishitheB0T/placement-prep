package com.rishikesh.placementprep.modules.resume.controller;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.auth.service.UserService;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.resume.dto.ResumeDTO;
import com.rishikesh.placementprep.modules.resume.service.ResumeService;
import com.rishikesh.placementprep.modules.resume.service.ResumeService.StoredFile;

/**
 * Uploading a resume for a drive, and reading back what the review said.
 *
 * <p>Restricted to STUDENT and TNP_COORDINATOR by SecurityConfig. Every path resolves the
 * caller's own id from their token, as ApplicationController does, so there is no id in
 * a request a student could change to reach somebody else's resume.
 */
@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    /** Every PDF file starts with these five bytes. */
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final ResumeService resumeService;
    private final DriveService driveService;
    private final UserService userService;
    private final DataSize maxFileSize;

    public ResumeController(ResumeService resumeService,
                            DriveService driveService,
                            UserService userService,
                            @Value("${spring.servlet.multipart.max-file-size}") DataSize maxFileSize) {
        this.resumeService = resumeService;
        this.driveService = driveService;
        this.userService = userService;
        this.maxFileSize = maxFileSize;
    }

    /**
     * Uploads a resume for one drive and queues it for review.
     *
     * <p>A multipart/form-data request, not JSON: a file cannot travel inside a JSON body
     * without being encoded into a much larger string first. The form has two parts, the
     * drive's id and the file.
     *
     * <p>Answers 201 straight away with status PENDING. The review happens afterwards, in
     * ResumeReviewJob, and the frontend polls GET /api/resumes/me until it is done.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResumeDTO> upload(@AuthenticationPrincipal UserDetails principal,
                                            @RequestParam Long driveId,
                                            @RequestParam MultipartFile file) throws IOException {
        // 1. Check the file before anything is stored
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a PDF file to upload");
        }
        // spring.servlet.multipart.max-file-size already refuses an oversized upload
        // before it reaches here. Checking against the same setting again means this
        // method holds the rule itself, wherever its bytes came from.
        if (file.getSize() > maxFileSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Resumes must be " + maxFileSize.toMegabytes() + " MB or smaller");
        }
        byte[] content = file.getBytes();
        // The content type and the file extension both come from the student's browser,
        // so either can say "pdf" about a file that is nothing of the sort. The first
        // bytes of the file itself cannot be talked around that way.
        if (!startsWithPdfSignature(content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF resumes are accepted");
        }

        // 2. The drive has to exist - its job description is what the review is against
        if (driveService.findDriveById(driveId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No drive with id " + driveId);
        }

        // 3. Store it and queue the review
        ResumeDTO created = resumeService.upload(callerId(principal), driveId, file.getOriginalFilename(), content)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Your last resume for this drive is still being reviewed. "
                                + "Wait for that review to finish before uploading another."));

        return ResponseEntity.created(URI.create("/api/resumes/" + created.id())).body(created);
    }

    /** Every resume I uploaded, newest first. */
    @GetMapping("/me")
    public List<ResumeDTO> mine(@AuthenticationPrincipal UserDetails principal) {
        return resumeService.findMine(callerId(principal));
    }

    /**
     * One of my resumes. A 404 rather than a 403 for somebody else's, for the same reason
     * withdraw does it: a 403 would confirm that a resume with that id exists at all.
     */
    @GetMapping("/{id}")
    public ResumeDTO one(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        return resumeService.findMyResume(id, callerId(principal))
                .orElseThrow(() -> notMine(id));
    }

    /**
     * The PDF I uploaded, so I can open it again. "inline" asks the browser to show it in
     * a tab rather than save it straight to the downloads folder.
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        StoredFile stored = resumeService.loadFile(id, callerId(principal))
                .orElseThrow(() -> notMine(id));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(stored.filename(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(stored.content());
    }

    /** Sends a failed review back to the queue. */
    @PostMapping("/{id}/retry")
    public ResumeDTO retry(@PathVariable Long id, @AuthenticationPrincipal UserDetails principal) {
        Long studentId = callerId(principal);
        return resumeService.retry(id, studentId).orElseThrow(() -> explainRetryFailure(id, studentId));
    }

    /**
     * Works out why a retry did nothing, so the caller gets an accurate status - the same
     * extra lookup, on the failure path only, that UserController does for promotion.
     */
    private ResponseStatusException explainRetryFailure(Long id, Long studentId) {
        return resumeService.findMyResume(id, studentId)
                .map(existing -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Only a failed review can be retried, and this one is "
                                + existing.status().name().toLowerCase()))
                .orElseGet(() -> notMine(id));
    }

    private ResponseStatusException notMine(Long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No resume of yours with id " + id);
    }

    private static boolean startsWithPdfSignature(byte[] content) {
        return content.length >= PDF_SIGNATURE.length
                && Arrays.equals(content, 0, PDF_SIGNATURE.length, PDF_SIGNATURE, 0, PDF_SIGNATURE.length);
    }

    /** Resolves the signed-in account's numeric id from the email a verified token carries. */
    private Long callerId(UserDetails principal) {
        UserProfileDTO profile = userService.findByEmail(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Account no longer exists"));
        return profile.id();
    }
}
