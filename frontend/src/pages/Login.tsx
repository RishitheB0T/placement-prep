import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";

import { login, logout } from "../api/auth";
import { ApiError } from "../api/client";

type Mode = "student" | "administration";

/**
 * Sign in, in one of two framings.
 *
 * Only the "administration" mode is actually enforced, and only against TNP_PIC - a
 * coordinator's sign-in experience is untouched, exactly like a student's. The "student"
 * mode is the original, unrestricted form: it accepts any account, PIC included, same as
 * before this toggle existed. There was no reason to lock coordinators out of anywhere
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

  // Where ProtectedRoute wanted to send them before it bounced them here.
  const destination = (location.state as { from?: string } | null)?.from ?? "/drives";

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
      if (mode === "administration" && response.role !== "TNP_PIC") {
        logout();
        setError("This is not an administration account. Use the student sign-in instead.");
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
    <main className="flex min-h-screen items-center justify-center bg-slate-50 p-6">
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-sm rounded-xl border border-slate-200 bg-white p-8 shadow-sm"
      >
        <div className="flex gap-1 rounded-lg bg-slate-100 p-1 text-sm">
          <ModeButton active={mode === "student"} onClick={() => switchMode("student")}>
            Student
          </ModeButton>
          <ModeButton
            active={mode === "administration"}
            onClick={() => switchMode("administration")}
          >
            Administration
          </ModeButton>
        </div>

        <h1 className="mt-4 text-2xl font-semibold text-slate-900">
          {mode === "administration" ? "Administration sign-in" : "Sign in"}
        </h1>
        <p className="mt-1 text-sm text-slate-500">Placement Prep</p>

        {error && (
          <p
            role="alert"
            className="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
          >
            {error}
          </p>
        )}

        <label className="mt-6 block text-sm font-medium text-slate-700">
          Email
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
            autoComplete="email"
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900 outline-none focus:border-slate-900"
          />
        </label>

        <label className="mt-4 block text-sm font-medium text-slate-700">
          Password
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            autoComplete="current-password"
            className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900 outline-none focus:border-slate-900"
          />
        </label>

        <button
          type="submit"
          disabled={submitting}
          className="mt-6 w-full rounded-lg bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700 disabled:opacity-50"
        >
          {submitting ? "Signing in..." : "Sign in"}
        </button>

        {mode === "student" && (
          <p className="mt-4 text-center text-sm text-slate-600">
            No account?{" "}
            <Link to="/register" className="font-medium text-slate-900 underline">
              Register
            </Link>
          </p>
        )}
      </form>
    </main>
  );
}

function ModeButton(props: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      onClick={props.onClick}
      className={[
        "flex-1 rounded-md px-3 py-1.5 font-medium transition-colors",
        props.active ? "bg-white text-slate-900 shadow-sm" : "text-slate-500 hover:text-slate-700",
      ].join(" ")}
    >
      {props.children}
    </button>
  );
}
