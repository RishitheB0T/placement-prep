import { api } from "./client";
import type { Drive } from "./types";

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
