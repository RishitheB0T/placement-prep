package com.rishikesh.placementprep.modules.drive.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DriveDTO(
    Long id,
    String companyName,
    String role,
    BigDecimal ctc,
    Integer tier,
    BigDecimal cgpaCutoff,
    BigDecimal tenthCutoff,
    BigDecimal twelfthCutoff,
    Boolean backlogsAllowed,
    Integer maxBacklogs,
    List<String> eligibleBranches,
    Instant applicationDeadline,
    String description
) {
}
