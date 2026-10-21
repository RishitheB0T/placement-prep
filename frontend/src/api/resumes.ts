import { api } from "./client";
import type { Resume } from "./types";

/** Mirrors spring.servlet.multipart.max-file-size on the backend: 5 MB. */
export const MAX_RESUME_BYTES = 5 * 1024 * 1024;

/**
 * Uploads a PDF resume for one drive and queues it for AI review.
 *
 * Sent as multipart/form-data with two parts, driveId and file - the shape
 * ResumeController's two @RequestParam arguments read. Resolves as soon as the file is
 * stored, with status PENDING; the review itself happens afterwards on the server, so
 * the page polls fetchMyResumes until it is done.
 *
 * Throws an ApiError with status 409 while an earlier resume for the same drive is still
 * being reviewed, 400 for a file that is not really a PDF, and 413 for one over 5 MB.
 */
export function uploadResume(driveId: number, file: File): Promise<Resume> {
  const form = new FormData();
  form.append("driveId", String(driveId));
  form.append("file", file);
  return api.postForm<Resume>("/api/resumes", form);
}

/** Every resume the signed-in student uploaded, newest first. */
export function fetchMyResumes(): Promise<Resume[]> {
  return api.get<Resume[]>("/api/resumes/me");
}

/** Sends a FAILED review back to the queue. 409 for any other status. */
export function retryResumeReview(id: number): Promise<Resume> {
  return api.post<Resume>(`/api/resumes/${id}/retry`);
}

/** The PDF the student uploaded, to open again in a new tab. */
export function fetchResumeFile(id: number): Promise<Blob> {
  return api.getBlob(`/api/resumes/${id}/file`);
}
