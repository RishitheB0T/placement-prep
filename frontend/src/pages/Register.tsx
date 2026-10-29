import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router-dom";

import { register } from "../api/auth";
import { ApiError } from "../api/client";
import AuthLayout from "../components/AuthLayout";
import { Icon, Spinner } from "../components/ui";

export default function Register() {
  const navigate = useNavigate();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setSubmitting(true);

    try {
      await register(email, password);
      // Registering signs you in, so go straight to the profile form: a new account has
      // no academic details yet, and the eligibility page is useless without them.
      navigate("/profile", { replace: true });
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        setError("That email is already registered. Try signing in instead.");
      } else if (e instanceof ApiError) {
        setError(e.message);
      } else {
        setError("Could not reach the server");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout>
      <title>Create account · Placement Prep</title>
      <form onSubmit={handleSubmit} className="mx-auto w-full max-w-sm">
        <p className="chip bg-sky-100 text-sky-700">
          <span className="chip-dot" />
          Students only
        </p>
        <h1 className="mt-4 text-3xl font-extrabold tracking-tight text-slate-900">Create an account</h1>
        <p className="mt-1 text-sm text-slate-500">Takes a minute. You will add your marks right after.</p>

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
              placeholder="name@ei.nits.ac.in"
              className="input pl-10"
            />
          </span>
          <span className="hint">Use your college address - it ends in .nits.ac.in</span>
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
              // Mirrors the backend rule so the browser catches it before a round trip.
              // The server still enforces it: client-side validation is a convenience,
              // never a control, since anyone can call the API directly.
              minLength={8}
              maxLength={72}
              autoComplete="new-password"
              className="input pl-10"
            />
          </span>
          <PasswordStrength password={password} />
        </label>

        <button type="submit" disabled={submitting} className="btn btn-primary btn-lg mt-7 w-full">
          {submitting ? (
            <>
              <Spinner />
              Creating...
            </>
          ) : (
            "Create account"
          )}
        </button>

        <p className="mt-6 text-center text-sm text-slate-600">
          Already registered?{" "}
          <Link to="/login" className="font-semibold text-violet-600 hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </AuthLayout>
  );
}

/**
 * A four-segment bar under the password field. Only the length rule is enforced by the
 * server; the rest is a nudge towards a password that is harder to guess.
 */
function PasswordStrength({ password }: { password: string }) {
  const checks = [
    password.length >= 8,
    /[A-Z]/.test(password) && /[a-z]/.test(password),
    /\d/.test(password),
    /[^A-Za-z0-9]/.test(password) || password.length >= 14,
  ];
  const score = checks.filter(Boolean).length;
  const colours = ["bg-rose-400", "bg-amber-400", "bg-sky-400", "bg-emerald-500"];

  return (
    <span className="mt-2 block">
      <span aria-hidden="true" className="flex gap-1.5">
        {checks.map((_, index) => (
          <span
            key={index}
            className={`h-1.5 flex-1 rounded-full transition-colors duration-300 ${
              index < score ? colours[score - 1] : "bg-slate-200"
            }`}
          />
        ))}
      </span>
      <span className="hint">
        {password.length < 8
          ? "At least 8 characters"
          : ["Weak", "Fair", "Good", "Strong"][score - 1] + " - mixing cases, numbers and symbols helps"}
      </span>
    </span>
  );
}
