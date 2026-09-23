import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import {
  fetchApplicationsForDrive,
  fetchMyApplications,
  updateApplicationStatus,
  withdrawApplication,
} from "../api/applications";
import { fetchMyProfile, logout } from "../api/auth";
import { ApiError } from "../api/client";
import { fetchAllDrives } from "../api/drives";
import type { Application, ApplicationStatus, Drive, UserProfile } from "../api/types";

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

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [applications, setApplications] = useState<Application[]>([]);
  const [drives, setDrives] = useState<Drive[]>([]);
  const [selectedDrive, setSelectedDrive] = useState<number | null>(null);
  const [view, setView] = useState<View>("mine");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const canManage = profile?.role === "TNP_PIC" || profile?.role === "TNP_COORDINATOR";
  const isStudent = profile !== null && profile.role !== "TNP_PIC";
  /** Only a coordinator is both, so only a coordinator needs the switch. */
  const showTabs = canManage && isStudent;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [me, allDrives] = await Promise.all([fetchMyProfile(), fetchAllDrives()]);
      setProfile(me);
      setDrives(allDrives);

      const student = me.role !== "TNP_PIC";
      // The PIC has no applications of their own, so they open straight into review.
      setView(student ? "mine" : "applicants");
      if (student) {
        setApplications(await fetchMyApplications());
      }
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { replace: true });
        return;
      }
      setError(e instanceof ApiError ? e.message : "Could not reach the server");
    } finally {
      setLoading(false);
    }
  }, [navigate]);

  useEffect(() => {
    void load();
  }, [load]);

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

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
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
  const driveName = (id: number) => drives.find((d) => d.id === id)?.companyName ?? `Drive ${id}`;

  return (
    <main className="mx-auto max-w-3xl p-6">
      <header className="flex items-baseline justify-between gap-4">
        <h1 className="text-2xl font-semibold text-slate-900">
          {view === "mine" ? "My applications" : "Applicants"}
        </h1>
        <div className="flex gap-2">
          <button
            onClick={() => navigate("/drives")}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
          >
            Drives
          </button>
          <button
            onClick={() => navigate("/profile")}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
          >
            Profile
          </button>
          <button
            onClick={handleLogout}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
          >
            Sign out
          </button>
        </div>
      </header>

      {showTabs && (
        <div className="mt-6 flex gap-2 border-b border-slate-200">
          <TabButton active={view === "mine"} onClick={() => void switchView("mine")}>
            My applications
          </TabButton>
          <TabButton active={view === "applicants"} onClick={() => void switchView("applicants")}>
            Applicants
          </TabButton>
        </div>
      )}

      {view === "applicants" && canManage && (
        <div className="mt-4">
          <label className="text-sm text-slate-600">
            Drive
            <select
              value={selectedDrive ?? ""}
              onChange={(e) => void showApplicants(Number(e.target.value))}
              className="ml-2 rounded-lg border border-slate-300 px-2 py-1.5 text-sm"
            >
              <option value="">Choose a drive...</option>
              {drives.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.companyName} - {d.role}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}

      {loading && <p className="mt-6 text-slate-500">Loading...</p>}

      {error && (
        <p
          role="alert"
          className="mt-6 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {error}
        </p>
      )}

      {!loading && applications.length === 0 && (
        <p className="mt-6 text-slate-500">
          {view === "mine"
            ? "You have not applied to any drives yet."
            : selectedDrive === null
              ? "Choose a drive to see who applied."
              : // Distinguishing these two matters: otherwise a drive nobody applied to
                // would look identical to not having chosen a drive at all.
                "Nobody has applied to this drive yet."}
        </p>
      )}

      <ul className="mt-6 space-y-3">
        {applications.map((application) => (
          <li key={application.id} className="rounded-xl border border-slate-200 bg-white p-4">
            <div className="flex items-baseline justify-between gap-4">
              <h2 className="font-semibold text-slate-900">
                {view === "applicants"
                  ? `Student #${application.studentId}`
                  : driveName(application.driveId)}
              </h2>
              <StatusBadge status={application.status} />
            </div>

            <p className="mt-1 text-xs text-slate-500">
              Applied {new Date(application.appliedAt).toLocaleDateString("en-IN")}
            </p>

            {application.note && <p className="mt-2 text-sm text-slate-600">{application.note}</p>}

            {view === "applicants" ? (
              <div className="mt-3 flex flex-wrap gap-2">
                {STATUSES.filter((s) => s !== "WITHDRAWN" && s !== application.status).map((status) => (
                  <button
                    key={status}
                    onClick={() => void changeStatus(application.id, status)}
                    className="rounded-lg border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                  >
                    Mark {status.toLowerCase()}
                  </button>
                ))}
              </div>
            ) : (
              application.status !== "WITHDRAWN" && (
                <button
                  onClick={() => void withdraw(application.id)}
                  className="mt-3 rounded-lg border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100"
                >
                  Withdraw
                </button>
              )
            )}
          </li>
        ))}
      </ul>
    </main>
  );
}

/** Same underline treatment as the tabs on the drives board, for consistency. */
function TabButton(props: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={props.onClick}
      className={[
        "-mb-px border-b-2 px-3 py-2 text-sm font-medium",
        props.active
          ? "border-slate-900 text-slate-900"
          : "border-transparent text-slate-500 hover:text-slate-700",
      ].join(" ")}
    >
      {props.children}
    </button>
  );
}

function StatusBadge({ status }: { status: ApplicationStatus }) {
  const tone: Record<ApplicationStatus, string> = {
    APPLIED: "bg-slate-100 text-slate-700",
    SHORTLISTED: "bg-amber-100 text-amber-800",
    SELECTED: "bg-green-100 text-green-800",
    REJECTED: "bg-red-100 text-red-700",
    WITHDRAWN: "bg-slate-100 text-slate-500",
  };

  return (
    <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${tone[status]}`}>
      {status.toLowerCase()}
    </span>
  );
}
