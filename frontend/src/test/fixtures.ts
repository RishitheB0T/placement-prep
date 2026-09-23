import type { Application, Drive, UserProfile } from "../api/types";

/**
 * Builders for the three shapes every page test needs.
 *
 * Each takes overrides so a test states only the field it actually cares about - a test
 * about the coordinator's Apply button should not have to spell out a CGPA to get there,
 * and the ones it does spell out are then obviously the point of the test.
 */

const BASE_PROFILE: UserProfile = {
  id: 1,
  email: "asha@cse.nits.ac.in",
  role: "STUDENT",
  cgpa: 8.5,
  branch: "CSE",
  tenthPercentage: 88,
  twelfthPercentage: 85,
  backlogs: 0,
  complete: true,
  designation: null,
  department: null,
  staffId: null,
  officeLocation: null,
  phoneNumber: null,
};

export function student(overrides: Partial<UserProfile> = {}): UserProfile {
  return { ...BASE_PROFILE, ...overrides };
}

/** A coordinator is a student with extra powers, so they keep the academic fields. */
export function coordinator(overrides: Partial<UserProfile> = {}): UserProfile {
  return {
    ...BASE_PROFILE,
    id: 2,
    email: "coord@tnp.nits.ac.in",
    role: "TNP_COORDINATOR",
    ...overrides,
  };
}

/** The PIC is faculty: no academic fields at all, staff fields instead. */
export function personInCharge(overrides: Partial<UserProfile> = {}): UserProfile {
  return {
    ...BASE_PROFILE,
    id: 3,
    email: "tnp@pic.nits.ac.in",
    role: "TNP_PIC",
    cgpa: null,
    branch: null,
    tenthPercentage: null,
    twelfthPercentage: null,
    complete: false,
    designation: "Training & Placement Officer",
    department: "TNP Cell",
    staffId: "STAFF-001",
    officeLocation: "Admin Block",
    phoneNumber: "9876543210",
    ...overrides,
  };
}

export function drive(overrides: Partial<Drive> = {}): Drive {
  return {
    id: 10,
    companyName: "Zoho",
    role: "Member Technical Staff",
    ctc: 900000,
    tier: 1,
    cgpaCutoff: 7.5,
    tenthCutoff: 75,
    twelfthCutoff: 70,
    backlogsAllowed: false,
    maxBacklogs: null,
    eligibleBranches: ["CSE", "ECE"],
    // Far enough out that "Closed" never appears by accident as the suite ages.
    applicationDeadline: "2099-06-30T18:00:00Z",
    description: null,
    ...overrides,
  };
}

export function application(overrides: Partial<Application> = {}): Application {
  return {
    id: 100,
    studentId: 1,
    studentEmail: null,
    driveId: 10,
    status: "APPLIED",
    note: null,
    appliedAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    ...overrides,
  };
}
