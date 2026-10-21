import { api } from "./client";
import type { IndexedJobDescription, JobDescriptionMatch } from "./types";

/**
 * Uploads a drive's job-description PDF and makes it searchable by meaning, replacing any
 * earlier one. Recruiters and admins only - the backend answers 403 for anyone else.
 *
 * Throws an ApiError with status 422 when the PDF has no readable text (a scanned image).
 */
export function uploadJobDescription(driveId: number, file: File): Promise<IndexedJobDescription> {
  const form = new FormData();
  form.append("file", file);
  return api.postForm<IndexedJobDescription>(`/api/drives/${driveId}/job-description`, form);
}

/**
 * Job-description passages closest in meaning to the query, best first. Works without
 * any AI key: the embeddings come from a model running inside the backend itself.
 */
export function searchJobDescriptions(query: string, limit = 5): Promise<JobDescriptionMatch[]> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return api.get<JobDescriptionMatch[]>(`/api/job-descriptions/search?${params}`);
}
