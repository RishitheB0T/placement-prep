import { useEffect, useState, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { fetchMyProfile } from "../api/auth";
import { ApiError } from "../api/client";
import { createDrive, fetchDriveById, updateDrive } from "../api/drives";
import type { UserProfile } from "../api/types";

/**
 * The form the placement cell posts a drive with, and edits one with.
 *
 * One component for both because the backend takes the same body either way -
 * DriveRequest serves POST and PUT alike - so a second form would be the same fields
 * twice, free to drift apart. The route decides which: /post-drive creates,
 * /drives/{id}/edit loads that drive and replaces it.
 *
 * Reachable only by TNP_COORDINATOR and TNP_PIC. That is enforced twice, on purpose:
 * SecurityConfig answers a student's POST with a 403 no matter what, which is the real
 * gate - but redirecting a student who lands here straight back to the board is a better
 * experience than letting them fill in a form only to have it rejected at the end.
 */
export default function PostDrive() {
  const navigate = useNavigate();
  const { id } = useParams<{ id: string }>();
  const editingId = id === undefined ? null : Number(id);
  const isEditing = editingId !== null;

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [checkingAccess, setCheckingAccess] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  const [form, setForm] = useState({
    companyName: "",
    role: "",
    ctc: "",
    tier: "1",
    cgpaCutoff: "",
    tenthCutoff: "",
    twelfthCutoff: "",
    backlogsAllowed: false,
    maxBacklogs: "",
    eligibleBranches: "",
    applicationDeadline: "",
    description: "",
  });

  useEffect(() => {
    fetchMyProfile()
      .then(async (me) => {
        if (me.role === "STUDENT") {
          navigate("/drives", { replace: true });
          return;
        }
        setProfile(me);

        if (editingId !== null) {
          const drive = await fetchDriveById(editingId);
          setForm({
            companyName: drive.companyName,
            role: drive.role,
            ctc: drive.ctc.toString(),
            tier: drive.tier.toString(),
            cgpaCutoff: drive.cgpaCutoff?.toString() ?? "",
            tenthCutoff: drive.tenthCutoff?.toString() ?? "",
            twelfthCutoff: drive.twelfthCutoff?.toString() ?? "",
            backlogsAllowed: drive.backlogsAllowed,
            maxBacklogs: drive.maxBacklogs?.toString() ?? "",
            eligibleBranches: drive.eligibleBranches.join(", "),
            // datetime-local wants "YYYY-MM-DDTHH:mm" in local time, not the ISO instant
            // the API returns, so trim the parts it cannot parse.
            applicationDeadline: toLocalInput(drive.applicationDeadline),
            description: drive.description ?? "",
          });
        }

        setCheckingAccess(false);
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not load the drive");
        setCheckingAccess(false);
      });
  }, [navigate, editingId]);

  function set<K extends keyof typeof form>(key: K, value: (typeof form)[K]) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  /** Empty means "no requirement", which the backend models as null - not zero. */
  const optionalNumber = (value: string) => (value.trim() === "" ? null : Number(value));

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setSaving(true);
    setError(null);
    setMessage(null);

    const body = {
      companyName: form.companyName.trim(),
      role: form.role.trim(),
      ctc: Number(form.ctc),
      tier: Number(form.tier),
      cgpaCutoff: Number(form.cgpaCutoff),
      tenthCutoff: optionalNumber(form.tenthCutoff),
      twelfthCutoff: optionalNumber(form.twelfthCutoff),
      backlogsAllowed: form.backlogsAllowed,
      maxBacklogs: form.backlogsAllowed ? optionalNumber(form.maxBacklogs) : null,
      eligibleBranches: form.eligibleBranches
        .split(",")
        .map((b) => b.trim().toUpperCase())
        .filter((b) => b !== ""),
      // The backend wants an ISO instant; a datetime-local input gives no timezone, so
      // new Date(...) interprets it in the browser's own zone before this converts it.
      applicationDeadline: new Date(form.applicationDeadline).toISOString(),
      description: form.description.trim() === "" ? null : form.description.trim(),
    };

    try {
      if (isEditing) {
        await updateDrive(editingId, body);
        // Straight back to the board: an edit is a correction, and seeing it applied in
        // the list is the confirmation that matters more than a message on this form.
        navigate("/drives", { replace: true });
        return;
      }

      const created = await createDrive(body);
      setMessage(`Posted ${created.companyName} - ${created.role}.`);
      setForm((current) => ({ ...current, companyName: "", role: "", ctc: "", description: "" }));
    } catch (e) {
      // A 400 here carries a field-level errors map (ValidationErrorHandler on the
      // backend); e.message is the top-level detail, which is still useful even without
      // walking that map field by field.
      setError(e instanceof ApiError ? e.message : `Could not ${isEditing ? "save" : "post"} the drive`);
    } finally {
      setSaving(false);
    }
  }

  if (checkingAccess) {
    return (
      <main className="mx-auto max-w-3xl p-6">
        <p className="text-slate-500">Loading...</p>
      </main>
    );
  }

  return (
    <main className="mx-auto max-w-3xl p-6">
      <header className="flex items-baseline justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">
            {isEditing ? "Edit drive" : "Post a drive"}
          </h1>
          {profile && (
            <p className="mt-1 text-sm text-slate-500">
              {profile.email} · {profile.role.replace("TNP_", "")}
            </p>
          )}
        </div>
        <button
          onClick={() => navigate("/drives")}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
        >
          Back to drives
        </button>
      </header>

      <form onSubmit={(e) => void submit(e)} className="mt-6 space-y-4">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Company">
            <input
              required
              value={form.companyName}
              onChange={(e) => set("companyName", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="Role">
            <input
              required
              value={form.role}
              onChange={(e) => set("role", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="CTC (rupees per annum)">
            <input
              required
              type="number"
              min="1"
              value={form.ctc}
              onChange={(e) => set("ctc", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="Tier">
            <select
              value={form.tier}
              onChange={(e) => set("tier", e.target.value)}
              className={inputClass}
            >
              <option value="1">1</option>
              <option value="2">2</option>
              <option value="3">3</option>
            </select>
          </Field>
          <Field label="CGPA cutoff">
            <input
              required
              type="number"
              step="0.1"
              min="0"
              max="10"
              value={form.cgpaCutoff}
              onChange={(e) => set("cgpaCutoff", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="Application deadline">
            <input
              required
              type="datetime-local"
              value={form.applicationDeadline}
              onChange={(e) => set("applicationDeadline", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="10th cutoff %" hint="Leave empty for no requirement">
            <input
              type="number"
              step="0.1"
              min="0"
              max="100"
              value={form.tenthCutoff}
              onChange={(e) => set("tenthCutoff", e.target.value)}
              className={inputClass}
            />
          </Field>
          <Field label="12th cutoff %" hint="Leave empty for no requirement">
            <input
              type="number"
              step="0.1"
              min="0"
              max="100"
              value={form.twelfthCutoff}
              onChange={(e) => set("twelfthCutoff", e.target.value)}
              className={inputClass}
            />
          </Field>
        </div>

        <Field label="Eligible branches" hint="Comma separated, e.g. CSE, ECE, ME">
          <input
            required
            value={form.eligibleBranches}
            onChange={(e) => set("eligibleBranches", e.target.value)}
            className={inputClass}
          />
        </Field>

        <label className="flex items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            checked={form.backlogsAllowed}
            onChange={(e) => set("backlogsAllowed", e.target.checked)}
          />
          Students with active backlogs may apply
        </label>

        {form.backlogsAllowed && (
          <Field label="Maximum backlogs" hint="Leave empty for no ceiling">
            <input
              type="number"
              min="0"
              value={form.maxBacklogs}
              onChange={(e) => set("maxBacklogs", e.target.value)}
              className={inputClass}
            />
          </Field>
        )}

        <Field label="Description">
          <textarea
            rows={3}
            value={form.description}
            onChange={(e) => set("description", e.target.value)}
            className={inputClass}
          />
        </Field>

        {message && (
          <p className="rounded-lg border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-800">
            {message}
          </p>
        )}

        {error && (
          <p
            role="alert"
            className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
          >
            {error}
          </p>
        )}

        <button
          type="submit"
          disabled={saving}
          className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
        >
          {saving ? "Saving..." : isEditing ? "Save changes" : "Post drive"}
        </button>
      </form>
    </main>
  );
}

const inputClass = "mt-1 w-full rounded-lg border border-slate-300 px-3 py-2 text-sm";

/**
 * Turns the API's ISO instant into the "YYYY-MM-DDTHH:mm" a datetime-local input needs.
 *
 * Deliberately built from the local-time getters rather than by slicing the ISO string:
 * the ISO form is UTC, so slicing it would show a deadline in the wrong timezone and an
 * edit would silently shift it every time the form was saved.
 */
function toLocalInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block text-sm text-slate-700">
      {label}
      {children}
      {hint && <span className="mt-0.5 block text-xs text-slate-400">{hint}</span>}
    </label>
  );
}
