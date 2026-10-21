import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router-dom";

import { currentRole, isLoggedIn } from "../api/auth";
import type { Role } from "../api/types";

/**
 * Wraps a route that requires a signed-in user - and, when `roles` is given, one of
 * those roles. This is what gives each role its own dashboard: a student's routes
 * include resume feedback and the assistant, a recruiter's add posting and editing
 * drives, and an admin's are the management pages without the student ones.
 *
 * <p>This is a convenience, not a security control. It reads the stored token without
 * verifying it - anyone can put a string in their own localStorage. The real enforcement
 * is on the server, which verifies the signature and the role on every request. The
 * purpose here is to send a visitor to a page they can actually use instead of showing
 * them a screen that would only fill with 401 or 403 errors.
 */
export default function ProtectedRoute({ children, roles }: { children: ReactNode; roles?: Role[] }) {
  const location = useLocation();

  if (!isLoggedIn()) {
    // `replace` keeps the protected URL out of history, so the back button does not
    // bounce the user between login and a page they still cannot see. `state` remembers
    // where they were heading so login can return them there.
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }

  if (roles) {
    const role = currentRole();
    if (role === null || !roles.includes(role)) {
      // Signed in, but this page is not one of this role's. Back to their own dashboard.
      return <Navigate to="/dashboard" replace />;
    }
  }

  return <>{children}</>;
}
