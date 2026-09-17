import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";

import { isLoggedIn } from "../api/auth";

/**
 * Wraps a route that requires a signed-in user.
 *
 * <p>This is a convenience, not a security control. It only checks whether a token is
 * stored, not whether it is valid - anyone can put a string in their own localStorage.
 * The real enforcement is on the server, which verifies the signature on every request.
 * The purpose here is to send a signed-out visitor to the login page instead of showing
 * them a screen that would only fill with 401 errors.
 */
export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const location = useLocation();

  if (!isLoggedIn()) {
    // `replace` keeps the protected URL out of history, so the back button does not
    // bounce the user between login and a page they still cannot see. `state` remembers
    // where they were heading so login can return them there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  return <>{children}</>;
}
