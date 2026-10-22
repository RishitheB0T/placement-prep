import { api, clearToken, getToken, setToken } from "./client";
import type { AuthResponse, Role, UpdateProfileRequest, UpdateStaffProfileRequest, UserProfile } from "./types";

/**
 * Creates an account and signs in.
 *
 * The backend always creates a STUDENT and ignores any role sent from here, so there is
 * deliberately no role parameter to pass.
 */
export async function register(email: string, password: string): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/api/auth/register", { email, password });
  setToken(response.token);
  return response;
}

/** Exchanges credentials for a token and remembers it. */
export async function login(email: string, password: string): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/api/auth/login", { email, password });
  setToken(response.token);
  return response;
}

/**
 * Signs out by forgetting the token.
 *
 * There is no server call: the API is stateless and issues no revocation list, so a token
 * stays technically valid until it expires. Discarding it is all a client can do, which
 * is one honest downside of JWTs compared with server-side sessions.
 */
export function logout(): void {
  clearToken();
}

/** Whether a token is stored. Not proof it is still valid - only the server decides that. */
export function isLoggedIn(): boolean {
  return getToken() !== null;
}

/**
 * The role written inside the stored token, read without verifying it.
 *
 * A JWT is three base64url segments, and the middle one is plain JSON - the claims - so
 * the browser can read the role the server put there at sign-in. What the browser cannot
 * do is check the signature; only the server holds the secret. That is why this is used
 * only to decide which pages to show: every request is still checked by the server, which
 * re-reads the real role from the database. A promotion therefore shows up in the browser
 * after the next sign-in, when a token carrying the new role is issued.
 */
export function currentRole(): Role | null {
  const token = getToken();
  if (token === null) {
    return null;
  }
  try {
    // base64url uses - and _ where base64 uses + and /.
    const payload = token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/");
    const role = (JSON.parse(atob(payload)) as { role?: unknown }).role;
    return role === "STUDENT" || role === "TNP_COORDINATOR" || role === "RECRUITER" || role === "TNP_PIC"
      ? role
      : null;
  } catch {
    // Not a JWT at all - anyone can put any string in their own localStorage.
    return null;
  }
}

/** Every account, or only one role, alphabetically. Admin only - 403 for anyone else. */
export function fetchUsers(role?: Role): Promise<UserProfile[]> {
  return api.get<UserProfile[]>(role ? `/api/users?role=${role}` : "/api/users");
}

/**
 * Makes a student a coordinator. Admin only. Throws an ApiError with status 409 when the
 * account is not a student.
 */
export function promoteToCoordinator(id: number): Promise<UserProfile> {
  return api.post<UserProfile>(`/api/users/${id}/promote`);
}

/**
 * Adds a company recruiter's account with a first password. Admin only. Throws an
 * ApiError with status 409 when the email is already registered.
 */
export function createRecruiter(email: string, password: string): Promise<UserProfile> {
  return api.post<UserProfile>("/api/users/recruiters", { email, password });
}

/**
 * The signed-in user and their academic profile.
 *
 * Also keeps the stored token's role in step with the account. The pages decide what to
 * show from the role inside the token (see currentRole), but the profile comes straight
 * from the database - so if the two disagree, the admin has changed this account's role
 * since sign-in (a promotion to coordinator). A fresh token is fetched then and there, so
 * the new pages open now rather than after the next sign-in. A failed refresh is ignored:
 * the profile is still right, and the next page load tries again.
 */
export async function fetchMyProfile(): Promise<UserProfile> {
  const profile = await api.get<UserProfile>("/api/users/me");
  // Read after the response arrives: if the user signed out while it was on its way,
  // there is no token any more and nothing to refresh - asking would only earn a 401.
  const tokenRole = currentRole();
  if (tokenRole !== null && profile.role !== tokenRole) {
    try {
      await refreshToken();
    } catch {
      // Keep the old token; nothing on this page depends on the refresh succeeding.
    }
  }
  return profile;
}

/** Swaps the stored token for one carrying the account's current role. */
export async function refreshToken(): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>("/api/auth/refresh");
  setToken(response.token);
  return response;
}

/**
 * Changes my own password. The backend answers 400 "Your current password is incorrect"
 * when the current one does not match, and nothing is changed.
 */
export function changeMyPassword(currentPassword: string, newPassword: string): Promise<void> {
  return api.put<void>("/api/users/me/password", { currentPassword, newPassword });
}

/**
 * Sets a new password on someone else's account - the admin's answer to a forgotten
 * password. Admin only; 404 when there is no such account.
 */
export function resetPassword(id: number, newPassword: string): Promise<void> {
  return api.post<void>(`/api/users/${id}/password`, { newPassword });
}

/** Replaces my academic details. PUT replaces in full, so every field must be supplied. */
export function updateMyProfile(profile: UpdateProfileRequest): Promise<UserProfile> {
  return api.put<UserProfile>("/api/users/me", profile);
}

/**
 * Replaces the signed-in PIC's own staff details. The backend answers 403 for anyone
 * else - this function does not and cannot enforce that itself, since a check running in
 * the browser is something the caller can already see and bypass.
 */
export function updateMyStaffProfile(profile: UpdateStaffProfileRequest): Promise<UserProfile> {
  return api.put<UserProfile>("/api/users/me/staff-profile", profile);
}
