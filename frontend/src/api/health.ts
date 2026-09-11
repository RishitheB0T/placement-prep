/** The only two states the UI cares about. */
export type HealthStatus = "UP" | "DOWN";

/** The slice of the /actuator/health response we read. */
type HealthResponse = { status?: string };

/**
 * Asks the backend whether it is healthy.
 *
 * The URL is a relative path, so in development the Vite dev server proxies it to
 * http://localhost:8081. Anything unexpected - a non-2xx response, invalid JSON, or
 * the backend simply not running - is reported as DOWN rather than thrown, because
 * the page always wants an answer to display.
 */
export async function fetchBackendHealth(): Promise<HealthStatus> {
  try {
    const response = await fetch("/actuator/health");
    if (!response.ok) {
      return "DOWN";
    }
    const body = (await response.json()) as HealthResponse;
    return body.status === "UP" ? "UP" : "DOWN";
  } catch {
    return "DOWN";
  }
}
