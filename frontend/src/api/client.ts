import axios, { AxiosError, type AxiosRequestConfig } from "axios";

/**
 * The single place this app talks to the backend.
 *
 * Every other file in src/api goes through the `api` object at the bottom, so token
 * handling, error shaping and 401 behaviour are written once - in the two Axios
 * interceptors below - rather than repeated in each page.
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
 * The one Axios instance the whole app uses.
 *
 * Requests use relative paths such as /api/drives, joined onto the page's own origin. In
 * development the Vite dev server proxies /api to localhost:8081, so the browser sees a
 * single origin and no CORS configuration is needed anywhere. The origin is spelled out
 * rather than left implicit because a Request built outside a browser page - in the
 * tests, under Node - has no page address to resolve "/api/drives" against.
 *
 * The "fetch" adapter makes Axios send requests with the browser's own fetch rather than
 * the older XMLHttpRequest it picks by default.
 */
const http = axios.create({ baseURL: window.location.origin, adapter: "fetch" });

/**
 * Request interceptor: runs on every request just before it leaves the browser, and
 * attaches the bearer token when there is one. This is the whole reason no page ever
 * has to think about the Authorization header.
 */
http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }
  return config;
});

/**
 * Response interceptor: runs on every failed response before the calling code sees it.
 *
 * - On a 401, clears the stored token. The token is either missing, expired or invalid,
 *   and keeping it would make every later request fail the same way. Redirecting is left
 *   to the caller so this module stays independent of the router.
 * - Turns any error response into an ApiError carrying the status and the server's
 *   message, so pages can show something useful instead of "something went wrong".
 * - A request that got no response at all (server down, network gone) is passed on
 *   unchanged; pages show "Could not reach the server" for anything that is not an
 *   ApiError.
 */
http.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (!error.response) {
      throw error;
    }
    if (error.response.status === 401) {
      clearToken();
    }
    throw new ApiError(error.response.status, await errorMessage(error.response.status, error.response.data));
  },
);

/** Pulls the most useful message out of an error response, whatever shape it is in. */
async function errorMessage(status: number, data: unknown): Promise<string> {
  let body = data;

  // A download asks for a Blob, and Axios then delivers even an error's JSON as one.
  if (body instanceof Blob) {
    try {
      body = JSON.parse(await body.text());
    } catch {
      body = null;
    }
  }

  if (body !== null && typeof body === "object") {
    const problem = body as ProblemDetail;
    return problem.detail ?? problem.title ?? `Request failed (${status})`;
  }
  // Not JSON at all - a proxy error page, or a dead server.
  return `Request failed (${status})`;
}

/**
 * Performs a request and returns its body. Axios has already parsed JSON and set the
 * JSON content type for a plain-object body; a FormData body is left for the browser to
 * label as multipart/form-data itself, boundary included.
 */
async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<T>(config);

  // 204 No Content has an empty body, so there is nothing to return.
  return response.status === 204 ? (undefined as T) : response.data;
}

export const api = {
  get: <T>(path: string) => request<T>({ method: "GET", url: path }),
  // body is optional: a POST that only performs an action (withdrawing an application,
  // say) has nothing to send.
  post: <T>(path: string, body?: unknown) => request<T>({ method: "POST", url: path, data: body }),
  put: <T>(path: string, body: unknown) => request<T>({ method: "PUT", url: path, data: body }),
  patch: <T>(path: string, body: unknown) => request<T>({ method: "PATCH", url: path, data: body }),
  delete: <T>(path: string) => request<T>({ method: "DELETE", url: path }),
  /** A multipart form POST, which is how a file travels - see uploadResume. */
  postForm: <T>(path: string, form: FormData) => request<T>({ method: "POST", url: path, data: form }),
  /**
   * A binary body such as a PDF, rather than JSON. Going through the same instance keeps
   * the token and error handling identical to every other call, which a plain <a href>
   * could not: a link cannot carry an Authorization header.
   */
  getBlob: (path: string) => request<Blob>({ method: "GET", url: path, responseType: "blob" }),
};
