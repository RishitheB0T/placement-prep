/**
 * TypeScript mirrors of the backend DTOs.
 *
 * These are hand-written and therefore can drift from the Java records if someone changes
 * one and not the other. TypeScript checks that our own code uses these shapes
 * consistently, but it cannot check that the server actually sends them - types are
 * erased at runtime, so a mismatch shows up as `undefined` on screen rather than a
 * compile error. Keep them in step by hand until the project is big enough to justify
 * generating them from an OpenAPI document.
 */

export type Role = "STUDENT" | "TNP_COORDINATOR" | "TNP_PIC";

/** Returned by POST /api/auth/register and POST /api/auth/login. */
export interface AuthResponse {
  token: string;
  email: string;
  role: Role;
  /** Epoch milliseconds at which the token stops being accepted. */
  expiresAt: number;
}

/**
 * Returned by GET and PUT /api/users/me.
 *
 * `complete` is derived on the server from the isComplete() method on the record, and
 * says whether enough of the profile is filled in to judge eligibility.
 */
export interface UserProfile {
  id: number;
  email: string;
  role: Role;
  cgpa: number | null;
  branch: string | null;
  tenthPercentage: number | null;
  twelfthPercentage: number | null;
  backlogs: number;
  complete: boolean;
}

/** Body of PUT /api/users/me. Every field is required, because PUT replaces in full. */
export interface UpdateProfileRequest {
  cgpa: number;
  branch: string;
  tenthPercentage: number;
  twelfthPercentage: number;
  backlogs: number;
}

/** A placement drive, as returned by /api/drives. */
export interface Drive {
  id: number;
  companyName: string;
  role: string;
  ctc: number;
  tier: number;
  cgpaCutoff: number | null;
  tenthCutoff: number | null;
  twelfthCutoff: number | null;
  backlogsAllowed: boolean;
  maxBacklogs: number | null;
  eligibleBranches: string[];
  /** ISO-8601 instant, e.g. "2026-12-20T18:00:00Z". */
  applicationDeadline: string;
  description: string | null;
}

/**
 * Where an application has got to. The student controls only APPLIED (by applying) and
 * WITHDRAWN; everything between is the placement cell's decision.
 */
export type ApplicationStatus = "APPLIED" | "SHORTLISTED" | "REJECTED" | "SELECTED" | "WITHDRAWN";

/**
 * Returned by every /api/applications endpoint. studentId and driveId are bare ids
 * rather than embedded objects - the backend keeps this DTO flat, so a page that wants a
 * company name or a student's email resolves it itself from data it already has (the
 * drives list, in the student view) rather than the API silently getting heavier.
 */
export interface Application {
  id: number;
  studentId: number;
  driveId: number;
  status: ApplicationStatus;
  note: string | null;
  appliedAt: string;
  updatedAt: string;
}

/**
 * Body of POST and PUT /api/drives - the same shape serves both, mirroring DriveRequest
 * on the backend. tenthCutoff, twelfthCutoff, maxBacklogs and description are nullable:
 * a null cutoff means the drive sets no requirement for that qualification, and a null
 * maxBacklogs with backlogsAllowed true means no ceiling.
 */
export interface DriveRequest {
  companyName: string;
  role: string;
  ctc: number;
  tier: number;
  cgpaCutoff: number;
  tenthCutoff: number | null;
  twelfthCutoff: number | null;
  backlogsAllowed: boolean;
  maxBacklogs: number | null;
  eligibleBranches: string[];
  applicationDeadline: string;
  description: string | null;
}
