package com.rishikesh.placementprep.modules.drive.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;


import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity 
@Table(name = "drives") 
public class Drive {
    @Id 
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String companyName;

    private String role;

    private BigDecimal ctc;

    private Integer tier;

    private BigDecimal cgpaCutoff;

    private BigDecimal tenthCutoff;

    private BigDecimal twelfthCutoff;

    private Boolean backlogsAllowed;

    private Integer maxBacklogs;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private List<String> eligibleBranches;

    private Instant applicationDeadline;

    private String description;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp 
    private Instant updatedAt;

    public Drive() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public BigDecimal getCtc() {
        return ctc;
    }

    public void setCtc(BigDecimal ctc) {
        this.ctc = ctc;
    }

    public Integer getTier() {
        return tier;
    }

    public void setTier(Integer tier) {
        this.tier = tier;
    }

    public BigDecimal getCgpaCutoff() {
        return cgpaCutoff;
    }

    public void setCgpaCutoff(BigDecimal cgpaCutoff) {
        this.cgpaCutoff = cgpaCutoff;
    }

    public BigDecimal getTenthCutoff() {
        return tenthCutoff;
    }

    public void setTenthCutoff(BigDecimal tenthCutoff) {
        this.tenthCutoff = tenthCutoff;
    }

    public BigDecimal getTwelfthCutoff() {
        return twelfthCutoff;
    }

    public void setTwelfthCutoff(BigDecimal twelfthCutoff) {
        this.twelfthCutoff = twelfthCutoff;
    }

    public Boolean getBacklogsAllowed() {
        return backlogsAllowed;
    }

    public void setBacklogsAllowed(Boolean backlogsAllowed) {
        this.backlogsAllowed = backlogsAllowed;
    }

    public Integer getMaxBacklogs() {
        return maxBacklogs;
    }

    public void setMaxBacklogs(Integer maxBacklogs) {
        this.maxBacklogs = maxBacklogs;
    }

    public List<String> getEligibleBranches() {
        return eligibleBranches;
    }

    public void setEligibleBranches(List<String> eligibleBranches) {
        this.eligibleBranches = eligibleBranches;
    }

    public Instant getApplicationDeadline() {
        return applicationDeadline;
    }

    public void setApplicationDeadline(Instant applicationDeadline) {
        this.applicationDeadline = applicationDeadline;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
    
}
