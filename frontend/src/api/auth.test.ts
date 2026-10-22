import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { currentRole, fetchMyProfile } from "./auth";
import { getToken, setToken } from "./client";
import type { Role } from "./types";

/**
 * fetchMyProfile keeps the stored token's role in step with the account. The pages decide
 * what to show from the token, so after a promotion the old token would hide the new
 * pages until the next sign-in - unless it is refreshed the moment the profile disagrees.
 */
describe("fetchMyProfile", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** A token whose payload carries this role. The signature is never checked in the browser. */
  const tokenFor = (role: Role) =>
    `header.${btoa(JSON.stringify({ role })).replace(/=+$/, "")}.signature`;

  const jsonResponse = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

  const profile = (role: Role) => ({ id: 1, email: "asha@cse.nits.ac.in", role });

  const urlOf = (call: number) => (fetchMock.mock.calls[call][0] as Request).url;

  it("leaves the token alone when its role still matches the account", async () => {
    setToken(tokenFor("STUDENT"));
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(profile("STUDENT"))));

    await fetchMyProfile();

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(currentRole()).toBe("STUDENT");
  });

  it("swaps in a fresh token when the account was promoted since sign-in", async () => {
    setToken(tokenFor("STUDENT"));
    const fresh = tokenFor("TNP_COORDINATOR");
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(profile("TNP_COORDINATOR"))))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse({ token: fresh, role: "TNP_COORDINATOR" })));

    const me = await fetchMyProfile();

    expect(me.role).toBe("TNP_COORDINATOR");
    expect(urlOf(1)).toMatch(/\/api\/auth\/refresh$/);
    expect(getToken()).toBe(fresh);
    expect(currentRole()).toBe("TNP_COORDINATOR");
  });

  /** Signing out while the profile request is on its way must not fire a refresh. */
  it("does not refresh when there is no token any more", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(profile("STUDENT"))));

    await fetchMyProfile();

    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("still returns the profile, and keeps the old token, if the refresh fails", async () => {
    const old = tokenFor("STUDENT");
    setToken(old);
    fetchMock
      .mockImplementationOnce(() => Promise.resolve(jsonResponse(profile("TNP_COORDINATOR"))))
      .mockImplementationOnce(() => Promise.resolve(jsonResponse({ detail: "Server error" }, 500)));

    const me = await fetchMyProfile();

    expect(me.role).toBe("TNP_COORDINATOR");
    expect(getToken()).toBe(old);
  });
});
