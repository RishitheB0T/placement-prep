import { useEffect, useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";

import { fetchMyProfile, updateMyProfile } from "../api/auth";
import { ApiError } from "../api/client";
import type { UserProfile } from "../api/types";

/** Empty strings rather than numbers, because an <input> always holds text. */
interface FormState {
  cgpa: string;
  branch: string;
  tenthPercentage: string;
  twelfthPercentage: string;
  backlogs: string;
}

const EMPTY: FormState = {
  cgpa: "",
  branch: "",
  tenthPercentage: "",
  twelfthPercentage: "",
  backlogs: "0",
};

export default function Profile() {
  const navigate = useNavigate();

  const [form, setForm] = useState<FormState>(EMPTY);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);

  // Load the existing profile so the form starts filled in rather than blank.
  useEffect(() => {
    let cancelled = false;

    fetchMyProfile()
      .then((me) => {
        if (cancelled) return;
        setProfile(me);
        setForm({
          cgpa: me.cgpa?.toString() ?? "",
          branch: me.branch ?? "",
          tenthPercentage: me.tenthPercentage?.toString() ?? "",
          twelfthPercentage: me.twelfthPercentage?.toString() ?? "",
          backlogs: me.backlogs.toString(),
        });
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError("Could not load your profile");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [navigate]);

  function update(field: keyof FormState, value: string) {
    setForm((current) => ({ ...current, [field]: value }));
    setSaved(false);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    setError(null);

    try {
      // The inputs hold strings; the API expects numbers. Converting here rather than in
      // the api layer keeps that layer a faithful mirror of the backend contract.
      const updated = await updateMyProfile({
        cgpa: Number(form.cgpa),
        branch: form.branch,
        tenthPercentage: Number(form.tenthPercentage),
        twelfthPercentage: Number(form.twelfthPercentage),
        backlogs: Number(form.backlogs),
      });
      setProfile(updated);
      setSaved(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not save your profile");
    }
  }

  if (loading) {
    return <main className="p-8 text-slate-500">Loading...</main>;
  }

  return (
    <main className="mx-auto max-w-xl p-6">
      <h1 className="text-2xl font-semibold text-slate-900">Your profile</h1>
      <p className="mt-1 text-sm text-slate-500">{profile?.email}</p>

      {!profile?.complete && (
        <p className="mt-4 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
          Fill this in to see which drives you are eligible for.
        </p>
      )}

      {error && (
        <p
          role="alert"
          className="mt-4 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {error}
        </p>
      )}

      {saved && (
        <p className="mt-4 rounded-lg border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">
          Saved.
        </p>
      )}

      <form onSubmit={handleSubmit} className="mt-6 space-y-4">
        <Field
          label="CGPA"
          value={form.cgpa}
          onChange={(v) => update("cgpa", v)}
          type="number"
          step="0.01"
          min="0"
          max="10"
        />
        <Field
          label="Branch"
          value={form.branch}
          onChange={(v) => update("branch", v)}
          placeholder="CSE"
          hint="Stored in capitals; matching ignores case."
        />
        <Field
          label="10th percentage"
          value={form.tenthPercentage}
          onChange={(v) => update("tenthPercentage", v)}
          type="number"
          step="0.01"
          min="0"
          max="100"
        />
        <Field
          label="12th percentage"
          value={form.twelfthPercentage}
          onChange={(v) => update("twelfthPercentage", v)}
          type="number"
          step="0.01"
          min="0"
          max="100"
        />
        <Field
          label="Active backlogs"
          value={form.backlogs}
          onChange={(v) => update("backlogs", v)}
          type="number"
          min="0"
        />

        <div className="flex gap-3 pt-2">
          <button
            type="submit"
            className="rounded-lg bg-slate-900 px-4 py-2 font-medium text-white hover:bg-slate-700"
          >
            Save
          </button>
          <button
            type="button"
            onClick={() => navigate("/drives")}
            className="rounded-lg border border-slate-300 px-4 py-2 font-medium text-slate-700 hover:bg-slate-100"
          >
            Back to drives
          </button>
        </div>
      </form>
    </main>
  );
}

/** One labelled input. Extracted only to keep the form above readable. */
function Field(props: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: string;
  step?: string;
  min?: string;
  max?: string;
  placeholder?: string;
  hint?: string;
}) {
  return (
    <label className="block text-sm font-medium text-slate-700">
      {props.label}
      <input
        type={props.type ?? "text"}
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
        required
        step={props.step}
        min={props.min}
        max={props.max}
        placeholder={props.placeholder}
        className="mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-slate-900 outline-none focus:border-slate-900"
      />
      {props.hint && (
        <span className="mt-1 block text-xs font-normal text-slate-500">{props.hint}</span>
      )}
    </label>
  );
}
