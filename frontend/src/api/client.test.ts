import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError, api, clearToken, getToken, setToken } from "./client";

/**
 * The one place every request passes through, so the things it does once on everyone's
 * behalf - attaching the token, shaping errors, dropping a rejected token - are worth
 * pinning down here rather than rediscovering them from a page that misbehaves.
 */
describe("api client", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const jsonResponse = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  const headersOf = (call: number) => new Headers(fetchMock.mock.calls[call][1].headers);

  it("sends no Authorization header when there is no token", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await api.get("/api/drives");

    expect(headersOf(0).has("Authorization")).toBe(false);
  });

  it("attaches the stored token to every request", async () => {
    setToken("a.b.c");
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await api.get("/api/drives");

    expect(headersOf(0).get("Authorization")).toBe("Bearer a.b.c");
  });

  it("sets a JSON content type only when there is a body", async () => {
    // mockImplementation, not mockResolvedValue: a Response body can only be read once,
    // so two calls need two Response objects rather than the same one twice.
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse({ ok: true })));

    await api.get("/api/drives");
    await api.post("/api/applications", { driveId: 1 });

    expect(headersOf(0).has("Content-Type")).toBe(false);
    expect(headersOf(1).get("Content-Type")).toBe("application/json");
  });

  it("sends no body for a bodiless POST, which withdraw relies on", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await api.post("/api/applications/1/withdraw");

    expect(fetchMock.mock.calls[0][1].body).toBeUndefined();
  });

  it("unwraps an RFC 9457 problem body into the thrown error's message", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ title: "Conflict", detail: "You have already applied to this drive" }, 409),
    );

    await expect(api.post("/api/applications", { driveId: 1 })).rejects.toMatchObject({
      status: 409,
      message: "You have already applied to this drive",
    });
  });

  it("still produces a usable error when the body is not JSON at all", async () => {
    // A dead server or a proxy error page, rather than the API answering.
    fetchMock.mockResolvedValue(new Response("<html>502</html>", { status: 502 }));

    await expect(api.get("/api/drives")).rejects.toThrow(/502/);
  });

  it("clears the stored token on a 401, since it is expired or invalid", async () => {
    setToken("a.b.c");
    fetchMock.mockResolvedValue(jsonResponse({ title: "Unauthorized" }, 401));

    await expect(api.get("/api/users/me")).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBeNull();
  });

  it("keeps the token on a 403 - the caller is known, just not allowed", async () => {
    setToken("a.b.c");
    fetchMock.mockResolvedValue(jsonResponse({ title: "Forbidden" }, 403));

    await expect(api.get("/api/applications/drive/1")).rejects.toBeInstanceOf(ApiError);
    expect(getToken()).toBe("a.b.c");
  });

  it("returns nothing for a 204, where parsing a body would throw", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));

    await expect(api.delete("/api/drives/1")).resolves.toBeUndefined();
  });

  it("round-trips the token through storage", () => {
    expect(getToken()).toBeNull();
    setToken("a.b.c");
    expect(getToken()).toBe("a.b.c");
    clearToken();
    expect(getToken()).toBeNull();
  });
});
