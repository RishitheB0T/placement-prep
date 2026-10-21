import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it } from "vitest";

import { setToken } from "../api/client";
import type { Role } from "../api/types";
import ProtectedRoute from "./ProtectedRoute";

/**
 * A token shaped like the backend's: three base64url segments, with the role in the
 * middle one. The signature is junk - the browser never checks it, only the server does.
 */
function tokenFor(role: Role): string {
  const encode = (value: object) =>
    btoa(JSON.stringify(value)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${encode({ alg: "HS256" })}.${encode({ sub: "someone@cse.nits.ac.in", role })}.signature`;
}

function renderAt(path: string, roles?: Role[]) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/post-drive"
          element={
            <ProtectedRoute roles={roles}>
              <div>post a drive page</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
        <Route path="/dashboard" element={<div>dashboard page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

/** Each role's dashboard is the set of routes it is let into. */
describe("ProtectedRoute", () => {
  it("sends a signed-out visitor to the login page", () => {
    renderAt("/post-drive", ["TNP_COORDINATOR", "TNP_PIC"]);

    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  it("lets a role that belongs on the page through", () => {
    setToken(tokenFor("TNP_COORDINATOR"));
    renderAt("/post-drive", ["TNP_COORDINATOR", "TNP_PIC"]);

    expect(screen.getByText("post a drive page")).toBeInTheDocument();
  });

  it("sends a role that does not belong back to their dashboard", () => {
    setToken(tokenFor("STUDENT"));
    renderAt("/post-drive", ["TNP_COORDINATOR", "TNP_PIC"]);

    expect(screen.getByText("dashboard page")).toBeInTheDocument();
    expect(screen.queryByText("post a drive page")).not.toBeInTheDocument();
  });

  it("treats a stored string that is not a JWT as having no role", () => {
    setToken("not-a-token");
    renderAt("/post-drive", ["TNP_PIC"]);

    expect(screen.getByText("dashboard page")).toBeInTheDocument();
  });

  it("only requires a sign-in when no roles are given", () => {
    setToken(tokenFor("STUDENT"));
    renderAt("/post-drive");

    expect(screen.getByText("post a drive page")).toBeInTheDocument();
  });
});
