import { api } from "./client";
import type { Drive, DriveRequest } from "./types";

/** Every drive on the board, newest first. Any signed-in user may read this. */
export function fetchAllDrives(): Promise<Drive[]> {
  return api.get<Drive[]>("/api/drives");
}

/**
 * Drives the signed-in student is eligible for, soonest deadline first.
 *
 * Takes no arguments on purpose: the backend reads the student's CGPA, branch,
 * percentages and backlog count from their stored profile. That is both less to get wrong
 * here and impossible to lie about from the browser.
 *
 * Throws an ApiError with status 409 when the profile is not filled in yet.
 */
export function fetchEligibleDrives(): Promise<Drive[]> {
  return api.get<Drive[]>("/api/drives/eligible");
}

/**
 * Posts a new drive. The backend restricts this to TNP_COORDINATOR and TNP_PIC with a
 * 403 - this function does not and cannot enforce that itself, since anything running in
 * the browser is something the user can already see and bypass. The server is the actual
 * gate; hiding the button from a student on this page is only ever a convenience.
 */
export function createDrive(request: DriveRequest): Promise<Drive> {
  return api.post<Drive>("/api/drives", request);
}

/** One drive by id. Any signed-in user may read it; used to fill the edit form. */
export function fetchDriveById(id: number): Promise<Drive> {
  return api.get<Drive>(`/api/drives/${id}`);
}

/**
 * Replaces a drive. PUT replaces in full, so every field must be sent - omitting an
 * optional cutoff clears it rather than leaving the stored value alone. Same staff-only
 * restriction as createDrive.
 */
export function updateDrive(id: number, drive: DriveRequest): Promise<Drive> {
  return api.put<Drive>(`/api/drives/${id}`, drive);
}

/** Same restriction as createDrive. Returns 204 with no body on success. */
export function deleteDrive(id: number): Promise<void> {
  return api.delete<void>(`/api/drives/${id}`);
}
