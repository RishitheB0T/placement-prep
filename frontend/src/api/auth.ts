import { api, clearToken, getToken, setToken } from "./client";
import type { AuthResponse, UpdateProfileRequest, UserProfile } from "./types";

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

/** The signed-in user and their academic profile. */
export function fetchMyProfile(): Promise<UserProfile> {
  return api.get<UserProfile>("/api/users/me");
}

/** Replaces my academic details. PUT replaces in full, so every field must be supplied. */
export function updateMyProfile(profile: UpdateProfileRequest): Promise<UserProfile> {
  return api.put<UserProfile>("/api/users/me", profile);
}
