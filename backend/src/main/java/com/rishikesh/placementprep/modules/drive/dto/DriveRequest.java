package com.rishikesh.placementprep.modules.drive.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The request body for both POST (create) and PUT (replace) on /api/drives. Named for
 * what it is rather than for one of its two uses.
 *
 * <p>applicationDeadline is deliberately not annotated @Future. An admin must be able to
 * correct a typo on a drive whose deadline has already passed, and @Future would reject
 * the unchanged date sent back. Nothing is lost by allowing it: the eligibility query
 * filters on application_deadline > now(), so an expired drive never reaches a student.
 *
 * <p>tenthCutoff and twelfthCutoff are optional. Leaving one out means the drive sets no
 * requirement for that qualification, matching how a NULL cgpaCutoff behaves.
 * backlogsAllowed is required, because "unspecified" has no useful meaning for it.
 */
public record DriveRequest(
    @NotBlank String companyName,
    @NotBlank String role,
    @NotNull @Positive BigDecimal ctc,
    @NotNull Integer tier,
    @NotNull BigDecimal cgpaCutoff,
    @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal tenthCutoff,
    @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal twelfthCutoff,
    @NotNull Boolean backlogsAllowed,
    @PositiveOrZero Integer maxBacklogs,
    @NotEmpty List<String> eligibleBranches,
    @NotNull Instant applicationDeadline,
    String description
) {

    /**
     * Cross-field rule: a drive that permits backlogs must say how many it tolerates.
     *
     * <p>A single-field annotation cannot express a rule that spans two fields.
     * @AssertTrue on a derived method is the way to do that without writing a custom
     * constraint annotation and validator class. The method is evaluated as part of the
     * same @Valid pass, so a violation produces the usual 400.
     */
    @AssertTrue(message = "maxBacklogs is required when backlogsAllowed is true")
    public boolean isBacklogLimitConsistent() {
        return !Boolean.TRUE.equals(backlogsAllowed) || maxBacklogs != null;
    }
}
