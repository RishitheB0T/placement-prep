import { useEffect, useState, type ReactNode } from "react";
import { useNavigate, useParams } from "react-router-dom";

import { fetchMyProfile } from "../api/auth";
import { ApiError } from "../api/client";
import { createDrive, fetchDriveById, updateDrive } from "../api/drives";
import { uploadJobDescription } from "../api/jobDescriptions";
import type { UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { Icon, PageHero, Spinner, type IconName } from "../components/ui";

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

  /** The branches as they will be saved, shown live under the input. */
  const branches = form.eligibleBranches
    .split(",")
    .map((b) => b.trim().toUpperCase())
    .filter((b) => b !== "");

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
      eligibleBranches: branches,
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

  return (
    <>
      <AppHeader profile={profile} active="post-drive" />

      <main className="mx-auto max-w-4xl px-4 pt-8 pb-16">
        {checkingAccess ? (
          <div className="flex items-center gap-3 py-16 text-slate-500">
            <Spinner className="size-5 text-violet-500" />
            Loading...
          </div>
        ) : (
          <>
            <PageHero
              eyebrow="Training and placement cell"
              title={isEditing ? "Edit drive" : "Post a drive"}
              subtitle={
                isEditing
                  ? "Changes show up on the board as soon as you save."
                  : "Students who meet the cutoffs see it straight away under Eligible for me."
              }
            >
              <button onClick={() => navigate("/drives")} className="btn btn-secondary">
                Back to drives
              </button>
            </PageHero>

            <form onSubmit={(e) => void submit(e)} className="mt-8 space-y-6">
              <Section icon="briefcase" tone="from-indigo-500 to-sky-500" title="Company and role">
                <div className="grid gap-5 sm:grid-cols-2">
                  <Field label="Company">
                    <input
                      required
                      value={form.companyName}
                      onChange={(e) => set("companyName", e.target.value)}
                      placeholder="Zoho"
                      className="input"
                    />
                  </Field>
                  <Field label="Role">
                    <input
                      required
                      value={form.role}
                      onChange={(e) => set("role", e.target.value)}
                      placeholder="Member Technical Staff"
                      className="input"
                    />
                  </Field>
                  <Field label="CTC (rupees per annum)">
                    <input
                      required
                      type="number"
                      min="1"
                      value={form.ctc}
                      onChange={(e) => set("ctc", e.target.value)}
                      placeholder="900000"
                      className="input"
                    />
                  </Field>
                  <Field label="Tier">
                    <select value={form.tier} onChange={(e) => set("tier", e.target.value)} className="input">
                      <option value="1">1</option>
                      <option value="2">2</option>
                      <option value="3">3</option>
                    </select>
                  </Field>
                </div>
              </Section>

              <Section icon="check" tone="from-emerald-500 to-teal-500" title="Eligibility">
                <div className="grid gap-5 sm:grid-cols-3">
                  <Field label="CGPA cutoff">
                    <input
                      required
                      type="number"
                      step="0.1"
                      min="0"
                      max="10"
                      value={form.cgpaCutoff}
                      onChange={(e) => set("cgpaCutoff", e.target.value)}
                      placeholder="7.5"
                      className="input"
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
                      className="input"
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
                      className="input"
                    />
                  </Field>
                </div>

                <div className="mt-5">
                  <Field label="Eligible branches" hint="Comma separated, e.g. CSE, ECE, ME">
                    <input
                      required
                      value={form.eligibleBranches}
                      onChange={(e) => set("eligibleBranches", e.target.value)}
                      className="input"
                    />
                  </Field>
                  {branches.length > 0 && (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {branches.map((branch) => (
                        <span key={branch} className="chip animate-pop bg-violet-100 text-violet-700">
                          {branch}
                        </span>
                      ))}
                    </div>
                  )}
                </div>

                <label className="mt-5 flex cursor-pointer items-center gap-3 rounded-xl border border-slate-200 bg-white/80 px-4 py-3 text-sm font-medium text-slate-700 transition hover:border-indigo-200">
                  <input
                    type="checkbox"
                    checked={form.backlogsAllowed}
                    onChange={(e) => set("backlogsAllowed", e.target.checked)}
                    className="size-4 accent-violet-600"
                  />
                  Students with active backlogs may apply
                </label>

                {form.backlogsAllowed && (
                  <div className="animate-fade-up mt-5 sm:w-1/2">
                    <Field label="Maximum backlogs" hint="Leave empty for no ceiling">
                      <input
                        type="number"
                        min="0"
                        value={form.maxBacklogs}
                        onChange={(e) => set("maxBacklogs", e.target.value)}
                        className="input"
                      />
                    </Field>
                  </div>
                )}
              </Section>

              <Section icon="calendar" tone="from-amber-500 to-rose-500" title="Deadline and details">
                <div className="grid gap-5">
                  <div className="sm:w-1/2">
                    <Field label="Application deadline">
                      <input
                        required
                        type="datetime-local"
                        value={form.applicationDeadline}
                        onChange={(e) => set("applicationDeadline", e.target.value)}
                        className="input"
                      />
                    </Field>
                  </div>
                  <Field
                    label="Description"
                    hint="Skills, rounds and responsibilities. Students' resumes are reviewed against this."
                  >
                    <textarea
                      rows={4}
                      value={form.description}
                      onChange={(e) => set("description", e.target.value)}
                      className="input"
                    />
                  </Field>
                </div>
              </Section>

              {message && (
                <p className="alert-success animate-pop flex flex-wrap items-center gap-3">
                  <Icon name="check" />
                  {message}
                  <button
                    type="button"
                    onClick={() => navigate("/drives")}
                    className="font-semibold text-emerald-700 underline underline-offset-2"
                  >
                    See it on the board
                  </button>
                </p>
              )}

              {error && (
                <p role="alert" className="alert-error animate-pop">
                  {error}
                </p>
              )}

              <button type="submit" disabled={saving} className="btn btn-primary btn-lg">
                {saving ? <Spinner /> : <Icon name={isEditing ? "check" : "plus"} />}
                {saving ? "Saving..." : isEditing ? "Save changes" : "Post drive"}
              </button>
            </form>

            {/* A PDF needs a drive to belong to, so it can only be attached once one exists. */}
            {isEditing ? (
              <JobDescriptionUpload driveId={editingId} />
            ) : (
              <p className="mt-6 text-sm text-slate-500">
                After posting, open the drive's Edit page to attach its job-description PDF for search.
              </p>
            )}
          </>
        )}
      </main>
    </>
  );
}

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

/**
 * Attaching the company's job-description PDF to an existing drive. The backend splits it
 * into passages and indexes them for search by meaning, which both the search panel and
 * the assistant on the Assistant page use. Uploading again replaces the earlier PDF.
 */
function JobDescriptionUpload({ driveId }: { driveId: number }) {
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function upload() {
    if (!file) return;
    setUploading(true);
    setMessage(null);
    setError(null);
    try {
      const result = await uploadJobDescription(driveId, file);
      setMessage(`Indexed as ${result.passages} searchable passage${result.passages === 1 ? "" : "s"}. Students can now find this drive by meaning.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not upload the job description");
    } finally {
      setUploading(false);
    }
  }

  return (
    <section className="card animate-fade-up mt-6 p-6 sm:p-8">
      <div className="mb-4 flex items-center gap-3">
        <span className="grid size-9 place-items-center rounded-xl bg-linear-to-br from-sky-500 to-indigo-500 text-white shadow-md">
          <Icon name="document" className="size-5" />
        </span>
        <div>
          <h2 className="text-lg font-bold text-slate-900">Job description PDF</h2>
          <p className="text-xs text-slate-500">Indexed for search by meaning. Uploading again replaces the old one.</p>
        </div>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <label className="sr-only" htmlFor="jd-file">
          Job description PDF
        </label>
        <input
          id="jd-file"
          type="file"
          accept="application/pdf,.pdf"
          onChange={(e) => {
            setFile(e.target.files?.[0] ?? null);
            setMessage(null);
            setError(null);
          }}
          className="block w-full text-sm text-slate-600 file:mr-3 file:rounded-xl file:border-0 file:bg-violet-100 file:px-4 file:py-2 file:text-sm file:font-semibold file:text-violet-700 hover:file:bg-violet-200"
        />
        <button type="button" onClick={() => void upload()} disabled={!file || uploading} className="btn btn-secondary">
          {uploading ? <Spinner /> : <Icon name="upload" />}
          {uploading ? "Indexing..." : "Upload and index"}
        </button>
      </div>

      {message && <p className="alert-success animate-pop mt-4">{message}</p>}
      {error && (
        <p role="alert" className="alert-error animate-pop mt-4">
          {error}
        </p>
      )}
    </section>
  );
}

/** One titled group of fields, so the long form reads as three short ones. */
function Section({
  icon,
  tone,
  title,
  children,
}: {
  icon: IconName;
  tone: string;
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="card animate-fade-up p-6 sm:p-8">
      <div className="mb-5 flex items-center gap-3">
        <span className={`grid size-9 place-items-center rounded-xl bg-linear-to-br text-white shadow-md ${tone}`}>
          <Icon name={icon} className="size-5" />
        </span>
        <h2 className="text-lg font-bold text-slate-900">{title}</h2>
      </div>
      {children}
    </section>
  );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="label">
      {label}
      {children}
      {hint && <span className="hint">{hint}</span>}
    </label>
  );
}
