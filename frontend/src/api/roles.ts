import type { Role } from "./types";

/**
 * Which roles can do what, in one place, mirroring MANAGERS and STUDENTS in the backend's
 * SecurityConfig. The backend's 403 is the real rule; these only decide what to show.
 *
 * Kept apart from auth.ts on purpose: auth.ts makes HTTP calls, so tests mock it wholesale,
 * and a mocked copy of these would answer `undefined` for every role.
 */

/** Roles that run hiring - post drives, review applicants. */
export const MANAGERS: Role[] = ["TNP_COORDINATOR", "RECRUITER", "TNP_PIC"];

/** Roles that are students - apply, resume feedback, assistant. A coordinator is both. */
export const STUDENTS: Role[] = ["STUDENT", "TNP_COORDINATOR"];

export function canManageDrives(role: Role | undefined | null): boolean {
  return role != null && MANAGERS.includes(role);
}

export function isStudentRole(role: Role | undefined | null): boolean {
  return role != null && STUDENTS.includes(role);
}
