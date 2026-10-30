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

  /** A text/event-stream response whose body arrives in exactly these chunks. */
  const eventStream = (chunks: string[]) => {
    const encoder = new TextEncoder();
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
        controller.close();
      },
    });
    return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
  };

  // Axios's fetch adapter builds a Request object and hands that to fetch, so what each
  // call sent - method, headers, body - is read off the Request.
  const requestOf = (call: number) => fetchMock.mock.calls[call][0] as Request;
  const headersOf = (call: number) => requestOf(call).headers;

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

  it("never labels a file upload as JSON", async () => {
    // The browser writes multipart/form-data plus the boundary between the parts. A JSON
    // header in its place would leave the server unable to find the file. (Under jsdom
    // the exact multipart header cannot be checked - its FormData is not the one Node's
    // Request understands - so the test pins down the part this code controls.)
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));
    const form = new FormData();
    form.append("driveId", "1");

    await api.postForm("/api/resumes", form);

    expect(requestOf(0).method).toBe("POST");
    expect(headersOf(0).get("Content-Type")).not.toBe("application/json");
  });

  it("returns a binary body as a Blob, with the token still attached", async () => {
    setToken("a.b.c");
    // A string body, not new Blob([...]): under jsdom, Blob is jsdom's own class while
    // Response is Node's, and Node's Response turns a foreign Blob into "[object Blob]".
    fetchMock.mockResolvedValue(new Response("%PDF-1.4", { status: 200 }));

    const blob = await api.getBlob("/api/resumes/1/file");

    expect(await blob.text()).toBe("%PDF-1.4");
    expect(headersOf(0).get("Authorization")).toBe("Bearer a.b.c");
  });

  it("sends no body for a bodiless POST, which withdraw relies on", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ok: true }));

    await api.post("/api/applications/1/withdraw");

    expect(requestOf(0).body).toBeNull();
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

  /** A rejected form names the fields to fix, instead of "Invalid request content." */
  it("spells out the field errors of a rejected form", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          title: "Bad Request",
          detail: "Invalid request content.",
          errors: {
            cgpaCutoff: "must be less than or equal to 10.0",
            backlogLimitConsistent: "Enter the maximum number of backlogs, since backlogs are allowed",
          },
        },
        400,
      ),
    );

    await expect(api.post("/api/drives", {})).rejects.toMatchObject({
      status: 400,
      message: "Cgpa cutoff: must be less than or equal to 10.0. Enter the maximum number of backlogs, since backlogs are allowed",
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

  it("reads a stream of events as they arrive, even when chunks split them mid-line", async () => {
    setToken("a.b.c");
    // Three chunks that cut across event and line boundaries, the way a network may.
    fetchMock.mockResolvedValue(
      eventStream(['event:token\ndata:{"te', 'xt":"Hi "}\n\nevent:tok', 'en\ndata:{"text":"there"}\n\n']),
    );

    const events = [];
    for await (const event of api.postForEvents("/api/agent/chat/stream", { message: "Hello" })) {
      events.push(event);
    }

    expect(events).toEqual([
      { event: "token", data: '{"text":"Hi "}' },
      { event: "token", data: '{"text":"there"}' },
    ]);
    expect(requestOf(0).method).toBe("POST");
    expect(headersOf(0).get("Authorization")).toBe("Bearer a.b.c");
  });

  it("turns a stream request refused before it began into the usual ApiError", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ title: "Service Unavailable", detail: "The AI assistant is not switched on for this server yet." }, 503),
    );

    const firstEvent = api.postForEvents("/api/agent/chat/stream", { message: "Hello" }).next();

    await expect(firstEvent).rejects.toMatchObject({
      status: 503,
      message: "The AI assistant is not switched on for this server yet.",
    });
  });

  it("round-trips the token through storage", () => {
    expect(getToken()).toBeNull();
    setToken("a.b.c");
    expect(getToken()).toBe("a.b.c");
    clearToken();
    expect(getToken()).toBeNull();
  });
});
