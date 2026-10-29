package com.rishikesh.placementprep.modules.drive.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

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
 *
 * <p>Every size and range below matches the column it fills (V2-V4), so a value the
 * database cannot hold is a clean 400 naming the field rather than a 500 from PostgreSQL:
 * company and role are VARCHAR(255), ctc is NUMERIC(10, 2), cgpa_cutoff NUMERIC(4, 2) and
 * each branch VARCHAR(255) - capped at 32 here, the length of a student's branch, since a
 * longer one could never match anybody. Tier is 1 to 3, the three choices the form offers.
 */
public record DriveRequest(
    @NotBlank @Size(max = 255) String companyName,
    @NotBlank @Size(max = 255) String role,
    @NotNull @Positive @Digits(integer = 8, fraction = 2) BigDecimal ctc,
    @NotNull @Min(1) @Max(3) Integer tier,
    @NotNull @DecimalMin("0.0") @DecimalMax("10.0") BigDecimal cgpaCutoff,
    @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal tenthCutoff,
    @DecimalMin("0.0") @DecimalMax("100.0") BigDecimal twelfthCutoff,
    @NotNull Boolean backlogsAllowed,
    @PositiveOrZero Integer maxBacklogs,
    @NotEmpty List<@NotBlank @Size(max = 32) String> eligibleBranches,
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
    @AssertTrue(message = "Enter the maximum number of backlogs, since backlogs are allowed")
    public boolean isBacklogLimitConsistent() {
        return !Boolean.TRUE.equals(backlogsAllowed) || maxBacklogs != null;
    }
}
