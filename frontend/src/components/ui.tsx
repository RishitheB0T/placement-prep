import type { ReactNode } from "react";

/**
 * Small presentational pieces shared by several pages. Nothing here fetches data or
 * navigates - each one only turns props into markup, so any page can use them freely.
 */

/** Outline icon paths on a 24x24 grid, drawn with the current text colour. */
const ICON_PATHS = {
  cap: "M4.26 10.15a60 60 0 0 0-.49 6.35A48.6 48.6 0 0 1 12 20.9a48.6 48.6 0 0 1 8.23-4.4 60 60 0 0 0-.49-6.35m-15.48 0a50.6 50.6 0 0 0-2.66-.81A59.9 59.9 0 0 1 12 3.5a59.9 59.9 0 0 1 10.4 5.84c-.9.25-1.78.52-2.66.81m-15.48 0A50.7 50.7 0 0 1 12 13.49a50.7 50.7 0 0 1 7.74-3.34M6.75 15a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm0 0v-3.68A55.4 55.4 0 0 1 12 8.44",
  briefcase:
    "M20.25 14.15v4.25c0 1.1-.79 2.04-1.87 2.18-2.09.28-4.22.42-6.38.42s-4.29-.14-6.38-.42c-1.08-.14-1.87-1.08-1.87-2.18v-4.25m16.5 0a2.18 2.18 0 0 0 .75-1.66V8.71c0-1.08-.77-2.02-1.84-2.18a48 48 0 0 0-3.41-.38m4.5 8a2 2 0 0 1-.67.38A24 24 0 0 1 12 15.75c-2.65 0-5.2-.43-7.58-1.22a2 2 0 0 1-.67-.38m0 0A2.18 2.18 0 0 1 3 12.49V8.71c0-1.08.77-2.02 1.84-2.18a48 48 0 0 1 3.41-.38m7.5 0V5.25A2.25 2.25 0 0 0 13.5 3h-3a2.25 2.25 0 0 0-2.25 2.25v.9m7.5 0a48.7 48.7 0 0 0-7.5 0",
  list: "M3.75 12h16.5m-16.5 3.75h16.5M3.75 19.5h16.5M5.63 4.5h12.74a1.88 1.88 0 0 1 0 3.75H5.63a1.88 1.88 0 0 1 0-3.75Z",
  sparkles:
    "M9.81 15.9 9 18.75l-.81-2.85a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.85-.81a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.81 2.85a4.5 4.5 0 0 0 3.09 3.09l2.85.81-2.85.81a4.5 4.5 0 0 0-3.09 3.09ZM18.26 8.72 18 9.75l-.26-1.03a3.38 3.38 0 0 0-2.45-2.46L14.25 6l1.04-.26a3.38 3.38 0 0 0 2.45-2.46L18 2.25l.26 1.03a3.38 3.38 0 0 0 2.46 2.46L21.75 6l-1.03.26a3.38 3.38 0 0 0-2.46 2.46Z",
  user: "M15.75 6a3.75 3.75 0 1 1-7.5 0 3.75 3.75 0 0 1 7.5 0ZM4.5 20.12a7.5 7.5 0 0 1 15 0A17.9 17.9 0 0 1 12 21.75c-2.68 0-5.22-.58-7.5-1.63Z",
  plus: "M12 4.5v15m7.5-7.5h-15",
  logout:
    "M15.75 9V5.25A2.25 2.25 0 0 0 13.5 3h-6a2.25 2.25 0 0 0-2.25 2.25v13.5A2.25 2.25 0 0 0 7.5 21h6a2.25 2.25 0 0 0 2.25-2.25V15m3 0 3-3m0 0-3-3m3 3H9",
  upload:
    "M3 16.5v2.25A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75V16.5m-13.5-9L12 3m0 0 4.5 4.5M12 3v13.5",
  document:
    "M19.5 14.25v-2.63a3.38 3.38 0 0 0-3.38-3.37h-1.5A1.13 1.13 0 0 1 13.5 7.13v-1.5a3.38 3.38 0 0 0-3.37-3.38H8.25m0 12.75h7.5m-7.5 3H12M10.5 2.25H5.63c-.62 0-1.13.5-1.13 1.13v17.24c0 .63.5 1.13 1.13 1.13h12.74c.63 0 1.13-.5 1.13-1.13V11.25a9 9 0 0 0-9-9Z",
  check: "m4.5 12.75 6 6 9-13.5",
  x: "M6 18 18 6M6 6l12 12",
  clock: "M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z",
  calendar:
    "M6.75 3v2.25M17.25 3v2.25M3 18.75V7.5a2.25 2.25 0 0 1 2.25-2.25h13.5A2.25 2.25 0 0 1 21 7.5v11.25m-18 0A2.25 2.25 0 0 0 5.25 21h13.5A2.25 2.25 0 0 0 21 18.75m-18 0v-7.5A2.25 2.25 0 0 1 5.25 9h13.5A2.25 2.25 0 0 1 21 11.25v7.5",
  refresh:
    "M16.02 9.35h4.99M2.99 19.64v-4.99m0 0h4.99m-4.99 0 3.18 3.18a8.25 8.25 0 0 0 13.8-3.7M4.03 9.87a8.25 8.25 0 0 1 13.8-3.7l3.18 3.18m0-4.99v4.99",
  eye: "M2.04 12.32a1 1 0 0 1 0-.64C3.42 7.51 7.36 4.5 12 4.5c4.64 0 8.57 3.01 9.96 7.18.07.2.07.43 0 .64C20.58 16.49 16.64 19.5 12 19.5c-4.64 0-8.57-3.01-9.96-7.18ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0Z",
  pencil:
    "m16.86 4.49 1.69-1.69a1.88 1.88 0 1 1 2.65 2.65L10.58 16.07a4.5 4.5 0 0 1-1.9 1.13L6 18l.8-2.69a4.5 4.5 0 0 1 1.13-1.9l8.93-8.92Zm0 0L19.5 7.13M18 14v4.75A2.25 2.25 0 0 1 15.75 21H5.25A2.25 2.25 0 0 1 3 18.75V8.25A2.25 2.25 0 0 1 5.25 6H10",
  trash:
    "m14.74 9-.35 9m-4.78 0L9.26 9m9.97-3.21c.34.05.68.11 1.02.17m-1.02-.17L18.16 19.67a2.25 2.25 0 0 1-2.24 2.08H8.08a2.25 2.25 0 0 1-2.24-2.08L4.77 5.79m14.46 0a48 48 0 0 0-3.48-.4m-12 .57c.34-.06.68-.12 1.02-.17m0 0a48 48 0 0 1 3.48-.4m7.5 0v-.92c0-1.18-.91-2.16-2.09-2.2a52 52 0 0 0-3.32 0c-1.18.04-2.09 1.02-2.09 2.2v.92m7.5 0a48.7 48.7 0 0 0-7.5 0",
  warning:
    "M12 9v3.75m-9.3 3.38c-.87 1.5.21 3.37 1.95 3.37h14.7c1.74 0 2.82-1.87 1.95-3.37L13.95 3.38c-.87-1.5-3.03-1.5-3.9 0L2.7 16.13ZM12 15.75h.01v.01H12v-.01Z",
  bulb: "M12 18v-5.25m0 0a6 6 0 0 0 1.5-.19m-1.5.19a6 6 0 0 1-1.5-.19m3.75 7.48a12 12 0 0 1-4.5 0m3.75 2.38a14.4 14.4 0 0 1-3 0M14.25 18v-.19c0-.98.66-1.82 1.51-2.32a7.5 7.5 0 1 0-7.52 0c.85.5 1.51 1.34 1.51 2.32V18",
  trend: "M2.25 18 9 11.25l4.31 4.31a12 12 0 0 1 5.81-5.52l2.74-1.22m0 0-5.94-2.28m5.94 2.28-2.28 5.94",
  mail: "M21.75 6.75v10.5a2.25 2.25 0 0 1-2.25 2.25h-15a2.25 2.25 0 0 1-2.25-2.25V6.75m19.5 0A2.25 2.25 0 0 0 19.5 4.5h-15a2.25 2.25 0 0 0-2.25 2.25m19.5 0v.24a2.25 2.25 0 0 1-1.07 1.92l-7.5 4.61a2.25 2.25 0 0 1-2.36 0L3.32 8.91a2.25 2.25 0 0 1-1.07-1.92v-.24",
  lock: "M16.5 10.5V6.75a4.5 4.5 0 1 0-9 0v3.75m-.75 11.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-6.75a2.25 2.25 0 0 0-2.25-2.25H6.75a2.25 2.25 0 0 0-2.25 2.25v6.75a2.25 2.25 0 0 0 2.25 2.25Z",
  arrowRight: "M13.5 4.5 21 12m0 0-7.5 7.5M21 12H3",
  chat: "M20.25 8.51c.88.28 1.5 1.13 1.5 2.1v4.28c0 1.14-.85 2.1-1.98 2.2-.34.02-.68.05-1.02.07v3.09l-3-3c-1.35 0-2.69-.06-4.02-.17a2.1 2.1 0 0 1-.82-.24m9.34-8.33a2 2 0 0 0-.48-.1 48.6 48.6 0 0 0-8.04 0c-1.13.1-1.98 1.06-1.98 2.2v4.28c0 .84.46 1.58 1.16 1.95m9.34-8.33V6.64c0-1.62-1.15-3.03-2.76-3.24A48.5 48.5 0 0 0 11.25 3c-2.12 0-4.2.14-6.24.4-1.61.21-2.76 1.62-2.76 3.24v6.22c0 1.62 1.15 3.03 2.76 3.24.58.07 1.16.14 1.74.19V21l4.16-4.16",
  search: "m21 21-5.2-5.2m0 0A7.5 7.5 0 1 0 5.2 5.2a7.5 7.5 0 0 0 10.6 10.6Z",
  home: "m2.25 12 8.95-8.96c.44-.43 1.15-.43 1.6 0L21.75 12M4.5 9.75v10.13c0 .62.5 1.12 1.13 1.12H9.75v-4.88c0-.62.5-1.12 1.13-1.12h2.25c.62 0 1.12.5 1.12 1.12V21h4.13c.62 0 1.12-.5 1.12-1.12V9.75M8.25 21h8.25",
  send: "M6 12 3.27 3.13A59.8 59.8 0 0 1 21.49 12 59.8 59.8 0 0 1 3.27 20.88L6 12Zm0 0h7.5",
  inbox:
    "M2.25 13.5h3.86a2.25 2.25 0 0 1 2.01 1.24l.27.54a2.25 2.25 0 0 0 2.01 1.24h3.2a2.25 2.25 0 0 0 2.01-1.24l.27-.54a2.25 2.25 0 0 1 2.01-1.24h3.86M2.25 13.5V18a2.25 2.25 0 0 0 2.25 2.25h15A2.25 2.25 0 0 0 21.75 18v-4.5m-19.5 0 2.39-7.15A2.25 2.25 0 0 1 6.78 4.8h10.44a2.25 2.25 0 0 1 2.14 1.55l2.39 7.15",
} as const;

export type IconName = keyof typeof ICON_PATHS;

/**
 * aria-hidden because every icon here sits next to words that already say the same
 * thing. Leaving it visible to screen readers would read the button out twice - and a
 * test looking a button up by its name would find "Drives" rather than an icon's title.
 */
export function Icon({ name, className = "size-4" }: { name: IconName; className?: string }) {
  return (
    <svg
      aria-hidden="true"
      focusable="false"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={`shrink-0 ${className}`}
    >
      <path d={ICON_PATHS[name]} />
    </svg>
  );
}

/** Every company gets one of these, chosen from its name so it stays the same each visit. */
const AVATAR_GRADIENTS = [
  "from-rose-500 to-orange-400",
  "from-violet-600 to-indigo-500",
  "from-emerald-500 to-teal-400",
  "from-sky-500 to-cyan-400",
  "from-fuchsia-500 to-pink-400",
  "from-amber-500 to-yellow-400",
  "from-indigo-500 to-sky-400",
];

export function CompanyAvatar({ name, size = "md" }: { name: string; size?: "sm" | "md" | "lg" }) {
  let hash = 0;
  for (const character of name) {
    hash = (hash * 31 + character.charCodeAt(0)) | 0;
  }
  const gradient = AVATAR_GRADIENTS[Math.abs(hash) % AVATAR_GRADIENTS.length];
  const dimensions = {
    sm: "size-9 rounded-xl text-sm",
    md: "size-12 rounded-2xl text-lg",
    lg: "size-16 rounded-2xl text-2xl",
  }[size];

  return (
    <span
      aria-hidden="true"
      className={`grid shrink-0 place-items-center bg-linear-to-br font-bold text-white shadow-lg shadow-slate-900/10 ${gradient} ${dimensions}`}
    >
      {name.trim().charAt(0).toUpperCase() || "?"}
    </span>
  );
}

/** The heading block at the top of every signed-in page. */
export function PageHero({
  eyebrow,
  title,
  subtitle,
  children,
}: {
  eyebrow?: string;
  title: string;
  subtitle?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <section className="animate-fade-up flex flex-col gap-5 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        {eyebrow && (
          <p className="chip bg-white/80 text-violet-700 shadow-sm ring-1 ring-violet-100">
            <span className="chip-dot text-fuchsia-500" />
            {eyebrow}
          </p>
        )}
        <h1 className="mt-3 text-3xl font-extrabold tracking-tight text-slate-900 sm:text-4xl">{title}</h1>
        {subtitle && <p className="mt-2 max-w-2xl text-slate-600">{subtitle}</p>}
      </div>
      {children && <div className="flex shrink-0 flex-wrap gap-2">{children}</div>}
    </section>
  );
}

/** A big friendly message for "nothing here yet", optionally with a way forward. */
export function EmptyState({
  icon,
  title,
  children,
  action,
}: {
  icon: IconName;
  title: string;
  children?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="card animate-fade-up flex flex-col items-center px-6 py-12 text-center">
      <span className="grid size-16 place-items-center rounded-2xl bg-linear-to-br from-indigo-100 via-violet-100 to-fuchsia-100 text-violet-600">
        <Icon name={icon} className="size-8" />
      </span>
      <p className="mt-4 text-lg font-semibold text-slate-800">{title}</p>
      {children && <div className="mt-1 max-w-md text-sm text-slate-500">{children}</div>}
      {action && <div className="mt-5">{action}</div>}
    </div>
  );
}

/** Placeholder cards shown while a list loads, so the page does not jump when it arrives. */
export function SkeletonCards({ count = 3 }: { count?: number }) {
  return (
    <div aria-hidden="true" className="grid gap-5 md:grid-cols-2">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className="card p-5">
          <div className="flex items-center gap-4">
            <div className="skeleton size-12 rounded-2xl" />
            <div className="flex-1 space-y-2">
              <div className="skeleton h-4 w-2/3" />
              <div className="skeleton h-3 w-1/3" />
            </div>
          </div>
          <div className="skeleton mt-5 h-3 w-full" />
          <div className="skeleton mt-2 h-3 w-4/5" />
          <div className="skeleton mt-5 h-8 w-28 rounded-xl" />
        </div>
      ))}
    </div>
  );
}

/** A spinning ring, for buttons and small waits. */
export function Spinner({ className = "size-4" }: { className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={`inline-block animate-spin rounded-full border-2 border-current border-r-transparent ${className}`}
    />
  );
}

/** One coloured number with a label, for the summary rows above the lists. */
export function StatTile({
  icon,
  label,
  value,
  tone,
}: {
  icon: IconName;
  label: string;
  value: string | number;
  tone: string;
}) {
  return (
    <div className="card flex items-center gap-4 p-4">
      <span className={`grid size-11 place-items-center rounded-xl bg-linear-to-br text-white shadow-md ${tone}`}>
        <Icon name={icon} className="size-5" />
      </span>
      <div>
        <p className="text-2xl font-bold text-slate-900">{value}</p>
        <p className="text-xs font-medium tracking-wide text-slate-500 uppercase">{label}</p>
      </div>
    </div>
  );
}
