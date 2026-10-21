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
    return role === "STUDENT" || role === "TNP_COORDINATOR" || role === "TNP_PIC" ? role : null;
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
 * Makes a student a recruiter (TNP_COORDINATOR). Admin only. Throws an ApiError with
 * status 409 when the account is not a student.
 */
export function promoteToRecruiter(id: number): Promise<UserProfile> {
  return api.post<UserProfile>(`/api/users/${id}/promote`);
}

/** The signed-in user and their academic profile. */
export function fetchMyProfile(): Promise<UserProfile> {
  return api.get<UserProfile>("/api/users/me");
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
