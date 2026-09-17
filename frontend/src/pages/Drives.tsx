import { useCallback, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { fetchMyProfile, logout } from "../api/auth";
import { ApiError } from "../api/client";
import { fetchAllDrives, fetchEligibleDrives } from "../api/drives";
import type { Drive, UserProfile } from "../api/types";

type Tab = "eligible" | "all";

export default function Drives() {
  const navigate = useNavigate();

  const [tab, setTab] = useState<Tab>("eligible");
  const [drives, setDrives] = useState<Drive[]>([]);
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  /** Set when the backend answers 409: the profile is not filled in yet. */
  const [needsProfile, setNeedsProfile] = useState(false);

  // useCallback so the effect below does not re-run on every render.
  const load = useCallback(
    async (which: Tab) => {
      setLoading(true);
      setError(null);
      setNeedsProfile(false);

      try {
        const [me, list] = await Promise.all([
          fetchMyProfile(),
          which === "eligible" ? fetchEligibleDrives() : fetchAllDrives(),
        ]);
        setProfile(me);
        setDrives(list);
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

      <div className="mt-6 flex gap-2 border-b border-slate-200">
        <TabButton active={tab === "eligible"} onClick={() => setTab("eligible")}>
          Eligible for me
        </TabButton>
        <TabButton active={tab === "all"} onClick={() => setTab("all")}>
          All drives
        </TabButton>
      </div>

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
          {tab === "eligible"
            ? "No open drives match your profile right now."
            : "No drives have been posted yet."}
        </p>
      )}

      <ul className="mt-6 space-y-3">
        {drives.map((drive) => (
          <DriveCard key={drive.id} drive={drive} />
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

function DriveCard({ drive }: { drive: Drive }) {
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
