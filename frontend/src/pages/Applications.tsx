import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";

import {
  fetchApplicationsForDrive,
  fetchMyApplications,
  updateApplicationStatus,
  withdrawApplication,
} from "../api/applications";
import { fetchMyProfile } from "../api/auth";
import { canManageDrives, isStudentRole } from "../api/roles";
import { ApiError } from "../api/client";
import { fetchAllDrives } from "../api/drives";
import type { Application, ApplicationStatus, Drive, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { CompanyAvatar, EmptyState, Icon, PageHero, SkeletonCards } from "../components/ui";

const STATUSES: ApplicationStatus[] = ["APPLIED", "SHORTLISTED", "REJECTED", "SELECTED", "WITHDRAWN"];

/** Which half of this page is on screen. A coordinator can reach both. */
type View = "mine" | "applicants";

/**
 * Two views of the same data, from opposite ends: your own applications, and the
 * applicants to a drive.
 *
 * Which of them an account can reach follows from what it is, and the two are
 * independent. A student has only the first. The person in charge, being faculty, has
 * only the second. A coordinator is a student who also runs the board, so they get both
 * and a tab to switch - defaulting to their own applications, since reviewing needs a
 * drive picked first anyway.
 *
 * The backend decides this too, not just the UI: GET /api/applications/drive/{id}
 * answers 403 for a plain student, so there is nothing this page could show them there
 * even if a bug let them click through to it.
 */
export default function Applications() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const requestedDrive = searchParams.get("driveId") === null ? null : Number(searchParams.get("driveId"));

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [applications, setApplications] = useState<Application[]>([]);
  const [drives, setDrives] = useState<Drive[]>([]);
  const [selectedDrive, setSelectedDrive] = useState<number | null>(null);
  const [view, setView] = useState<View>("mine");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const canManage = canManageDrives(profile?.role);
  const isStudent = isStudentRole(profile?.role);
  /** Only a coordinator is both, so only a coordinator needs the switch. */
  const showTabs = canManage && isStudent;

  // Loads the page once (and again if ?driveId changes). Every setState happens in the
  // promise callbacks, after the data arrives - never synchronously inside the effect -
  // and `cancelled` drops a response that arrives after the page has moved on.
  useEffect(() => {
    let cancelled = false;
    loadPage(requestedDrive)
      .then((page) => {
        if (cancelled) return;
        setError(null);
        setProfile(page.profile);
        setDrives(page.drives);
        setView(page.view);
        setSelectedDrive(page.selectedDrive);
        setApplications(page.applications);
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not reach the server");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [navigate, requestedDrive]);

  /** Switching views swaps which list `applications` holds, so it has to refetch. */
  async function switchView(next: View) {
    setView(next);
    setError(null);
    setSelectedDrive(null);
    setApplications([]);

    if (next === "mine") {
      try {
        setApplications(await fetchMyApplications());
      } catch (e) {
        setError(e instanceof ApiError ? e.message : "Could not load your applications");
      }
    }
  }

  async function showApplicants(driveId: number) {
    setSelectedDrive(driveId);
    setError(null);
    try {
      setApplications(await fetchApplicationsForDrive(driveId));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load applicants");
    }
  }

  async function changeStatus(id: number, status: ApplicationStatus) {
    try {
      const updated = await updateApplicationStatus(id, status);
      setApplications((current) => current.map((a) => (a.id === id ? updated : a)));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not update the status");
    }
  }

  /**
   * Downloads the applicants on screen as a CSV file, built in the browser from the list
   * already loaded - no extra request, and no library: CSV is plain text.
   */
  function downloadCsv() {
    const drive = selectedDrive === null ? undefined : driveOf(selectedDrive);
    const rows = [
      ["Student email", "Status", "Applied on", "Note"],
      ...applications.map((a) => [
        a.studentEmail ?? `Student #${a.studentId}`,
        a.status.toLowerCase(),
        new Date(a.appliedAt).toLocaleDateString("en-IN"),
        a.note ?? "",
      ]),
    ];
    const blob = new Blob([toCsv(rows)], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${(drive?.companyName ?? "drive").replace(/[^a-z0-9]+/gi, "-")}-applicants.csv`;
    link.click();
    URL.revokeObjectURL(url);
  }

  async function withdraw(id: number) {
    try {
      const updated = await withdrawApplication(id);
      setApplications((current) => current.map((a) => (a.id === id ? updated : a)));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not withdraw");
    }
  }

  // The API only ever returns a bare driveId - resolving it to a company name here, from
  // the drives list already fetched for this page, is cheaper than the backend embedding
  // drive details in every application row on every request.
  const driveOf = (id: number) => drives.find((d) => d.id === id);
  const driveName = (id: number) => driveOf(id)?.companyName ?? `Drive ${id}`;

  // How many applications sit in each status, for the coloured summary row. Each chip is
  // one string ("2 shortlisted") rather than a number and a word in separate elements.
  const counts = STATUSES.map((status) => ({
    status,
    count: applications.filter((a) => a.status === status).length,
  })).filter((entry) => entry.count > 0);

  return (
    <>
      <title>Applications · Placement Prep</title>
      <AppHeader profile={profile} active="applications" />

      <main className="mx-auto max-w-5xl px-4 pt-8 pb-16">
        <PageHero
          eyebrow={view === "mine" ? "Your journey" : "Review"}
          title={view === "mine" ? "My applications" : "Applicants"}
          subtitle={
            view === "mine"
              ? "Every drive you applied to, and where the placement cell has got to with it."
              : "Pick a drive to see who applied, then move each application along."
          }
        />

        {showTabs && (
          <div className="mt-8 inline-flex rounded-2xl bg-white/70 p-1 shadow-sm ring-1 ring-slate-200/70">
            <TabButton active={view === "mine"} onClick={() => void switchView("mine")}>
              My applications
            </TabButton>
            <TabButton active={view === "applicants"} onClick={() => void switchView("applicants")}>
              Applicants
            </TabButton>
          </div>
        )}

        {view === "applicants" && canManage && (
          <div className="card animate-fade-up mt-6 flex flex-col gap-3 p-5 sm:flex-row sm:items-center">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-linear-to-br from-sky-500 to-indigo-500 text-white">
              <Icon name="briefcase" className="size-5" />
            </span>
            <label className="label flex-1">
              Drive
              <select
                value={selectedDrive ?? ""}
                onChange={(e) => void showApplicants(Number(e.target.value))}
                className="input"
              >
                <option value="">Choose a drive...</option>
                {drives.map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.companyName} - {d.role}
                  </option>
                ))}
              </select>
            </label>
            {selectedDrive !== null && applications.length > 0 && (
              <button type="button" onClick={downloadCsv} className="btn btn-secondary sm:self-end">
                <Icon name="download" />
                Download CSV
              </button>
            )}
          </div>
        )}

        {!loading && counts.length > 0 && (
          <div className="animate-fade-up mt-6 flex flex-wrap gap-2">
            {counts.map(({ status, count }) => (
              <span key={status} className={`chip px-3 py-1.5 ${STATUS_TONE[status].chip}`}>
                {`${count} ${status.toLowerCase()}`}
              </span>
            ))}
          </div>
        )}

        {error && (
          <p role="alert" className="alert-error animate-pop mt-6">
            {error}
          </p>
        )}

        <div className="mt-6">
          {loading && <SkeletonCards count={2} />}

          {!loading && applications.length === 0 && (
            <EmptyState
              icon={view === "mine" ? "list" : "inbox"}
              title={view === "mine" ? "No applications yet" : "Nothing to review yet"}
              action={
                view === "mine" ? (
                  <button onClick={() => navigate("/drives")} className="btn btn-primary">
                    <Icon name="briefcase" />
                    Browse drives
                  </button>
                ) : undefined
              }
            >
              {view === "mine"
                ? "You have not applied to any drives yet."
                : selectedDrive === null
                  ? "Choose a drive to see who applied."
                  : // Distinguishing these two matters: otherwise a drive nobody applied to
                    // would look identical to not having chosen a drive at all.
                    "Nobody has applied to this drive yet."}
            </EmptyState>
          )}

          <ul className="space-y-4">
            {applications.map((application, index) => {
              const tone = STATUS_TONE[application.status];
              return (
                <li
                  key={application.id}
                  className="card card-hover animate-fade-up relative overflow-hidden p-5 pl-6"
                  style={{ animationDelay: `${Math.min(index, 8) * 60}ms` }}
                >
                  {/* A coloured edge on the left, matching the status. */}
                  <span aria-hidden="true" className={`absolute inset-y-0 left-0 w-1.5 bg-linear-to-b ${tone.edge}`} />

                  <div className="flex items-start gap-4">
                    {view === "mine" ? (
                      <CompanyAvatar name={driveName(application.driveId)} size="sm" />
                    ) : (
                      <span
                        aria-hidden="true"
                        className="grid size-9 shrink-0 place-items-center rounded-xl bg-linear-to-br from-sky-400 to-violet-500 text-sm font-bold text-white"
                      >
                        {(application.studentEmail ?? "S").charAt(0).toUpperCase()}
                      </span>
                    )}

                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <h2 className="truncate font-bold text-slate-900">
                          {view === "applicants"
                            ? // The backend labels every applicant row with an email. The id is the
                              // fallback for a row whose account has since gone missing, which the
                              // lookup reports by simply having no entry for it.
                              (application.studentEmail ?? `Student #${application.studentId}`)
                            : driveName(application.driveId)}
                        </h2>
                        <StatusBadge status={application.status} />
                      </div>

                      {view === "mine" && driveOf(application.driveId) && (
                        <p className="text-sm text-slate-600">{driveOf(application.driveId)?.role}</p>
                      )}

                      <p className="mt-1 inline-flex items-center gap-1 text-xs text-slate-500">
                        <Icon name="calendar" className="size-3.5" />
                        Applied {new Date(application.appliedAt).toLocaleDateString("en-IN")}
                      </p>

                      {application.note && (
                        <p className="mt-3 rounded-xl border-l-4 border-violet-300 bg-violet-50/70 px-3 py-2 text-sm text-slate-700 italic">
                          {application.note}
                        </p>
                      )}

                      {view === "applicants" && application.status === "WITHDRAWN" ? (
                        // The student pulled out; the backend refuses to decide it (409).
                        <p className="mt-4 text-sm text-slate-500">Withdrawn by the student.</p>
                      ) : view === "applicants" ? (
                        <div className="mt-4 flex flex-wrap gap-2">
                          {STATUSES.filter((s) => s !== "WITHDRAWN" && s !== application.status).map((status) => (
                            <button
                              key={status}
                              onClick={() => void changeStatus(application.id, status)}
                              className={`btn btn-sm ${STATUS_TONE[status].button}`}
                            >
                              Mark {status.toLowerCase()}
                            </button>
                          ))}
                        </div>
                      ) : (
                        // Only while still under review: a decided application cannot be
                        // withdrawn (the backend answers 409), or a rejection could be undone
                        // by withdrawing and applying again.
                        // Wrapped in a block so the button starts on its own line: on its own
                        // it is inline, and would sit beside the "Applied" date above it.
                        (application.status === "APPLIED" || application.status === "SHORTLISTED") && (
                          <div className="mt-4">
                            <button onClick={() => void withdraw(application.id)} className="btn btn-sm btn-secondary">
                              Withdraw
                            </button>
                          </div>
                        )
                      )}
                    </div>
                  </div>
                </li>
              );
            })}
          </ul>
        </div>
      </main>
    </>
  );
}

/** What the page opens on, fetched in one go. */
interface Page {
  profile: UserProfile;
  drives: Drive[];
  view: View;
  selectedDrive: number | null;
  applications: Application[];
}

/**
 * Fetches what the page opens on. Returns data and sets no state, so the effect calling
 * it can ignore a result that arrives too late.
 */
async function loadPage(requestedDrive: number | null): Promise<Page> {
  const [me, allDrives] = await Promise.all([fetchMyProfile(), fetchAllDrives()]);

  // Arriving from a dashboard's "Review applicants" button, as ?driveId=12: a
  // coordinator, recruiter or admin opens straight onto that drive's applicants.
  if (requestedDrive !== null && canManageDrives(me.role)) {
    return {
      profile: me,
      drives: allDrives,
      view: "applicants",
      selectedDrive: requestedDrive,
      applications: await fetchApplicationsForDrive(requestedDrive),
    };
  }

  // A recruiter or the PIC has no applications of their own, so they open straight into review.
  const student = isStudentRole(me.role);
  return {
    profile: me,
    drives: allDrives,
    view: student ? "mine" : "applicants",
    selectedDrive: null,
    applications: student ? await fetchMyApplications() : [],
  };
}

/**
 * Turns rows into CSV text. A value with a comma, quote or line break is wrapped in
 * quotes, with any quote inside doubled - the rule spreadsheet programs expect. The
 * leading byte-order mark makes Excel read the file as UTF-8.
 */
function toCsv(rows: string[][]): string {
  const escape = (value: string) => (/[",\r\n]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value);
  return "﻿" + rows.map((row) => row.map(escape).join(",")).join("\r\n");
}

/** Same pill treatment as the tabs on the drives board, for consistency. */
function TabButton(props: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={props.onClick}
      className={[
        "rounded-xl px-4 py-2 text-sm font-semibold transition",
        props.active
          ? "bg-linear-to-r from-indigo-600 to-fuchsia-500 text-white shadow-md shadow-indigo-500/25"
          : "text-slate-600 hover:bg-white hover:text-indigo-700",
      ].join(" ")}
    >
      {props.children}
    </button>
  );
}

/** One colour per status, used for the badge, the card's left edge, and the Mark button. */
const STATUS_TONE: Record<ApplicationStatus, { chip: string; edge: string; button: string }> = {
  APPLIED: {
    chip: "bg-sky-100 text-sky-700",
    edge: "from-sky-400 to-indigo-400",
    button: "border border-sky-200 bg-sky-50 text-sky-700 hover:bg-sky-100",
  },
  SHORTLISTED: {
    chip: "bg-amber-100 text-amber-800",
    edge: "from-amber-400 to-orange-400",
    button: "border border-amber-200 bg-amber-50 text-amber-800 hover:bg-amber-100",
  },
  SELECTED: {
    chip: "bg-emerald-100 text-emerald-700",
    edge: "from-emerald-400 to-teal-400",
    button: "border border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100",
  },
  REJECTED: {
    chip: "bg-rose-100 text-rose-700",
    edge: "from-rose-400 to-pink-400",
    button: "border border-rose-200 bg-rose-50 text-rose-700 hover:bg-rose-100",
  },
  WITHDRAWN: {
    chip: "bg-slate-100 text-slate-500",
    edge: "from-slate-300 to-slate-400",
    button: "border border-slate-200 bg-slate-50 text-slate-600 hover:bg-slate-100",
  },
};

function StatusBadge({ status }: { status: ApplicationStatus }) {
  return (
    <span className={`chip shrink-0 ${STATUS_TONE[status].chip}`}>
      <span className="chip-dot" />
      {status.toLowerCase()}
    </span>
  );
}
