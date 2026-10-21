import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { applyToDrive, fetchMyApplications } from "../api/applications";
import { fetchMyProfile } from "../api/auth";
import { canManageDrives, isStudentRole } from "../api/roles";
import { ApiError } from "../api/client";
import { deleteDrive, fetchAllDrives, fetchEligibleDrives } from "../api/drives";
import type { Application, ApplicationStatus, Drive, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { CompanyAvatar, EmptyState, Icon, PageHero, SkeletonCards, StatTile } from "../components/ui";

type Tab = "eligible" | "all";

const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

/** Indian formatting, because CTC figures here are read in lakhs. */
function formatInr(amount: number): string {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 0,
  }).format(amount);
}

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
  /**
   * "Now", captured when the list loads rather than read afresh on every render, so
   * the closed checks and countdowns all agree with each other and a re-render cannot
   * change what they say.
   */
  const [now, setNow] = useState(() => Date.now());

  // Two independent questions, not one. A coordinator IS a student - one who has been
  // given the run of the board - so they answer yes to both: they apply to drives like
  // anyone else, and they manage them. A company recruiter and the person in charge have
  // no CGPA to their name, so they are management and nothing else.
  const canManage = canManageDrives(profile?.role);
  const isStudent = isStudentRole(profile?.role);

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
        // get it too. A recruiter and the PIC are excluded: they hold no CGPA or branch,
        // so /api/drives/eligible would answer 409 for them every time.
        const student = isStudentRole(me.role);

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
        setNow(Date.now());
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

  // The summary row above the list, worked out from what is already loaded.
  const openDrives = drives.filter((d) => new Date(d.applicationDeadline).getTime() >= now);
  const closingSoon = openDrives.filter((d) => new Date(d.applicationDeadline).getTime() - now <= WEEK_MS);
  const activeApplications = applications.filter((a) => a.status !== "WITHDRAWN");
  const topCtc = drives.length === 0 ? 0 : Math.max(...drives.map((d) => d.ctc));

  // The backend serves a recruiter only the drives they posted, so the page says so.
  const isRecruiter = profile?.role === "RECRUITER";

  const subtitle = isStudent
    ? tab === "eligible"
      ? "Drives your profile qualifies for, soonest deadline first. Apply in one click."
      : "Every drive on the board, newest first."
    : isRecruiter
      ? "The drives you have posted. Keep details current, and remove old ones."
      : "Everything on the board. Post new drives, keep details current, and remove old ones.";

  return (
    <>
      <AppHeader profile={profile} active="drives" />

      <main className="mx-auto max-w-6xl px-4 pt-8 pb-16">
        <PageHero
          eyebrow={
            profile ? (isStudent ? "Placement season" : isRecruiter ? "Recruiter" : "Training and placement cell") : undefined
          }
          title={isRecruiter ? "Your drives" : "Placement drives"}
          subtitle={subtitle}
        >
          {profile && isStudent && (profile.branch || profile.cgpa !== null) && (
            <div className="flex flex-wrap gap-2">
              {profile.branch && <span className="chip bg-sky-100 text-sky-700">{profile.branch}</span>}
              {profile.cgpa !== null && <span className="chip bg-emerald-100 text-emerald-700">CGPA {profile.cgpa}</span>}
            </div>
          )}
        </PageHero>

        {!loading && !needsProfile && !error && profile && (
          <div className="animate-fade-up mt-8 grid gap-4 [animation-delay:80ms] sm:grid-cols-3">
            <StatTile icon="briefcase" label="Open drives" value={openDrives.length} tone="from-indigo-500 to-violet-500" />
            {isStudent ? (
              <StatTile icon="list" label="Your applications" value={activeApplications.length} tone="from-fuchsia-500 to-pink-500" />
            ) : (
              <StatTile icon="trend" label="Top CTC" value={topCtc === 0 ? "-" : formatInr(topCtc)} tone="from-emerald-500 to-teal-500" />
            )}
            <StatTile icon="clock" label="Closing this week" value={closingSoon.length} tone="from-amber-500 to-orange-500" />
          </div>
        )}

        {/*
          Shown to anyone who is a student, coordinators included. Hidden only from the PIC,
          for whom load() always serves the full board - leaving the tabs up would be
          misleading, since clicking "Eligible for me" would appear to do something while
          quietly serving the same list underneath.
        */}
        {isStudent && (
          <div className="mt-8 inline-flex rounded-2xl bg-white/70 p-1 shadow-sm ring-1 ring-slate-200/70">
            <TabButton active={tab === "eligible"} onClick={() => setTab("eligible")}>
              Eligible for me
            </TabButton>
            <TabButton active={tab === "all"} onClick={() => setTab("all")}>
              All drives
            </TabButton>
          </div>
        )}

        <div className="mt-6">
          {loading && <SkeletonCards count={4} />}

          {needsProfile && !loading && (
            <div className="animate-fade-up overflow-hidden rounded-2xl bg-linear-to-r from-amber-400 via-orange-400 to-rose-400 p-px shadow-lg shadow-orange-500/20">
              <div className="flex flex-col gap-4 rounded-[15px] bg-white/95 p-6 sm:flex-row sm:items-center">
                <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-linear-to-br from-amber-400 to-orange-500 text-white">
                  <Icon name="user" className="size-6" />
                </span>
                <div className="flex-1">
                  <p className="font-semibold text-slate-900">One step left</p>
                  <p className="text-sm text-slate-600">
                    Add your CGPA, branch and school percentages to see which drives you qualify
                    for.
                  </p>
                </div>
                <button onClick={() => navigate("/profile")} className="btn btn-warning">
                  Complete profile
                </button>
              </div>
            </div>
          )}

          {error && !loading && (
            <p role="alert" className="alert-error animate-pop">
              {error}
            </p>
          )}

          {!loading && !error && !needsProfile && drives.length === 0 && (
            <EmptyState icon="inbox" title="Nothing here yet">
              {isStudent && tab === "eligible"
                ? "No open drives match your profile right now."
                : isRecruiter
                  ? "You have not posted a drive yet. Use \"Post a drive\" to add your company's first one."
                  : "No drives have been posted yet."}
            </EmptyState>
          )}

          {!loading && (
            <ul className="grid gap-5 md:grid-cols-2">
              {drives.map((drive, index) => (
                <DriveCard
                  key={drive.id}
                  drive={drive}
                  index={index}
                  now={now}
                  canManage={canManage}
                  isStudent={isStudent}
                  application={applicationFor(drive.id)}
                  onDelete={() => void handleDelete(drive)}
                  onApply={() => void handleApply(drive)}
                  onEdit={() => navigate(`/drives/${drive.id}/edit`)}
                  onResumeFeedback={() => navigate(`/resumes?driveId=${drive.id}`)}
                />
              ))}
            </ul>
          )}
        </div>
      </main>
    </>
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

const TIER_TONE: Record<number, string> = {
  1: "bg-linear-to-r from-amber-400 to-yellow-300 text-amber-950",
  2: "bg-linear-to-r from-sky-400 to-cyan-300 text-sky-950",
  3: "bg-linear-to-r from-violet-400 to-fuchsia-300 text-violet-950",
};

const APPLICATION_TONE: Record<ApplicationStatus, string> = {
  APPLIED: "bg-sky-100 text-sky-700",
  SHORTLISTED: "bg-amber-100 text-amber-800",
  SELECTED: "bg-emerald-100 text-emerald-700",
  REJECTED: "bg-rose-100 text-rose-700",
  WITHDRAWN: "bg-slate-100 text-slate-500",
};

function DriveCard({
  drive,
  index,
  now,
  canManage,
  isStudent,
  application,
  onDelete,
  onApply,
  onEdit,
  onResumeFeedback,
}: {
  drive: Drive;
  index: number;
  now: number;
  canManage: boolean;
  isStudent: boolean;
  application?: Application;
  onDelete: () => void;
  onApply: () => void;
  onEdit: () => void;
  onResumeFeedback: () => void;
}) {
  const [expanded, setExpanded] = useState(false);

  const deadline = new Date(drive.applicationDeadline).toLocaleDateString("en-IN", {
    day: "numeric",
    month: "short",
    year: "numeric",
  });

  const msLeft = new Date(drive.applicationDeadline).getTime() - now;
  const closed = msLeft < 0;
  const daysLeft = Math.ceil(msLeft / (24 * 60 * 60 * 1000));
  const longDescription = (drive.description?.length ?? 0) > 180;

  return (
    <li
      className="card card-hover animate-fade-up flex flex-col p-5 sm:p-6"
      style={{ animationDelay: `${Math.min(index, 8) * 60}ms` }}
    >
      <div className="flex items-start gap-4">
        <CompanyAvatar name={drive.companyName} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-bold text-slate-900">{drive.companyName}</h2>
            <span className={`chip px-2 py-0.5 text-[11px] ${TIER_TONE[drive.tier] ?? TIER_TONE[3]}`}>
              Tier {drive.tier}
            </span>
          </div>
          <p className="text-sm text-slate-600">{drive.role}</p>
        </div>
        <div className="shrink-0 text-right">
          <p className="text-lg font-extrabold text-emerald-600">{formatInr(drive.ctc)}</p>
          <p className="text-[11px] font-medium text-slate-400">per year</p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        <span className="chip bg-indigo-50 text-indigo-700">
          <Icon name="calendar" className="size-3.5" />
          Deadline {deadline}
        </span>
        {closed ? (
          <span className="chip bg-slate-100 text-slate-500">Applications closed</span>
        ) : (
          daysLeft <= 7 && (
            <span className="chip animate-pulse bg-rose-100 text-rose-700">
              <Icon name="clock" className="size-3.5" />
              {daysLeft <= 1 ? "Closes within a day" : `Closes in ${daysLeft} days`}
            </span>
          )
        )}
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
        {drive.cgpaCutoff !== null && <Detail label="CGPA" value={`${drive.cgpaCutoff}+`} />}
        {drive.tenthCutoff !== null && <Detail label="10th" value={`${drive.tenthCutoff}%+`} />}
        {drive.twelfthCutoff !== null && <Detail label="12th" value={`${drive.twelfthCutoff}%+`} />}
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

      {drive.eligibleBranches.length > 0 && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {drive.eligibleBranches.map((branch) => (
            <span key={branch} className="chip bg-violet-50 px-2 py-0.5 text-[11px] text-violet-700">
              {branch}
            </span>
          ))}
        </div>
      )}

      {drive.description && (
        <div className="mt-4">
          <p className={`text-sm leading-relaxed text-slate-600 ${longDescription && !expanded ? "line-clamp-3" : ""}`}>
            {drive.description}
          </p>
          {longDescription && (
            <button
              type="button"
              onClick={() => setExpanded((open) => !open)}
              className="mt-1 text-xs font-semibold text-violet-600 hover:underline"
            >
              {expanded ? "Show less" : "Read more"}
            </button>
          )}
        </div>
      )}

      {/*
        Both halves can appear at once, and for a coordinator they do: applying is
        something they do as a student, deleting is something they do as the cell. The
        two are independent, so this is two checks rather than one either/or.
      */}
      <div className="mt-auto flex flex-wrap items-center gap-2 pt-5">
        {isStudent &&
          (application && application.status !== "WITHDRAWN" ? (
            <span className={`chip px-3 py-1.5 text-sm ${APPLICATION_TONE[application.status]}`}>
              <Icon name="check" className="size-3.5" />
              {application.status === "APPLIED" ? "Applied" : application.status.toLowerCase()}
            </span>
          ) : (
            // No application yet, or the previous one was withdrawn - either way the
            // backend accepts a fresh POST here. A withdrawn row reopens rather than
            // duplicates, since applying is blocked only by an active or decided
            // application, not by history.
            <button onClick={onApply} disabled={closed} className="btn btn-sm btn-primary">
              {closed ? "Closed" : "Apply"}
            </button>
          ))}

        {isStudent && (
          <button onClick={onResumeFeedback} className="btn btn-sm btn-secondary">
            <Icon name="sparkles" />
            Get resume feedback
          </button>
        )}

        {canManage && (
          <div className="ml-auto flex gap-2">
            <button onClick={onEdit} className="btn btn-sm btn-secondary">
              <Icon name="pencil" />
              Edit
            </button>
            <button onClick={onDelete} className="btn btn-sm btn-danger">
              <Icon name="trash" />
              Delete
            </button>
          </div>
        )}
      </div>
    </li>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl bg-slate-50/80 px-3 py-2 ring-1 ring-slate-100">
      <dt className="text-[10px] font-semibold tracking-wider text-slate-400 uppercase">{label}</dt>
      <dd className="text-sm font-semibold text-slate-700">{value}</dd>
    </div>
  );
}
