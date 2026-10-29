import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";

import { fetchApplicationStats, fetchMyApplications } from "../api/applications";
import { createRecruiter, fetchMyProfile, fetchUsers, promoteToCoordinator, resetPassword } from "../api/auth";
import { ApiError } from "../api/client";
import { fetchAllDrives, fetchEligibleDrives } from "../api/drives";
import { fetchMyResumes } from "../api/resumes";
import type { Application, ApplicationStats, ApplicationStatus, Drive, Resume, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { CompanyAvatar, EmptyState, Icon, PageHero, SkeletonCards, StatTile, type IconName } from "../components/ui";

/**
 * Each role's own dashboard, on one route.
 *
 * Which view appears follows from the role in the signed-in profile - a student sees
 * their own placement journey, a recruiter sees what is waiting for review, a coordinator
 * sees both, and the admin sees the whole cell and the people in it. Every number on each view comes
 * from an endpoint that role is allowed to call; the backend would refuse the others.
 */
export default function Dashboard() {
  const navigate = useNavigate();
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMyProfile()
      .then((me) => {
        if (!cancelled) setProfile(me);
      })
      .catch((e) => {
        if (cancelled) return;
        if (e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not reach the server");
      });
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  return (
    <>
      {/* React 19 moves a <title> rendered anywhere into the document head. */}
      <title>Dashboard · Placement Prep</title>
      <AppHeader profile={profile} active="dashboard" />
      <main className="mx-auto max-w-6xl px-4 pt-8 pb-16">
        {error && (
          <p role="alert" className="alert-error animate-pop">
            {error}
          </p>
        )}
        {!profile && !error && <SkeletonCards count={4} />}
        {profile?.role === "STUDENT" && <StudentDashboard profile={profile} />}
        {profile?.role === "TNP_COORDINATOR" && <HiringDashboard profile={profile} alsoStudent />}
        {profile?.role === "RECRUITER" && <HiringDashboard profile={profile} alsoStudent={false} />}
        {profile?.role === "TNP_PIC" && <AdminDashboard profile={profile} />}
      </main>
    </>
  );
}

// ------------------------------------------------------------------ Student

function StudentDashboard({ profile }: { profile: UserProfile }) {
  const navigate = useNavigate();
  const [eligible, setEligible] = useState<Drive[]>([]);
  const [drives, setDrives] = useState<Drive[]>([]);
  const [applications, setApplications] = useState<Application[]>([]);
  const [resumes, setResumes] = useState<Resume[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Eligibility needs a complete profile; asking without one would only be refused.
    Promise.all([
      profile.complete ? fetchEligibleDrives() : Promise.resolve([] as Drive[]),
      fetchAllDrives(),
      fetchMyApplications(),
      fetchMyResumes(),
    ])
      .then(([eligibleDrives, allDrives, mine, myResumes]) => {
        setEligible(eligibleDrives);
        setDrives(allDrives);
        setApplications(mine);
        setResumes(myResumes);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load your dashboard"))
      .finally(() => setLoading(false));
  }, [profile.complete]);

  const appliedTo = new Set(applications.filter((a) => a.status !== "WITHDRAWN").map((a) => a.driveId));
  const notYetApplied = eligible.filter((d) => !appliedTo.has(d.id));
  const active = applications.filter((a) => a.status !== "WITHDRAWN");
  const progressing = applications.filter((a) => a.status === "SHORTLISTED" || a.status === "SELECTED");
  const latestReview = resumes.find((r) => r.status === "COMPLETED");
  const driveName = (id: number) => drives.find((d) => d.id === id)?.companyName ?? `Drive ${id}`;

  return (
    <>
      <PageHero
        eyebrow="Student dashboard"
        title={`Welcome back, ${firstName(profile.email)}`}
        subtitle="Your placement season at a glance - what you can apply to, where your applications stand, and what to do next."
      />

      {!profile.complete && (
        <Callout
          icon="user"
          title="One step left"
          text="Add your CGPA, branch and school percentages to see which drives you qualify for."
          action={
            <button onClick={() => navigate("/profile")} className="btn btn-warning">
              Complete profile
            </button>
          }
        />
      )}

      {error && (
        <p role="alert" className="alert-error animate-pop mt-6">
          {error}
        </p>
      )}

      {loading ? (
        <div className="mt-8">
          <SkeletonCards count={4} />
        </div>
      ) : (
        <>
          <div className="animate-fade-up mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatTile icon="briefcase" label="Drives to apply to" value={notYetApplied.length} tone="from-indigo-500 to-violet-500" />
            <StatTile icon="list" label="Active applications" value={active.length} tone="from-sky-500 to-cyan-500" />
            <StatTile icon="trend" label="Shortlisted or selected" value={progressing.length} tone="from-emerald-500 to-teal-500" />
            <StatTile icon="sparkles" label="Latest resume score" value={latestReview?.score ?? "-"} tone="from-fuchsia-500 to-pink-500" />
          </div>

          <div className="mt-8 grid gap-6 lg:grid-cols-2">
            <Panel title="Closing soon for you" action={<LinkButton onClick={() => navigate("/drives")}>All drives</LinkButton>}>
              {notYetApplied.length === 0 ? (
                <p className="text-sm text-slate-500">
                  {profile.complete ? "Nothing open that you have not applied to yet." : "Complete your profile to see these."}
                </p>
              ) : (
                <ul className="space-y-3">
                  {notYetApplied.slice(0, 3).map((drive) => (
                    <DriveRow key={drive.id} drive={drive}>
                      <span className="chip bg-rose-50 text-rose-700">{deadlineLabel(drive.applicationDeadline)}</span>
                    </DriveRow>
                  ))}
                </ul>
              )}
            </Panel>

            <Panel title="Your applications" action={<LinkButton onClick={() => navigate("/applications")}>See all</LinkButton>}>
              {applications.length === 0 ? (
                <p className="text-sm text-slate-500">You have not applied to any drives yet.</p>
              ) : (
                <ul className="space-y-3">
                  {applications.slice(0, 4).map((application) => (
                    <li key={application.id} className="flex items-center gap-3">
                      <CompanyAvatar name={driveName(application.driveId)} size="sm" />
                      <span className="min-w-0 flex-1 truncate text-sm font-semibold text-slate-800">
                        {driveName(application.driveId)}
                      </span>
                      <span className={`chip ${STATUS_TONE[application.status]}`}>{application.status.toLowerCase()}</span>
                    </li>
                  ))}
                </ul>
              )}
            </Panel>
          </div>

          <div className="mt-6 grid gap-4 sm:grid-cols-3">
            <ActionCard icon="sparkles" tone="from-violet-500 to-fuchsia-500" title="Resume feedback" text="Score your resume against a drive" onClick={() => navigate("/resumes")} />
            <ActionCard icon="chat" tone="from-sky-500 to-indigo-500" title="Ask the assistant" text="Plain-English answers about your placements" onClick={() => navigate("/assistant")} />
            <ActionCard icon="user" tone="from-amber-500 to-orange-500" title="Your profile" text="Keep your marks accurate" onClick={() => navigate("/profile")} />
          </div>
        </>
      )}
    </>
  );
}

// ------------------------------------------------- Recruiter and coordinator

/**
 * The hiring view, for a company recruiter and for a coordinator. A coordinator is also a
 * student, so `alsoStudent` adds their own applications; a recruiter has none, and the
 * backend would answer 403 if this asked for them.
 */
function HiringDashboard({ profile, alsoStudent }: { profile: UserProfile; alsoStudent: boolean }) {
  const navigate = useNavigate();
  const [drives, setDrives] = useState<Drive[]>([]);
  const [stats, setStats] = useState<ApplicationStats | null>(null);
  const [mine, setMine] = useState<Application[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      fetchAllDrives(),
      fetchApplicationStats(),
      alsoStudent ? fetchMyApplications() : Promise.resolve([] as Application[]),
    ])
      .then(([allDrives, applicationStats, myApplications]) => {
        setDrives(allDrives);
        setStats(applicationStats);
        setMine(myApplications);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load your dashboard"));
  }, [alsoStudent]);

  const myActive = mine.filter((a) => a.status !== "WITHDRAWN").length;

  return (
    <>
      <PageHero
        eyebrow={alsoStudent ? "Coordinator dashboard" : "Recruiter dashboard"}
        title="Recruiting overview"
        subtitle={`Signed in as ${profile.email}. Post drives, review who applied, and move applications along.`}
      >
        <button onClick={() => navigate("/post-drive")} className="btn btn-primary">
          <Icon name="plus" />
          Post a new drive
        </button>
      </PageHero>

      {error && (
        <p role="alert" className="alert-error animate-pop mt-6">
          {error}
        </p>
      )}

      {!stats && !error ? (
        <div className="mt-8">
          <SkeletonCards count={4} />
        </div>
      ) : (
        stats && (
          <>
            <CellStats drives={drives} stats={stats} />
            {alsoStudent ? (
              <div className="mt-8 grid gap-6 lg:grid-cols-3">
                <div className="lg:col-span-2">
                  <NeedsReview drives={drives} stats={stats} />
                </div>
                <Panel title="Your own placement">
                  <p className="text-sm text-slate-600">
                    A coordinator is a student too. You have {myActive} active application{myActive === 1 ? "" : "s"}.
                  </p>
                  <div className="mt-4 flex flex-wrap gap-2">
                    <button onClick={() => navigate("/applications")} className="btn btn-sm btn-secondary">
                      My applications
                    </button>
                    <button onClick={() => navigate("/resumes")} className="btn btn-sm btn-secondary">
                      Resume feedback
                    </button>
                  </div>
                </Panel>
              </div>
            ) : (
              <div className="mt-8">
                <NeedsReview drives={drives} stats={stats} />
              </div>
            )}
          </>
        )
      )}
    </>
  );
}

// -------------------------------------------------------------------- Admin

function AdminDashboard({ profile }: { profile: UserProfile }) {
  const [drives, setDrives] = useState<Drive[]>([]);
  const [stats, setStats] = useState<ApplicationStats | null>(null);
  const [users, setUsers] = useState<UserProfile[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchAllDrives(), fetchApplicationStats(), fetchUsers()])
      .then(([allDrives, applicationStats, everyone]) => {
        setDrives(allDrives);
        setStats(applicationStats);
        setUsers(everyone);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load your dashboard"));
  }, []);

  return (
    <>
      <PageHero
        eyebrow="Admin dashboard"
        title="Placement cell overview"
        subtitle={`Signed in as ${profile.email}. The whole board, everything waiting for review, and the people in the cell.`}
      />

      {error && (
        <p role="alert" className="alert-error animate-pop mt-6">
          {error}
        </p>
      )}

      {!stats && !error ? (
        <div className="mt-8">
          <SkeletonCards count={4} />
        </div>
      ) : (
        stats && (
          <>
            <CellStats drives={drives} stats={stats} />
            <div className="mt-8 grid items-start gap-6 lg:grid-cols-2">
              <NeedsReview drives={drives} stats={stats} />
              <People
                users={users}
                onPromoted={(updated) => setUsers((all) => all.map((u) => (u.id === updated.id ? updated : u)))}
                onAdded={(added) => setUsers((all) => [...all, added])}
              />
            </div>
          </>
        )
      )}
    </>
  );
}

type PeopleFilter = "STUDENT" | "TNP_COORDINATOR" | "RECRUITER";

const PEOPLE_TABS: { role: PeopleFilter; label: string }[] = [
  { role: "STUDENT", label: "Students" },
  { role: "TNP_COORDINATOR", label: "Coordinators" },
  { role: "RECRUITER", label: "Recruiters" },
];

/**
 * The admin's user management: make a student a coordinator, or add a company's recruiter.
 * Both are things only the admin may do, so they live on the admin's own dashboard.
 */
function People({
  users,
  onPromoted,
  onAdded,
}: {
  users: UserProfile[];
  onPromoted: (user: UserProfile) => void;
  onAdded: (user: UserProfile) => void;
}) {
  const [filter, setFilter] = useState<PeopleFilter>("STUDENT");
  const [search, setSearch] = useState("");
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [recruiterEmail, setRecruiterEmail] = useState("");
  const [recruiterPassword, setRecruiterPassword] = useState("");
  /** The account whose password is being reset, if any - one inline form at a time. */
  const [resettingId, setResettingId] = useState<number | null>(null);
  const [newPassword, setNewPassword] = useState("");

  /** The "forgot password" path: the admin sets a temporary password and hands it over. */
  async function submitReset(event: FormEvent, user: UserProfile) {
    event.preventDefault();
    setMessage(null);
    setError(null);
    try {
      await resetPassword(user.id, newPassword);
      setResettingId(null);
      setNewPassword("");
      setMessage(`${user.email} can now sign in with the new password. Ask them to change it on their Profile page.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not reset the password");
    }
  }

  async function addRecruiter(event: FormEvent) {
    event.preventDefault();
    setMessage(null);
    setError(null);
    try {
      const added = await createRecruiter(recruiterEmail, recruiterPassword);
      onAdded(added);
      setRecruiterEmail("");
      setRecruiterPassword("");
      setMessage(`${added.email} can now sign in on the Recruiter tab with the password you set.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not add this recruiter");
    }
  }

  const shown = useMemo(
    () =>
      users
        .filter((u) => u.role === filter)
        .filter((u) => u.email.toLowerCase().includes(search.trim().toLowerCase())),
    [users, filter, search],
  );

  async function promote(user: UserProfile) {
    if (!window.confirm(`Make ${user.email} a coordinator? They will be able to post drives and decide applications.`)) {
      return;
    }
    setMessage(null);
    setError(null);
    try {
      const updated = await promoteToCoordinator(user.id);
      onPromoted(updated);
      setMessage(`${updated.email} is now a coordinator. The coordinator pages appear the next time they open a page.`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not promote this account");
    }
  }

  return (
    <Panel title="People">
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-xl bg-slate-100/80 p-1 text-sm">
          {PEOPLE_TABS.map(({ role, label }) => (
            <button
              key={role}
              onClick={() => setFilter(role)}
              className={[
                "rounded-lg px-3 py-1.5 font-semibold transition",
                filter === role ? "bg-white text-violet-700 shadow-sm" : "text-slate-500 hover:text-slate-700",
              ].join(" ")}
            >
              {label} ({users.filter((u) => u.role === role).length})
            </button>
          ))}
        </div>
        <label className="sr-only" htmlFor="people-search">
          Search people by email
        </label>
        <input
          id="people-search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by email"
          className="input mt-0 min-w-0 flex-1"
        />
      </div>

      {filter === "RECRUITER" && (
        <form onSubmit={(e) => void addRecruiter(e)} className="mt-4 rounded-xl border border-emerald-100 bg-emerald-50/60 p-3">
          <p className="text-sm font-semibold text-slate-800">Add a company recruiter</p>
          <div className="mt-2 flex flex-wrap gap-2">
            <label className="sr-only" htmlFor="recruiter-email">
              Recruiter email
            </label>
            <input
              id="recruiter-email"
              type="email"
              required
              value={recruiterEmail}
              onChange={(e) => setRecruiterEmail(e.target.value)}
              placeholder="hr@company.com"
              className="input mt-0 min-w-0 flex-1"
            />
            <label className="sr-only" htmlFor="recruiter-password">
              First password
            </label>
            <input
              id="recruiter-password"
              type="password"
              required
              minLength={8}
              maxLength={72}
              value={recruiterPassword}
              onChange={(e) => setRecruiterPassword(e.target.value)}
              placeholder="First password"
              autoComplete="new-password"
              className="input mt-0 min-w-0 flex-1"
            />
            <button type="submit" className="btn btn-sm btn-primary">
              <Icon name="plus" />
              Add recruiter
            </button>
          </div>
        </form>
      )}

      {message && <p className="alert-success animate-pop mt-4">{message}</p>}
      {error && (
        <p role="alert" className="alert-error animate-pop mt-4">
          {error}
        </p>
      )}

      {shown.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">Nobody matches.</p>
      ) : (
        <ul className="mt-4 max-h-96 space-y-2 overflow-y-auto pr-1">
          {shown.map((user) => (
            <li key={user.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-100 bg-white/80 px-3 py-2.5">
              <span
                aria-hidden="true"
                className="grid size-8 shrink-0 place-items-center rounded-lg bg-linear-to-br from-sky-400 to-violet-500 text-xs font-bold text-white"
              >
                {user.email.charAt(0).toUpperCase()}
              </span>
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-semibold text-slate-800">{user.email}</p>
                <p className="text-xs text-slate-500">
                  {user.role === "RECRUITER" ? "Company recruiter" : (user.branch ?? "No branch yet")}
                  {user.cgpa !== null && ` · CGPA ${user.cgpa}`}
                </p>
              </div>
              {user.role === "STUDENT" && (
                <button onClick={() => void promote(user)} className="btn btn-sm btn-secondary">
                  Make coordinator
                </button>
              )}
              <button
                onClick={() => {
                  setResettingId(resettingId === user.id ? null : user.id);
                  setNewPassword("");
                }}
                aria-label={`Reset password for ${user.email}`}
                className="btn btn-sm btn-ghost"
              >
                <Icon name="lock" />
              </button>
              {resettingId === user.id && (
                <form onSubmit={(e) => void submitReset(e, user)} className="flex w-full flex-wrap gap-2">
                  <label className="sr-only" htmlFor={`reset-${user.id}`}>
                    New password for {user.email}
                  </label>
                  <input
                    id={`reset-${user.id}`}
                    type="password"
                    required
                    minLength={8}
                    maxLength={72}
                    value={newPassword}
                    onChange={(e) => setNewPassword(e.target.value)}
                    placeholder="New temporary password"
                    autoComplete="new-password"
                    className="input mt-0 min-w-0 flex-1"
                  />
                  <button type="submit" className="btn btn-sm btn-primary">
                    Set password
                  </button>
                </form>
              )}
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

// ------------------------------------------------------- Shared by the cell

function CellStats({ drives, stats }: { drives: Drive[]; stats: ApplicationStats }) {
  // Read once, when the row first renders, so a re-render cannot change the count.
  const [now] = useState(() => Date.now());
  const open = drives.filter((d) => new Date(d.applicationDeadline).getTime() >= now).length;
  return (
    <div className="animate-fade-up mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <StatTile icon="briefcase" label="Open drives" value={open} tone="from-indigo-500 to-violet-500" />
      <StatTile icon="inbox" label="Awaiting review" value={stats.byStatus.APPLIED} tone="from-amber-500 to-orange-500" />
      <StatTile icon="list" label="Shortlisted" value={stats.byStatus.SHORTLISTED} tone="from-sky-500 to-cyan-500" />
      <StatTile icon="check" label="Selected" value={stats.byStatus.SELECTED} tone="from-emerald-500 to-teal-500" />
    </div>
  );
}

/** Drives with applications nobody has looked at yet, most waiting first. */
function NeedsReview({ drives, stats }: { drives: Drive[]; stats: ApplicationStats }) {
  const navigate = useNavigate();
  const waiting = drives
    .map((drive) => ({ drive, count: stats.awaitingReviewByDrive[String(drive.id)] ?? 0 }))
    .filter((entry) => entry.count > 0)
    .sort((a, b) => b.count - a.count);

  return (
    <Panel title="Needs your review">
      {waiting.length === 0 ? (
        <EmptyState icon="check" title="All caught up">
          Every application has been looked at.
        </EmptyState>
      ) : (
        <ul className="space-y-3">
          {waiting.map(({ drive, count }) => (
            <DriveRow key={drive.id} drive={drive}>
              <span className="chip bg-amber-100 text-amber-800">{count} new</span>
              <button
                onClick={() => navigate(`/applications?driveId=${drive.id}`)}
                className="btn btn-sm btn-secondary"
              >
                Review applicants
              </button>
            </DriveRow>
          ))}
        </ul>
      )}
    </Panel>
  );
}

// ------------------------------------------------------------ Small pieces

function Panel({ title, action, children }: { title: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section className="card animate-fade-up p-6">
      <div className="mb-4 flex items-center justify-between gap-3">
        <h2 className="text-lg font-bold text-slate-900">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

function DriveRow({ drive, children }: { drive: Drive; children: ReactNode }) {
  return (
    <li className="flex flex-wrap items-center gap-3 rounded-xl border border-slate-100 bg-white/80 px-3 py-2.5">
      <CompanyAvatar name={drive.companyName} size="sm" />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-slate-800">{drive.companyName}</p>
        <p className="truncate text-xs text-slate-500">{drive.role}</p>
      </div>
      {children}
    </li>
  );
}

function Callout({ icon, title, text, action }: { icon: IconName; title: string; text: string; action: ReactNode }) {
  return (
    <div className="animate-fade-up mt-6 overflow-hidden rounded-2xl bg-linear-to-r from-amber-400 via-orange-400 to-rose-400 p-px shadow-lg shadow-orange-500/20">
      <div className="flex flex-col gap-4 rounded-[15px] bg-white/95 p-5 sm:flex-row sm:items-center">
        <span className="grid size-11 shrink-0 place-items-center rounded-2xl bg-linear-to-br from-amber-400 to-orange-500 text-white">
          <Icon name={icon} className="size-5" />
        </span>
        <div className="flex-1">
          <p className="font-semibold text-slate-900">{title}</p>
          <p className="text-sm text-slate-600">{text}</p>
        </div>
        {action}
      </div>
    </div>
  );
}

function ActionCard({
  icon,
  tone,
  title,
  text,
  onClick,
}: {
  icon: IconName;
  tone: string;
  title: string;
  text: string;
  onClick: () => void;
}) {
  return (
    <button onClick={onClick} className="card card-hover flex items-center gap-4 p-5 text-left">
      <span className={`grid size-11 shrink-0 place-items-center rounded-2xl bg-linear-to-br text-white shadow-md ${tone}`}>
        <Icon name={icon} className="size-5" />
      </span>
      <span>
        <span className="block font-semibold text-slate-900">{title}</span>
        <span className="block text-xs text-slate-500">{text}</span>
      </span>
    </button>
  );
}

function LinkButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button onClick={onClick} className="text-sm font-semibold text-violet-600 hover:underline">
      {children}
    </button>
  );
}

const STATUS_TONE: Record<ApplicationStatus, string> = {
  APPLIED: "bg-sky-100 text-sky-700",
  SHORTLISTED: "bg-amber-100 text-amber-800",
  SELECTED: "bg-emerald-100 text-emerald-700",
  REJECTED: "bg-rose-100 text-rose-700",
  WITHDRAWN: "bg-slate-100 text-slate-500",
};

/** "asha.verma@cse.nits.ac.in" -> "Asha". */
function firstName(email: string): string {
  const first = email.split("@")[0].split(/[._-]/)[0];
  return first.charAt(0).toUpperCase() + first.slice(1);
}

function deadlineLabel(iso: string): string {
  return `Closes ${new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short" })}`;
}
