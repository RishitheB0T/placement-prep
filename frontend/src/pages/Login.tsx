import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { login, logout } from "../api/auth";
import { ApiError } from "../api/client";
import type { Role } from "../api/types";
import AuthLayout from "../components/AuthLayout";
import { Icon, Spinner } from "../components/ui";

type Mode = "student" | "recruiter" | "administration";

/** The one role each restricted mode accepts. The student mode accepts any account. */
const REQUIRED_ROLE: Partial<Record<Mode, Role>> = {
  recruiter: "RECRUITER",
  administration: "TNP_PIC",
};

/**
 * Sign in, in one of three framings.
 *
 * The "recruiter" and "administration" modes are enforced, each against its one role. The
 * "student" mode is the original, unrestricted form: it accepts any account, same as
 * before these tabs existed. There was no reason to lock coordinators out of anywhere
 * they could already sign in.
 *
 * The backend has no separate "administration" login endpoint - it cannot, since which
 * mode was selected is not information the server has any reason to know before the
 * password is even checked. Both modes call the same POST /api/auth/login; the
 * enforcement happens here, after a valid token comes back, by reading the role it
 * carries and deciding whether to keep it or throw it away.
 */
export default function Login() {
  const navigate = useNavigate();
  const location = useLocation();

  const [mode, setMode] = useState<Mode>("student");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // Where ProtectedRoute wanted to send them before it bounced them here - otherwise their
  // own dashboard, which shows the student, recruiter or admin view by role.
  const destination = (location.state as { from?: string } | null)?.from ?? "/dashboard";

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
  }

  async function handleSubmit(event: FormEvent) {
    // Without this the browser does a full page reload and React never sees the submit.
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      const response = await login(email, password);

      // login() already stored the token as a side effect once the password checked out,
      // so a rejection here has to actively undo that rather than simply not navigating -
      // otherwise a valid-but-wrong-tab token would be left sitting in localStorage and
      // the next page load would act as though this had succeeded.
      const required = REQUIRED_ROLE[mode];
      if (required !== undefined && response.role !== required) {
        logout();
        setError(`This is not ${mode === "recruiter" ? "a recruiter" : "an administration"} account. Use the student sign-in instead.`);
        return;
      }

      // `replace` so the back button does not return to the login form now that the
      // user is signed in.
      navigate(destination, { replace: true });
    } catch (e) {
      // The server deliberately says only "Invalid email or password" for both an
      // unknown account and a wrong password, so this message is as specific as it
      // honestly can be.
      setError(e instanceof ApiError ? e.message : "Could not reach the server");
    } finally {
      // In `finally` so the button re-enables even when the request failed.
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout>
      <form onSubmit={handleSubmit} className="mx-auto w-full max-w-sm">
        <div className="flex gap-1 rounded-2xl bg-slate-100/80 p-1 text-sm ring-1 ring-slate-200/60">
          <ModeButton active={mode === "student"} onClick={() => switchMode("student")}>
            Student
          </ModeButton>
          <ModeButton active={mode === "recruiter"} onClick={() => switchMode("recruiter")}>
            Recruiter
          </ModeButton>
          <ModeButton active={mode === "administration"} onClick={() => switchMode("administration")}>
            Administration
          </ModeButton>
        </div>

        <h1 className="mt-6 text-3xl font-extrabold tracking-tight text-slate-900">
          {mode === "administration" ? "Administration sign-in" : mode === "recruiter" ? "Recruiter sign-in" : "Sign in"}
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          {mode === "administration"
            ? "For the person in charge of the placement cell."
            : mode === "recruiter"
              ? "For company recruiters - use the account the placement cell created for you."
              : "Welcome back - pick up where you left off."}
        </p>

        {error && (
          <p role="alert" className="alert-error animate-pop mt-5">
            {error}
          </p>
        )}

        <label className="label mt-6">
          Email
          <span className="relative block">
            <span className="pointer-events-none absolute top-1/2 left-3.5 mt-0.75 -translate-y-1/2 text-slate-400">
              <Icon name="mail" />
            </span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
              autoComplete="email"
              placeholder={mode === "recruiter" ? "you@company.com" : "name@ei.nits.ac.in"}
              className="input pl-10"
            />
          </span>
        </label>

        <label className="label mt-4">
          Password
          <span className="relative block">
            <span className="pointer-events-none absolute top-1/2 left-3.5 mt-0.75 -translate-y-1/2 text-slate-400">
              <Icon name="lock" />
            </span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              autoComplete="current-password"
              className="input pl-10"
            />
          </span>
        </label>

        <button type="submit" disabled={submitting} className="btn btn-primary btn-lg mt-7 w-full">
          {submitting ? (
            <>
              <Spinner />
              Signing in...
            </>
          ) : (
            "Sign in"
          )}
        </button>

        {mode === "student" && (
          <p className="mt-6 text-center text-sm text-slate-600">
            No account?{" "}
            <Link to="/register" className="font-semibold text-violet-600 hover:underline">
              Register
            </Link>
          </p>
        )}
      </form>
    </AuthLayout>
  );
}

function ModeButton(props: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={props.onClick}
      className={[
        "flex-1 rounded-xl px-3 py-2 font-semibold transition",
        props.active
          ? "bg-white text-violet-700 shadow-md shadow-violet-500/10"
          : "text-slate-500 hover:text-slate-700",
      ].join(" ")}
    >
      {props.children}
    </button>
  );
}
