import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { applyToDrive, fetchMyApplications } from "../api/applications";
import { fetchMyProfile, logout } from "../api/auth";
import { ApiError } from "../api/client";
import { deleteDrive, fetchAllDrives, fetchEligibleDrives } from "../api/drives";
import type { Application, Drive, UserProfile } from "../api/types";

type Tab = "eligible" | "all";

export default function Drives() {
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>("eligible");
  const [drives, setDrives] = useState<Drive[]>([]);
  const [applications, setApplications] = useState<Application[]>([]);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** Set when the backend answers 409: the profile is not filled in yet. */
  const [needsProfile, setNeedsProfile] = useState(false);

  // Two independent questions, not one. A coordinator IS a student - one who has been
  // given the run of the board - so they answer yes to both: they apply to drives like
  // anyone else, and they manage them. Only the person in charge, an actual member of
  // faculty with no CGPA to their name, is management and nothing else.
  const canManage = profile?.role === "TNP_PIC" || profile?.role === "TNP_COORDINATOR";
  const isStudent = profile !== null && profile.role !== "TNP_PIC";

  // useCallback so the effect below does not re-run on every render.
  const load = useCallback(
    async (which: Tab) => {
      setLoading(true);
      setError(null);
      setNeedsProfile(false);

      try {
        // Sequential rather than Promise.all: which endpoint to call next depends on the
        // role that only the first response reveals, so there is nothing to parallelise.
        const me = await fetchMyProfile();
        setProfile(me);

        // "Eligible for me" is a student concept, and a coordinator is a student, so they
        // get it too. Only the PIC is excluded: they hold no CGPA or branch, so
        // /api/drives/eligible would answer 409 for them every time.
        const student = me.role !== "TNP_PIC";

        // The profile we just loaded already says whether it is complete, so an
        // incomplete one short-circuits here rather than making a request we know will
        // be refused. The 409 branch below still exists for the race where the profile
        // is emptied between these two calls - it is just no longer the normal path,
        // which is what used to fill the console with red on every first visit.
        if (student && which === "eligible" && !me.complete) {
          setNeedsProfile(true);
          setDrives([]);
          setApplications(await fetchMyApplications());
          return;
        }

        const [list, mine] = await Promise.all([
          student && which === "eligible" ? fetchEligibleDrives() : fetchAllDrives(),
          // The PIC has no applications of their own; asking would waste a request.
          student ? fetchMyApplications() : Promise.resolve([] as Application[]),
        ]);
        setDrives(list);
        setApplications(mine);
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) {
          // The token is gone or expired. client.ts has already cleared it.
          navigate("/login", { replace: true });
          return;
        }
        if (e instanceof ApiError && e.status === 409) {
          // Not an error the user caused - they simply have not filled the form in.
          setNeedsProfile(true);
          setDrives([]);
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not reach the server");
      } finally {
        setLoading(false);
      }
    },
    [navigate],
  );

  useEffect(() => {
    void load(tab);
  }, [tab, load]);

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  async function handleDelete(drive: Drive) {
    if (!window.confirm(`Delete the ${drive.companyName} drive? This cannot be undone.`)) {
      return;
    }
    try {
      await deleteDrive(drive.id);
      setDrives((current) => current.filter((d) => d.id !== drive.id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not delete the drive");
    }
  }

  async function handleApply(drive: Drive) {
    setError(null);
    try {
      const created = await applyToDrive(drive.id);
      // Replace rather than append: re-applying after a withdrawal reopens the same
      // application row on the backend (same id, status back to APPLIED) rather than
      // creating a second one, so the stale withdrawn entry for this drive has to be
      // dropped or the board would hold two records for the one drive.
      setApplications((current) => [...current.filter((a) => a.driveId !== drive.id), created]);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not apply");
    }
  }

  const applicationFor = (driveId: number) => applications.find((a) => a.driveId === driveId);

  return (
    <main className="mx-auto max-w-3xl p-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Placement drives</h1>
          {profile && (
            <p className="mt-1 text-sm text-slate-500">
              {profile.email}
              {profile.branch && ` · ${profile.branch}`}
              {profile.cgpa !== null && ` · CGPA ${profile.cgpa}`}
            </p>
          )}
        </div>
        <div className="flex gap-2">
          {canManage && (
            <button
              onClick={() => navigate("/post-drive")}
              className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700"
            >
              Post a drive
            </button>
          )}
          <button
            onClick={() => navigate("/applications")}
            className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
          >
            {isStudent ? "My applications" : "Applicants"}
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

      {/*
        Shown to anyone who is a student, coordinators included. Hidden only from the PIC,
        for whom load() always serves the full board - leaving the tabs up would be
        misleading, since clicking "Eligible for me" would appear to do something while
        quietly serving the same list underneath.
      */}
      {isStudent && (
        <div className="mt-6 flex gap-2 border-b border-slate-200">
          <TabButton active={tab === "eligible"} onClick={() => setTab("eligible")}>
            Eligible for me
          </TabButton>
          <TabButton active={tab === "all"} onClick={() => setTab("all")}>
            All drives
          </TabButton>
        </div>
      )}

      {loading && <p className="mt-6 text-slate-500">Loading...</p>}

      {needsProfile && !loading && (
        <div className="mt-6 rounded-lg border border-amber-200 bg-amber-50 p-4">
          <p className="text-sm text-amber-800">
            Add your CGPA, branch and school percentages to see which drives you qualify
            for.
          </p>
          <button
            onClick={() => navigate("/profile")}
            className="mt-3 rounded-lg bg-amber-800 px-3 py-1.5 text-sm font-medium text-white hover:bg-amber-700"
          >
            Complete profile
          </button>
        </div>
      )}

      {error && !loading && (
        <p
          role="alert"
          className="mt-6 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700"
        >
          {error}
        </p>
      )}

      {!loading && !error && !needsProfile && drives.length === 0 && (
        <p className="mt-6 text-slate-500">
          {isStudent && tab === "eligible"
            ? "No open drives match your profile right now."
            : "No drives have been posted yet."}
        </p>
      )}

      <ul className="mt-6 space-y-3">
        {drives.map((drive) => (
          <DriveCard
            key={drive.id}
            drive={drive}
            canManage={canManage}
            isStudent={isStudent}
            application={applicationFor(drive.id)}
            onDelete={() => void handleDelete(drive)}
            onApply={() => void handleApply(drive)}
            onEdit={() => navigate(`/drives/${drive.id}/edit`)}
          />
        ))}
      </ul>
    </main>
  );
}

function TabButton(props: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
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

function DriveCard({
  drive,
  canManage,
  isStudent,
  application,
  onDelete,
  onApply,
  onEdit,
}: {
  drive: Drive;
  canManage: boolean;
  isStudent: boolean;
  application?: Application;
  onDelete: () => void;
  onApply: () => void;
  onEdit: () => void;
}) {
  // Indian formatting, because CTC figures here are read in lakhs.
  const ctc = new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(drive.ctc);

  const deadline = new Date(drive.applicationDeadline).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });

  const closed = new Date(drive.applicationDeadline).getTime() < Date.now();

  return (
    <li className="rounded-xl border border-slate-200 bg-white p-4">
      <div className="flex items-baseline justify-between gap-4">
        <h2 className="font-semibold text-slate-900">{drive.companyName}</h2>
        <span className="shrink-0 text-sm font-medium text-slate-900">{ctc}</span>
      </div>
      <p className="text-sm text-slate-600">{drive.role}</p>

      <dl className="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-500">
        <Detail label="Deadline" value={deadline} />
        {drive.cgpaCutoff !== null && <Detail label="CGPA" value={`${drive.cgpaCutoff}+`} />}
        {drive.tenthCutoff !== null && <Detail label="10th" value={`${drive.tenthCutoff}%+`} />}
        {drive.twelfthCutoff !== null && (
          <Detail label="12th" value={`${drive.twelfthCutoff}%+`} />
        )}
        <Detail
          label="Backlogs"
          value={
            drive.backlogsAllowed
              ? drive.maxBacklogs === null
                ? "allowed"
                : `up to ${drive.maxBacklogs}`
              : "not allowed"
          }
        />
      </dl>

      <p className="mt-3 text-xs text-slate-500">{drive.eligibleBranches.join(" · ")}</p>

      {drive.description && (
        <p className="mt-3 text-sm text-slate-600">{drive.description}</p>
      )}

      {/*
        Both halves can appear at once, and for a coordinator they do: applying is
        something they do as a student, deleting is something they do as the cell. The
        two are independent, so this is two checks rather than one either/or.
      */}
      <div className="mt-4 flex flex-wrap items-center gap-2">
        {isStudent &&
          (application && application.status !== "WITHDRAWN" ? (
            <span className="rounded-lg bg-slate-100 px-3 py-1.5 text-sm font-medium text-slate-600">
              {application.status === "APPLIED" ? "Applied" : application.status.toLowerCase()}
            </span>
          ) : (
            // No application yet, or the previous one was withdrawn - either way the
            // backend accepts a fresh POST here. A withdrawn row reopens rather than
            // duplicates, since applying is blocked only by an active or decided
            // application, not by history.
            <button
              onClick={onApply}
              disabled={closed}
              className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-40"
            >
              {closed ? "Closed" : "Apply"}
            </button>
          ))}

        {canManage && (
          <>
            <button
              onClick={onEdit}
              className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-100"
            >
              Edit
            </button>
            <button
              onClick={onDelete}
              className="rounded-lg border border-red-300 px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50"
            >
              Delete
            </button>
          </>
        )}
      </div>
    </li>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="inline text-slate-400">{label}: </dt>
      <dd className="inline text-slate-600">{value}</dd>
    </div>
  );
}
