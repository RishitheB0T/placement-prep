import { Link, useNavigate } from "react-router-dom";

import { logout } from "../api/auth";
import type { Role, UserProfile } from "../api/types";
import { Icon, type IconName } from "./ui";

export type NavKey = "dashboard" | "drives" | "applications" | "resumes" | "assistant" | "profile" | "post-drive";

/**
 * What each role is called on screen. The code and the database keep the original names;
 * these are the plain words for what each account does: a coordinator posts drives and
 * decides applications - the recruiting role - and the person in charge administers the
 * cell, including promoting students.
 */
const ROLE_LABEL: Record<Role, string> = {
  STUDENT: "Student",
  TNP_COORDINATOR: "Recruiter",
  TNP_PIC: "Admin",
};

const ROLE_TONE: Record<Role, string> = {
  STUDENT: "bg-sky-100 text-sky-700",
  TNP_COORDINATOR: "bg-amber-100 text-amber-800",
  TNP_PIC: "bg-fuchsia-100 text-fuchsia-700",
};

/**
 * The bar across the top of every signed-in page.
 *
 * Which buttons appear follows the same two independent questions the pages already ask:
 * can this account manage the board, and is this account a student. A coordinator answers
 * yes to both, so they see "Post a drive" and their own applications and resume feedback.
 * The person in charge is faculty, so "My applications" becomes "Applicants" and there is
 * no resume feedback - they have no resume to review.
 *
 * Rendered once, not once for desktop and again for mobile. Two copies would each be
 * read out by a screen reader, and a test asking for "the My applications button" would
 * find two of them. The single row wraps and scrolls on a narrow screen instead.
 */
export default function AppHeader({ profile, active }: { profile: UserProfile | null; active: NavKey }) {
  const navigate = useNavigate();

  const canManage = profile?.role === "TNP_PIC" || profile?.role === "TNP_COORDINATOR";
  const isStudent = profile !== null && profile.role !== "TNP_PIC";

  const items: { key: NavKey; label: string; to: string; icon: IconName; show: boolean }[] = [
    { key: "dashboard", label: "Dashboard", to: "/dashboard", icon: "home", show: true },
    { key: "drives", label: "Drives", to: "/drives", icon: "briefcase", show: true },
    {
      key: "applications",
      label: isStudent ? "My applications" : "Applicants",
      to: "/applications",
      icon: "list",
      show: profile !== null,
    },
    { key: "resumes", label: "Resume feedback", to: "/resumes", icon: "sparkles", show: isStudent },
    { key: "assistant", label: "Assistant", to: "/assistant", icon: "chat", show: isStudent },
    { key: "profile", label: "Profile", to: "/profile", icon: "user", show: profile !== null },
  ];

  function handleLogout() {
    logout();
    navigate("/login", { replace: true });
  }

  return (
    <header className="sticky top-0 z-30 border-b border-white/70 bg-white/70 shadow-sm shadow-indigo-500/5 backdrop-blur-xl">
      <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
        <Link to="/dashboard" className="group flex items-center gap-2.5 rounded-xl focus-visible:ring-4 focus-visible:ring-violet-200 focus-visible:outline-none">
          <span className="grid size-9 place-items-center rounded-xl bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-500 text-white shadow-lg shadow-violet-500/30 transition group-hover:rotate-6">
            <Icon name="cap" className="size-5" />
          </span>
          <span className="text-lg font-extrabold tracking-tight text-slate-900">
            <span className="gradient-text">Placement</span> Prep
          </span>
        </Link>

        <nav
          aria-label="Main"
          className="order-last -mx-1 flex w-full items-center gap-1 overflow-x-auto px-1 pb-1 md:order-none md:w-auto md:flex-1 md:pb-0"
        >
          {items
            .filter((item) => item.show)
            .map((item) => {
              const current = item.key === active;
              return (
                <button
                  key={item.key}
                  type="button"
                  onClick={() => navigate(item.to)}
                  aria-current={current ? "page" : undefined}
                  className={[
                    "btn btn-sm",
                    current
                      ? "bg-linear-to-r from-indigo-500/15 to-fuchsia-500/15 text-indigo-700 ring-1 ring-indigo-200"
                      : "btn-ghost",
                  ].join(" ")}
                >
                  {/* Icons only on very wide screens: a recruiter has seven buttons here. */}
                  <Icon name={item.icon} className="hidden size-4 2xl:block" />
                  {item.label}
                </button>
              );
            })}
          {canManage && (
            <button
              type="button"
              onClick={() => navigate("/post-drive")}
              aria-current={active === "post-drive" ? "page" : undefined}
              className="btn btn-sm btn-primary md:ml-1"
            >
              <Icon name="plus" className="hidden size-4 2xl:block" />
              Post a drive
            </button>
          )}
        </nav>

        <div className="ml-auto flex items-center gap-2">
          {profile && (
            <div className="hidden items-center gap-2 rounded-full bg-white/80 py-1 pr-3 pl-1 shadow-sm ring-1 ring-slate-200/70 sm:flex">
              <span
                aria-hidden="true"
                className="grid size-7 place-items-center rounded-full bg-linear-to-br from-sky-400 to-violet-500 text-xs font-bold text-white"
              >
                {profile.email.charAt(0).toUpperCase()}
              </span>
              {/* Only on very wide screens - below that the nav needs the room. */}
              <span className="hidden max-w-40 truncate text-xs font-medium text-slate-600 2xl:inline">{profile.email}</span>
              <span className={`chip px-2 py-0.5 text-[10px] ${ROLE_TONE[profile.role]}`}>
                {ROLE_LABEL[profile.role]}
              </span>
            </div>
          )}
          <button type="button" onClick={handleLogout} className="btn btn-sm btn-secondary">
            <Icon name="logout" />
            Sign out
          </button>
        </div>
      </div>
    </header>
  );
}
