package com.rishikesh.placementprep.modules.auth.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A person who can sign in.
 *
 * <p>This is deliberately a plain JPA entity and does <em>not</em> implement Spring
 * Security's {@code UserDetails}. Most tutorials merge the two, which drags a web-security
 * type into the persistence model. Keeping them apart is the same rule already applied to
 * DriveService, which stays free of HTTP types. AppUserDetailsService adapts this entity
 * into what Spring Security wants.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    /** A BCrypt hash. The plain-text password is never stored, logged, or returned. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Role role;

    // ---- Student profile ------------------------------------------------------
    // All nullable: an account exists from registration, but the marks are filled in
    // afterwards, and a TNP administrator never fills them in at all. Anything reading
    // these must cope with them being absent.

    private BigDecimal cgpa;

    private String branch;

    private BigDecimal tenthPercentage;

    private BigDecimal twelfthPercentage;

    /**
     * Active backlogs.
     *
     * <p>Initialised here rather than relying on the column's DEFAULT 0. A SQL default
     * only applies when the column is left out of the INSERT, and Hibernate always lists
     * every mapped column, so it would send an explicit NULL and violate the constraint.
     * Defaulting in Java covers every path that creates a user.
     */
    @Column(nullable = false)
    private Integer backlogs = 0;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public User() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public BigDecimal getCgpa() {
        return cgpa;
    }

    public void setCgpa(BigDecimal cgpa) {
        this.cgpa = cgpa;
    }

    public String getBranch() {
        return branch;
    }

    public void setBranch(String branch) {
        this.branch = branch;
    }

    public BigDecimal getTenthPercentage() {
        return tenthPercentage;
    }

    public void setTenthPercentage(BigDecimal tenthPercentage) {
        this.tenthPercentage = tenthPercentage;
    }

    public BigDecimal getTwelfthPercentage() {
        return twelfthPercentage;
    }

    public void setTwelfthPercentage(BigDecimal twelfthPercentage) {
        this.twelfthPercentage = twelfthPercentage;
    }

    public Integer getBacklogs() {
        return backlogs;
    }

    public void setBacklogs(Integer backlogs) {
        this.backlogs = backlogs;
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
