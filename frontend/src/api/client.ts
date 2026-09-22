/**
 * The single place this app talks to the backend.
 *
 * Every other file in src/api goes through `request` below, so token handling, error
 * shaping and 401 behaviour are written once rather than repeated in each page.
 */

const TOKEN_KEY = "placementprep.token";

/**
 * The token lives in localStorage.
 *
 * The trade-off, worth knowing rather than just accepting: localStorage survives a page
 * refresh and is simple, but any JavaScript running on the page can read it, so a
 * cross-site scripting flaw hands an attacker a valid session. The safer production
 * answer is an httpOnly cookie that JavaScript cannot touch - but that reintroduces CSRF,
 * which this API deliberately disabled because it is stateless and cookie-free.
 * localStorage is the pragmatic choice here; it is not the only one.
 */
export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

/**
 * An error carrying the HTTP status, so callers can react to specific ones - 409 meaning
 * "your profile is incomplete" is handled differently from a generic failure.
 */
export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

/** The RFC 9457 problem-detail body the backend returns for every error. */
interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
}

/**
 * Performs a request and returns the parsed body.
 *
 * - Attaches the bearer token when there is one.
 * - Turns any non-2xx response into an ApiError carrying the status and the server's
 *   message, so pages can show something useful instead of "something went wrong".
 * - On a 401, clears the stored token. The token is either missing, expired or invalid,
 *   and keeping it would make every later request fail the same way. Redirecting is left
 *   to the caller so this module stays independent of the router.
 */
async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();

  const headers = new Headers(options.headers);
  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  // A relative URL: in development the Vite dev server proxies /api to localhost:8081,
  // so the browser sees a single origin and no CORS configuration is needed anywhere.
  const response = await fetch(path, { ...options, headers });

  if (response.status === 401) {
    clearToken();
  }

  if (!response.ok) {
    throw new ApiError(response.status, await errorMessage(response));
  }

  // 204 No Content has an empty body, so parsing it as JSON would throw.
  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

/** Pulls the most useful message out of an error response, whatever shape it is in. */
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as ProblemDetail;
    return body.detail ?? body.title ?? `Request failed (${response.status})`;
  } catch {
    // Not JSON at all - a proxy error page, or a dead server.
    return `Request failed (${response.status})`;
  }
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  // body is optional: a POST that only performs an action (withdrawing an application,
  // say) has nothing to send, and `request` already treats "no body" and "body:
  // undefined" identically when deciding whether to set the JSON content type.
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: "POST", ...(body === undefined ? {} : { body: JSON.stringify(body) }) }),
  put: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PUT", body: JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown) =>
    request<T>(path, { method: "PATCH", body: JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: "DELETE" }),
};
