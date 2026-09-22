import { api } from "./client";
import type { Application, ApplicationStatus } from "./types";

/**
 * Applies the signed-in student to a drive. Only the drive is named - the backend takes
 * the student from the token, so there is no id here for a caller to fake.
 *
 * Throws an ApiError with status 409 when the student has already applied to this drive.
 */
export function applyToDrive(driveId: number): Promise<Application> {
  return api.post<Application>("/api/applications", { driveId });
}

export function fetchMyApplications(): Promise<Application[]> {
  return api.get<Application[]>("/api/applications/me");
}

/** Everyone who applied to one drive. Staff only - the backend refuses anyone else with a 403. */
export function fetchApplicationsForDrive(driveId: number): Promise<Application[]> {
  return api.get<Application[]>(`/api/applications/drive/${driveId}`);
}

/** Moves an application along. Staff only. */
export function updateApplicationStatus(
  id: number,
  status: ApplicationStatus,
  note?: string,
): Promise<Application> {
  return api.patch<Application>(`/api/applications/${id}/status`, { status, note: note ?? null });
}

/**
 * A student pulling out of a drive they applied to.
 *
 * Throws an ApiError with status 404 if the application is not yours - the backend
 * answers 404 rather than 403 here on purpose, so a guessed id cannot even confirm that
 * an application exists.
 */
export function withdrawApplication(id: number): Promise<Application> {
  return api.post<Application>(`/api/applications/${id}/withdraw`);
}
