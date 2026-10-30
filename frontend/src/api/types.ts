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

export type Role = "STUDENT" | "TNP_COORDINATOR" | "RECRUITER" | "TNP_PIC";

/** Returned by POST /api/auth/register and POST /api/auth/login. */
export interface AuthResponse {
  token: string;
  email: string;
  role: Role;
  /** Epoch milliseconds at which the token stops being accepted. */
  expiresAt: number;
}

/**
 * Returned by GET /api/users/me, and by both profile PUT endpoints.
 *
 * `complete` is derived on the server from the isComplete() method on the record, and
 * says whether enough of the *academic* profile is filled in to judge eligibility - it
 * says nothing about the PIC-only fields below, since nothing in the system gates a
 * feature behind those being filled in.
 *
 * The five staff fields are PIC-only, enforced by a database CHECK constraint: always
 * null for a STUDENT or a TNP_COORDINATOR, exactly like `cgpa` is always null for staff.
 * One flat shape for every role is the same choice already made for the academic fields -
 * pages branch on `role` to decide which half of this to show.
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
  designation: string | null;
  department: string | null;
  staffId: string | null;
  officeLocation: string | null;
  phoneNumber: string | null;
}

/** Body of PUT /api/users/me. Every field is required, because PUT replaces in full. */
export interface UpdateProfileRequest {
  cgpa: number;
  branch: string;
  tenthPercentage: number;
  twelfthPercentage: number;
  backlogs: number;
}

/**
 * Body of PUT /api/users/me/staff-profile. PIC only - the backend answers 403 for anyone
 * else, including a coordinator.
 *
 * Every field is optional, unlike UpdateProfileRequest: nothing depends on a PIC's staff
 * details being complete, so there is no all-or-nothing rule to enforce here. A field left
 * out still replaces the stored value with null - PUT replaces the whole sub-resource -
 * it is just that "null" is itself a valid, unenforced outcome for every field.
 */
export interface UpdateStaffProfileRequest {
  designation: string | null;
  department: string | null;
  staffId: string | null;
  officeLocation: string | null;
  phoneNumber: string | null;
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
  /**
   * Filled in only when the placement cell is reviewing a drive's applicants, where a
   * bare id is not something a human can act on. Null on a student's own list - they
   * already know who they are, so the backend skips the lookup.
   */
  studentEmail: string | null;
  driveId: number;
  status: ApplicationStatus;
  note: string | null;
  appliedAt: string;
  updatedAt: string;
}

/**
 * What a notification is about: a new drive the student is eligible for, a deadline 3
 * days or 1 day away on a drive they have not applied to, or a change to one of their
 * applications.
 */
export type NotificationKind = "NEW_DRIVE" | "DEADLINE_3_DAYS" | "DEADLINE_1_DAY" | "STATUS_CHANGED";

/** Returned by /api/notifications/me - one entry in the student's bell. */
export interface Notification {
  id: number;
  kind: NotificationKind;
  /** Written in full by the backend, ready to show. */
  message: string;
  /** The drive it is about, or null. */
  driveId: number | null;
  createdAt: string;
  read: boolean;
}

/**
 * Where a resume's review has got to. Uploading creates PENDING; the backend's background
 * job moves it through PROCESSING to COMPLETED or FAILED, and only FAILED can be retried.
 */
export type ResumeStatus = "PENDING" | "PROCESSING" | "COMPLETED" | "FAILED";

/**
 * Returned by every /api/resumes endpoint. driveId is a bare id like on Application - the
 * resume page resolves it to a company name from the drives list it already loads.
 *
 * score, summary, strengths and improvements are only meaningful once status is
 * COMPLETED; the two lists are empty arrays, never null, before then.
 */
export interface Resume {
  id: number;
  driveId: number;
  originalFilename: string;
  sizeBytes: number;
  status: ResumeStatus;
  /** 0 to 100: how well the resume fits the drive. */
  score: number | null;
  summary: string | null;
  strengths: string[];
  improvements: string[];
  /** Set only when status is FAILED, in words meant for the student. */
  failureReason: string | null;
  uploadedAt: string;
  reviewedAt: string | null;
}

/**
 * Returned by GET /api/applications/stats - recruiters and admins only. Every status is
 * present in byStatus, zero included. awaitingReviewByDrive maps a drive id (a string,
 * since JSON object keys always are) to how many of its applications are still APPLIED.
 */
export interface ApplicationStats {
  byStatus: Record<ApplicationStatus, number>;
  awaitingReviewByDrive: Record<string, number>;
}

/**
 * One earlier message in an assistant conversation. The server keeps no history, so the
 * page sends the conversation so far with every new message.
 */
export interface ChatTurn {
  text: string;
  fromAssistant: boolean;
}

/** Returned by POST /api/agent/chat. */
export interface ChatResponse {
  reply: string;
  /** The tools the assistant called to answer, such as "eligibleDrives"; empty if none. */
  toolsUsed: string[];
}

/** One piece of a reply that is still being written. */
export interface ChatChunk {
  text: string;
}

/**
 * One passage of an uploaded job-description PDF that matched a search. Results arrive
 * best first, ranked by the search by meaning and the search by words together.
 */
export interface JobDescriptionMatch {
  driveId: number;
  companyName: string;
  role: string;
  excerpt: string;
  /**
   * How close in meaning the passage is to the search: 0 unrelated, 1 the same meaning.
   * 0 when only the words matched.
   */
  score: number;
  /** Which half of the search found it: by meaning, by its words, or both. */
  matchedBy: "MEANING" | "KEYWORDS" | "BOTH";
}

/** Returned after a job-description PDF is uploaded. */
export interface IndexedJobDescription {
  driveId: number;
  /** How many searchable passages the PDF was split into. */
  passages: number;
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
