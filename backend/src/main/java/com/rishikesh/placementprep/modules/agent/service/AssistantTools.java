package com.rishikesh.placementprep.modules.agent.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.rishikesh.placementprep.modules.application.dto.ApplicationDTO;
import com.rishikesh.placementprep.modules.application.service.ApplicationRefusedException;
import com.rishikesh.placementprep.modules.application.service.ApplicationService;
import com.rishikesh.placementprep.modules.auth.dto.UserProfileDTO;
import com.rishikesh.placementprep.modules.drive.dto.DriveDTO;
import com.rishikesh.placementprep.modules.drive.service.DriveService;
import com.rishikesh.placementprep.modules.knowledgebase.dto.JobDescriptionMatch;
import com.rishikesh.placementprep.modules.knowledgebase.service.JobDescriptionService;

/**
 * The backend methods the model is allowed to call, for one student.
 *
 * <p>Each {@code @Tool} method is described to the model by its description and its
 * parameters. When a plain-English question needs one - "which drives can I apply to?" -
 * the model asks for that tool by name, Spring AI calls the Java method, and the result
 * goes back to the model as JSON for it to answer with.
 *
 * <p>A new instance is made for every request, holding the signed-in student. None of the
 * tools take a student id as a parameter, so there is nothing the model - or a cleverly
 * worded message - could change to reach another student's data.
 *
 * <p>Not a Spring bean for that reason: it is built per request in AgentService, from the
 * same services the controllers use, so every rule those services enforce still applies.
 */
public class AssistantTools {

    private final UserProfileDTO student;
    private final DriveService driveService;
    private final ApplicationService applicationService;
    private final JobDescriptionService jobDescriptionService;

    public AssistantTools(UserProfileDTO student,
                          DriveService driveService,
                          ApplicationService applicationService,
                          JobDescriptionService jobDescriptionService) {
        this.student = student;
        this.driveService = driveService;
        this.applicationService = applicationService;
        this.jobDescriptionService = jobDescriptionService;
    }

    @Tool(description = "List the open placement drives this student is eligible for, judged against their "
            + "CGPA, branch, 10th and 12th percentages and backlogs. Soonest deadline first.")
    public List<DriveSummary> eligibleDrives() {
        // Thrown rather than returned empty: Spring AI hands an exception's message back to
        // the model, which can then tell the student what to do instead of "no drives".
        if (!student.isComplete()) {
            throw new IllegalStateException("The student's academic profile is incomplete, so eligibility cannot "
                    + "be checked. They need to add their CGPA, branch and 10th and 12th percentages on the Profile page.");
        }
        return driveService.findEligibleDrives(
                        student.cgpa(), student.tenthPercentage(), student.twelfthPercentage(),
                        student.backlogs(), student.branch())
                .stream().map(DriveSummary::of).toList();
    }

    @Tool(description = "List every drive on the placement board, including ones this student is not eligible "
            + "for and ones whose deadline has passed. Newest first.")
    public List<DriveSummary> allDrives() {
        return driveService.findAllDrives().stream().map(DriveSummary::of).toList();
    }

    @Tool(description = "List this student's own applications, with the company, role and current status of each "
            + "(APPLIED, SHORTLISTED, REJECTED, SELECTED or WITHDRAWN) and any note from the placement cell.")
    public List<ApplicationSummary> myApplications() {
        Map<Long, DriveDTO> drives = driveService.findAllDrives().stream()
                .collect(Collectors.toMap(DriveDTO::id, Function.identity()));

        return applicationService.findMine(student.id()).stream()
                .map(application -> ApplicationSummary.of(application, drives.get(application.driveId())))
                .toList();
    }

    @Tool(description = "Apply this student to one placement drive. Only call this when the student has clearly "
            + "asked to apply to that specific drive - never on your own initiative.")
    public String applyToDrive(@ToolParam(description = "The id of the drive to apply to") Long driveId) {
        // Apply through the same service the Apply button uses, so every rule - the drive
        // exists, not already applied, still open, profile complete, eligible - is the same
        // one. A refusal is returned as text rather than thrown, so the model can tell the
        // student exactly why in its own words.
        try {
            ApplicationDTO application = applicationService.apply(student, driveId);
            String drive = driveService.findDriveById(driveId)
                    .map(found -> found.companyName() + " - " + found.role())
                    .orElse("drive " + driveId);
            return "Applied to " + drive + ". The application is now " + application.status() + ".";
        } catch (ApplicationRefusedException refused) {
            return "Could not apply: " + refused.getMessage() + ".";
        }
    }

    @Tool(description = "Search the uploaded job-description PDFs by meaning, not exact words - for example "
            + "'backend role using Java and SQL' or 'what is the selection process'. Returns the most relevant "
            + "passages, each with the drive it belongs to.")
    public List<JobDescriptionMatch> searchJobDescriptions(
            @ToolParam(description = "What to look for, in plain words") String query) {
        return jobDescriptionService.search(query, JobDescriptionService.DEFAULT_RESULTS);
    }

    /** The parts of a drive worth showing the model - fewer fields means a cheaper call. */
    public record DriveSummary(Long id, String companyName, String role, BigDecimal ctc, Integer tier,
                               BigDecimal cgpaCutoff, List<String> eligibleBranches, String applicationDeadline) {

        static DriveSummary of(DriveDTO drive) {
            return new DriveSummary(drive.id(), drive.companyName(), drive.role(), drive.ctc(), drive.tier(),
                    drive.cgpaCutoff(), drive.eligibleBranches(),
                    drive.applicationDeadline() == null ? null : drive.applicationDeadline().toString());
        }
    }

    public record ApplicationSummary(Long applicationId, Long driveId, String companyName, String role,
                                     String status, String note) {

        static ApplicationSummary of(ApplicationDTO application, DriveDTO drive) {
            return new ApplicationSummary(application.id(), application.driveId(),
                    drive == null ? "Unknown drive" : drive.companyName(),
                    drive == null ? null : drive.role(),
                    application.status().name(), application.note());
        }
    }
}
