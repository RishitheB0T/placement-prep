import { api } from "./client";
import type { Notification } from "./types";

/**
 * My newest 50 notifications, newest first. Students and coordinators only - the backend
 * answers 403 for a recruiter or the admin, who never receive any.
 */
export function fetchMyNotifications(): Promise<Notification[]> {
  return api.get<Notification[]>("/api/notifications/me");
}

/** Marks one of mine read and returns it updated. 404 for anyone else's. */
export function markNotificationRead(id: number): Promise<Notification> {
  return api.post<Notification>(`/api/notifications/${id}/read`);
}

/** Marks all of mine read. */
export function markAllNotificationsRead(): Promise<void> {
  return api.post<void>("/api/notifications/read-all");
}
