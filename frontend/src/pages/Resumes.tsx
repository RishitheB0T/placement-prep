import { useEffect, useMemo, useRef, useState, type DragEvent, type FormEvent } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import { fetchMyProfile } from "../api/auth";
import { ApiError } from "../api/client";
import { fetchAllDrives } from "../api/drives";
import {
  MAX_RESUME_BYTES,
  fetchMyResumes,
  fetchResumeFile,
  retryResumeReview,
  uploadResume,
} from "../api/resumes";
import type { Drive, Resume, ResumeStatus, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { CompanyAvatar, EmptyState, Icon, PageHero, SkeletonCards, Spinner, type IconName } from "../components/ui";

/** How often the page asks for fresh statuses while a review is still running. */
const POLL_INTERVAL_MS = 4000;

/**
 * Upload a resume for a drive, and read what the AI review said about it.
 *
 * The upload only stores the file - the review runs afterwards on the server, in a
 * background job. So while any review here is PENDING or PROCESSING, this page asks for
 * the list again every few seconds and swaps in whatever has changed, and stops asking the
 * moment nothing is left in flight.
 *
 * Students and coordinators only. The person in charge is faculty with no resume to
 * review, so they are sent back to the board; the backend refuses them with a 403 anyway.
 */
export default function Resumes() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [drives, setDrives] = useState<Drive[]>([]);
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // The upload form. A drive can arrive preselected from a drive card's
  // "Get resume feedback" button, as ?driveId=12.
  const [driveId, setDriveId] = useState(searchParams.get("driveId") ?? "");
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const me = await fetchMyProfile();
        if (cancelled) return;
        if (me.role === "TNP_PIC") {
          navigate("/drives", { replace: true });
          return;
        }
        setProfile(me);

        const [allDrives, mine] = await Promise.all([fetchAllDrives(), fetchMyResumes()]);
        if (cancelled) return;
        setDrives(allDrives);
        setResumes(mine);
      } catch (e) {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not reach the server");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  const reviewing = resumes.some((r) => r.status === "PENDING" || r.status === "PROCESSING");

  // Poll only while there is something to wait for. Depending on `reviewing` rather than
  // on `resumes` means the timer is not torn down and rebuilt on every refresh.
  useEffect(() => {
    if (!reviewing) return;

    const timer = window.setInterval(() => {
      fetchMyResumes()
        .then(setResumes)
        .catch((e) => {
          if (e instanceof ApiError && e.status === 401) {
            navigate("/login", { replace: true });
          }
          // Anything else is most likely a blip. The list on screen stays as it was and
          // the next tick simply tries again.
        });
    }, POLL_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, [reviewing, navigate]);

  // Open drives first, soonest deadline at the top; closed ones after, still choosable,
  // since practising against a past opening is useful too.
  const driveOptions = useMemo(
    () =>
      [...drives].sort((a, b) => {
        const aClosed = isClosed(a);
        const bClosed = isClosed(b);
        if (aClosed !== bClosed) return aClosed ? 1 : -1;
        return new Date(a.applicationDeadline).getTime() - new Date(b.applicationDeadline).getTime();
      }),
    [drives],
  );

  const selectedDrive = drives.find((d) => String(d.id) === driveId);
  const driveFor = (id: number) => drives.find((d) => d.id === id);

  /** Checks a picked or dropped file here first, so a wrong one never costs an upload. */
  function chooseFile(candidate: File | undefined) {
    setMessage(null);
    if (!candidate) return;
    const problem = problemWith(candidate);
    setFileError(problem);
    setFile(problem ? null : candidate);
  }

  function clearFile() {
    setFile(null);
    setFileError(null);
    // Clearing the input too, otherwise picking the same file again fires no change event.
    if (fileInput.current) fileInput.current.value = "";
  }

  function handleDrop(event: DragEvent<HTMLLabelElement>) {
    // Without preventDefault the browser opens the dropped PDF in place of this page.
    event.preventDefault();
    setDragging(false);
    chooseFile(event.dataTransfer.files[0]);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!file || driveId === "") return;

    setUploading(true);
    setError(null);
    setMessage(null);

    try {
      const created = await uploadResume(Number(driveId), file);
      setResumes((current) => [created, ...current]);
      clearFile();
      setMessage("Uploaded! Your review has started and usually takes under a minute.");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not upload your resume");
    } finally {
      setUploading(false);
    }
  }

  async function handleRetry(resume: Resume) {
    setError(null);
    try {
      const updated = await retryResumeReview(resume.id);
      setResumes((current) => current.map((r) => (r.id === updated.id ? updated : r)));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not retry the review");
    }
  }

  async function openPdf(resume: Resume) {
    // The tab is opened here, synchronously inside the click, because pop-up blockers
    // only allow window.open as a direct response to the user. It is filled in once the
    // file has downloaded.
    const tab = window.open("", "_blank");
    try {
      const blob = await fetchResumeFile(resume.id);
      const url = URL.createObjectURL(blob);
      // replace() rather than assigning href, so the blank page the tab opened on does
      // not sit in its back history.
      if (tab) {
        tab.location.replace(url);
      } else {
        window.location.assign(url);
      }
      // Long enough for the new tab to have loaded it, then the memory is released.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch (e) {
      tab?.close();
      setError(e instanceof ApiError ? e.message : "Could not open the PDF");
    }
  }

  return (
    <>
      <AppHeader profile={profile} active="resumes" />

      <main className="mx-auto max-w-6xl px-4 pt-8 pb-16">
        <PageHero
          eyebrow="AI-powered"
          title="Resume feedback"
          subtitle="Upload your resume for a drive and get a match score, what is already working, and exactly what to change - judged against that drive's own job description."
        />

        {error && (
          <p role="alert" className="alert-error animate-pop mt-6">
            {error}
          </p>
        )}

        <div className="mt-8 grid items-start gap-6 lg:grid-cols-5">
          {/* ---------------------------------------------------- Upload panel */}
          <section className="space-y-6 lg:sticky lg:top-24 lg:col-span-2">
            <form onSubmit={(e) => void handleSubmit(e)} className="card animate-fade-up p-6">
              <div className="flex items-center gap-3">
                <span className="grid size-11 place-items-center rounded-2xl bg-linear-to-br from-violet-500 to-fuchsia-500 text-white shadow-lg shadow-fuchsia-500/25">
                  <Icon name="upload" className="size-5" />
                </span>
                <div>
                  <h2 className="text-lg font-bold text-slate-900">Upload a resume</h2>
                  <p className="text-xs text-slate-500">PDF only, up to 5 MB</p>
                </div>
              </div>

              <label className="label mt-6">
                <span className="flex items-center gap-2">
                  <StepNumber value={1} />
                  Which drive is it for?
                </span>
                <select
                  value={driveId}
                  onChange={(e) => {
                    setDriveId(e.target.value);
                    setMessage(null);
                  }}
                  required
                  className="input"
                >
                  <option value="">Choose a drive...</option>
                  {driveOptions.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.companyName} - {d.role}
                      {isClosed(d) ? " (closed)" : ""}
                    </option>
                  ))}
                </select>
              </label>
              {selectedDrive && (
                <p className="hint">
                  {selectedDrive.description
                    ? `Scored against the job description ${selectedDrive.companyName} posted.`
                    : `${selectedDrive.companyName} posted no description, so the review uses what this role usually expects.`}
                </p>
              )}

              <p className="label mt-5 flex items-center gap-2">
                <StepNumber value={2} />
                Your resume
              </p>

              {/* Always rendered, visually hidden: the dropzone and the Change button both
                  open it, and it keeps the picker reachable from the keyboard. */}
              <input
                id="resume-file"
                ref={fileInput}
                type="file"
                accept="application/pdf,.pdf"
                className="peer sr-only"
                onChange={(e) => chooseFile(e.target.files?.[0])}
              />

              {file ? (
                <div className="animate-pop mt-2 flex items-center gap-3 rounded-2xl border border-emerald-200 bg-emerald-50/80 p-3">
                  <span className="grid size-10 place-items-center rounded-xl bg-linear-to-br from-emerald-500 to-teal-500 text-white">
                    <Icon name="document" className="size-5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-semibold text-slate-800">{file.name}</p>
                    <p className="text-xs text-emerald-700">{formatSize(file.size)} · ready to upload</p>
                  </div>
                  <button type="button" onClick={() => fileInput.current?.click()} className="btn btn-sm btn-secondary">
                    Change
                  </button>
                  <button
                    type="button"
                    onClick={clearFile}
                    className="btn btn-sm btn-ghost px-2"
                    title="Remove this file"
                  >
                    <Icon name="x" />
                    <span className="sr-only">Remove file</span>
                  </button>
                </div>
              ) : (
                <label
                  htmlFor="resume-file"
                  onDragOver={(e) => {
                    e.preventDefault();
                    setDragging(true);
                  }}
                  onDragLeave={() => setDragging(false)}
                  onDrop={handleDrop}
                  className={[
                    "mt-2 flex cursor-pointer flex-col items-center rounded-2xl border-2 border-dashed px-6 py-8 text-center transition peer-focus-visible:ring-4 peer-focus-visible:ring-violet-200",
                    dragging
                      ? "scale-[1.02] border-fuchsia-400 bg-fuchsia-50"
                      : "border-violet-200 bg-linear-to-br from-indigo-50/80 via-white to-fuchsia-50/80 hover:border-violet-400",
                  ].join(" ")}
                >
                  <span className="grid size-12 place-items-center rounded-2xl bg-white text-violet-600 shadow-md">
                    <Icon name="upload" className="size-6" />
                  </span>
                  <span className="mt-3 text-sm font-semibold text-slate-800">
                    {dragging ? "Drop it here" : "Drag your PDF here"}
                  </span>
                  <span className="mt-1 text-xs text-slate-500">
                    or <span className="font-semibold text-violet-600 underline-offset-2 hover:underline">browse your files</span>
                  </span>
                </label>
              )}

              {fileError && (
                <p role="alert" className="alert-error animate-pop mt-3">
                  {fileError}
                </p>
              )}

              {message && <p className="alert-success animate-pop mt-4">{message}</p>}

              <button
                type="submit"
                disabled={!file || driveId === "" || uploading}
                className="btn btn-primary btn-lg mt-6 w-full"
              >
                {uploading ? (
                  <>
                    <Spinner />
                    Uploading...
                  </>
                ) : (
                  <>
                    <Icon name="sparkles" className="size-5" />
                    Get feedback
                  </>
                )}
              </button>
            </form>

            {/* On a phone this card would sit between the form and the reviews, so once
                there is a review to read it steps aside; side by side, it always shows. */}
            <div className={resumes.length > 0 ? "hidden lg:block" : ""}>
              <HowItWorks />
            </div>
          </section>

          {/* --------------------------------------------------- Reviews list */}
          <section className="lg:col-span-3">
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-xl font-bold text-slate-900">Your reviews</h2>
              {reviewing && (
                <span className="chip animate-pulse bg-violet-100 text-violet-700">
                  <Spinner className="size-3" />
                  Updating live
                </span>
              )}
            </div>

            <div className="mt-4">
              {loading ? (
                <SkeletonCards count={2} />
              ) : resumes.length === 0 ? (
                <EmptyState icon="document" title="No reviews yet">
                  Pick a drive, drop in your resume, and your first review will appear here in
                  about a minute.
                </EmptyState>
              ) : (
                <ul className="space-y-5">
                  {resumes.map((resume, index) => (
                    <ResumeCard
                      key={resume.id}
                      resume={resume}
                      drive={driveFor(resume.driveId)}
                      index={index}
                      onRetry={() => void handleRetry(resume)}
                      onOpen={() => void openPdf(resume)}
                    />
                  ))}
                </ul>
              )}
            </div>
          </section>
        </div>
      </main>
    </>
  );
}

/** One uploaded resume: who it was for, where its review is, and the review itself. */
function ResumeCard({
  resume,
  drive,
  index,
  onRetry,
  onOpen,
}: {
  resume: Resume;
  drive: Drive | undefined;
  index: number;
  onRetry: () => void;
  onOpen: () => void;
}) {
  const company = drive?.companyName ?? `Drive #${resume.driveId}`;
  const inFlight = resume.status === "PENDING" || resume.status === "PROCESSING";

  return (
    <li className="card animate-fade-up overflow-hidden" style={{ animationDelay: `${index * 70}ms` }}>
      {inFlight && (
        <div className="h-1 w-full overflow-hidden bg-violet-100">
          <div className="animate-progress h-full w-1/3 rounded-full bg-linear-to-r from-indigo-500 via-violet-500 to-fuchsia-500" />
        </div>
      )}

      <div className="p-5 sm:p-6">
        <div className="flex items-start gap-4">
          <CompanyAvatar name={company} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-base font-bold text-slate-900">{company}</h3>
              <ReviewStatusChip status={resume.status} />
            </div>
            <p className="text-sm text-slate-600">{drive?.role ?? "This drive is no longer listed"}</p>
            <p className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
              <span className="inline-flex min-w-0 items-center gap-1">
                <Icon name="document" className="size-3.5" />
                <span className="truncate">{resume.originalFilename}</span> · {formatSize(resume.sizeBytes)}
              </span>
              <span className="inline-flex items-center gap-1">
                <Icon name="clock" className="size-3.5" />
                {formatWhen(resume.uploadedAt)}
              </span>
            </p>
          </div>
        </div>

        {inFlight && (
          <div className="mt-5 flex items-center gap-3 rounded-2xl bg-linear-to-r from-indigo-50 to-fuchsia-50 p-4">
            <span className="grid size-10 place-items-center rounded-xl bg-white text-violet-600 shadow-sm">
              <Spinner className="size-5" />
            </span>
            <div>
              <p className="text-sm font-semibold text-slate-800">
                {resume.status === "PENDING" ? "Waiting in the queue..." : "Reading your resume..."}
              </p>
              <p className="text-xs text-slate-500">This updates by itself - feel free to keep browsing.</p>
            </div>
          </div>
        )}

        {resume.status === "FAILED" && (
          <div className="mt-5 flex flex-col gap-3 rounded-2xl border border-amber-200 bg-amber-50/80 p-4 sm:flex-row sm:items-center">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-linear-to-br from-amber-400 to-orange-500 text-white">
              <Icon name="warning" className="size-5" />
            </span>
            <p className="flex-1 text-sm text-amber-900">
              {resume.failureReason ?? "The review could not be completed."}
            </p>
            <button type="button" onClick={onRetry} className="btn btn-sm btn-warning">
              <Icon name="refresh" />
              Try again
            </button>
          </div>
        )}

        {resume.status === "COMPLETED" && resume.score !== null && (
          <>
            <div className="mt-5 flex flex-col gap-5 sm:flex-row sm:items-center">
              <ScoreRing score={resume.score} />
              <div>
                <p className={`text-sm font-bold ${verdictFor(resume.score).text}`}>{verdictFor(resume.score).label}</p>
                <p className="mt-1 text-sm leading-relaxed text-slate-700">{resume.summary}</p>
              </div>
            </div>

            <div className="mt-5 grid gap-4 md:grid-cols-2">
              <FeedbackList title="Already strong" tone="good" icon="check" items={resume.strengths} />
              <FeedbackList title="What to improve" tone="improve" icon="bulb" items={resume.improvements} />
            </div>
          </>
        )}

        <div className="mt-5 flex justify-end border-t border-slate-100 pt-4">
          <button type="button" onClick={onOpen} className="btn btn-sm btn-ghost">
            <Icon name="eye" />
            View PDF
          </button>
        </div>
      </div>
    </li>
  );
}

const STATUS_CHIPS: Record<ResumeStatus, { label: string; tone: string }> = {
  PENDING: { label: "In the queue", tone: "bg-sky-100 text-sky-700" },
  PROCESSING: { label: "Reviewing", tone: "bg-violet-100 text-violet-700" },
  COMPLETED: { label: "Reviewed", tone: "bg-emerald-100 text-emerald-700" },
  FAILED: { label: "Needs a retry", tone: "bg-amber-100 text-amber-800" },
};

function ReviewStatusChip({ status }: { status: ResumeStatus }) {
  const chip = STATUS_CHIPS[status];
  return (
    <span className={`chip ${chip.tone}`}>
      <span className="chip-dot" />
      {chip.label}
    </span>
  );
}

/** The score as a coloured ring: the filled share of the circle is the score out of 100. */
function ScoreRing({ score }: { score: number }) {
  const verdict = verdictFor(score);
  return (
    <div
      className="animate-pop grid size-24 shrink-0 place-items-center rounded-full shadow-inner"
      style={{ background: `conic-gradient(${verdict.color} ${score * 3.6}deg, rgb(226 232 240) 0deg)` }}
    >
      <div className="grid size-19 place-items-center rounded-full bg-white text-center shadow-sm">
        <div>
          <p className="text-2xl leading-none font-extrabold text-slate-900">{score}</p>
          <p className="mt-0.5 text-[10px] font-medium text-slate-400">out of 100</p>
        </div>
      </div>
    </div>
  );
}

function verdictFor(score: number) {
  if (score >= 75) return { label: "Strong match", color: "rgb(16 185 129)", text: "text-emerald-600" };
  if (score >= 50) return { label: "Good start", color: "rgb(245 158 11)", text: "text-amber-600" };
  return { label: "Needs work", color: "rgb(244 63 94)", text: "text-rose-600" };
}

const LIST_TONES = {
  good: { box: "border-emerald-100 bg-emerald-50/70", title: "text-emerald-800", bullet: "bg-emerald-500" },
  improve: { box: "border-amber-100 bg-amber-50/70", title: "text-amber-800", bullet: "bg-amber-500" },
};

function FeedbackList({
  title,
  tone,
  icon,
  items,
}: {
  title: string;
  tone: keyof typeof LIST_TONES;
  icon: IconName;
  items: string[];
}) {
  const colours = LIST_TONES[tone];
  return (
    <div className={`rounded-2xl border p-4 ${colours.box}`}>
      <p className={`text-sm font-bold ${colours.title}`}>{title}</p>
      {items.length === 0 ? (
        <p className="mt-2 text-sm text-slate-500">Nothing listed.</p>
      ) : (
        <ul className="mt-3 space-y-2.5">
          {items.map((item) => (
            <li key={item} className="flex gap-2.5 text-sm text-slate-700">
              <span className={`mt-0.5 grid size-5 shrink-0 place-items-center rounded-full text-white ${colours.bullet}`}>
                <Icon name={icon} className="size-3" />
              </span>
              <span>{item}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function HowItWorks() {
  const steps: { title: string; text: string; tone: string }[] = [
    {
      title: "Pick the drive",
      text: "Your resume is scored against that drive's job description, so the advice fits the role.",
      tone: "from-indigo-500 to-sky-500",
    },
    {
      title: "Upload a PDF",
      text: "Stored privately on the server. Only you can open it again.",
      tone: "from-violet-500 to-fuchsia-500",
    },
    {
      title: "Read your review",
      text: "A match score, what is already strong, and exactly what to change.",
      tone: "from-amber-500 to-rose-500",
    },
  ];

  return (
    <div className="card animate-fade-up p-6 [animation-delay:120ms]">
      <p className="text-sm font-bold text-slate-900">How it works</p>
      <ol className="mt-4 space-y-4">
        {steps.map((step, index) => (
          <li key={step.title} className="flex gap-3">
            <span
              className={`grid size-8 shrink-0 place-items-center rounded-xl bg-linear-to-br text-sm font-bold text-white shadow-md ${step.tone}`}
            >
              {index + 1}
            </span>
            <div>
              <p className="text-sm font-semibold text-slate-800">{step.title}</p>
              <p className="text-xs leading-relaxed text-slate-500">{step.text}</p>
            </div>
          </li>
        ))}
      </ol>
    </div>
  );
}

function StepNumber({ value }: { value: number }) {
  return (
    <span className="grid size-5 place-items-center rounded-full bg-linear-to-br from-indigo-500 to-fuchsia-500 text-[11px] font-bold text-white">
      {value}
    </span>
  );
}

/** What is wrong with a picked file, or null when it is fine to upload. */
function problemWith(file: File): string | null {
  const looksLikePdf = file.type === "application/pdf" || file.name.toLowerCase().endsWith(".pdf");
  if (!looksLikePdf) {
    return "That file is not a PDF. Export or save your resume as a PDF and try again.";
  }
  if (file.size === 0) {
    return "That file is empty.";
  }
  if (file.size > MAX_RESUME_BYTES) {
    return `That file is ${formatSize(file.size)}. Resumes must be 5 MB or smaller.`;
  }
  return null;
}

function isClosed(drive: Drive): boolean {
  return new Date(drive.applicationDeadline).getTime() < Date.now();
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString("en-IN", {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });
}
