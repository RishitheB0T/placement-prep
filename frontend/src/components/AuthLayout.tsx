import type { ReactNode } from "react";

import { Icon, type IconName } from "./ui";

const FEATURES: { icon: IconName; title: string; text: string }[] = [
  { icon: "briefcase", title: "Drives that fit you", text: "Only the openings your marks qualify for." },
  { icon: "list", title: "Every application tracked", text: "From applied to selected, in one place." },
  { icon: "sparkles", title: "AI resume feedback", text: "A score and fixes, against the real job description." },
];

/**
 * The two-panel frame the sign-in and registration pages share: a colourful welcome on
 * the left, the form on the right. On a phone the welcome panel gives way to a compact
 * brand line above the form, so the form is the first thing on screen.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden p-4 sm:p-8">
      {/* Slow-drifting colour behind everything - decoration only. */}
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <div className="animate-float absolute -top-24 -left-24 size-96 rounded-full bg-violet-300/40 blur-3xl" />
        <div className="animate-float absolute top-1/3 -right-24 size-96 rounded-full bg-fuchsia-300/40 blur-3xl [animation-delay:-5s]" />
        <div className="animate-float absolute -bottom-32 left-1/3 size-96 rounded-full bg-sky-300/40 blur-3xl [animation-delay:-9s]" />
      </div>

      <div className="card animate-fade-up relative grid w-full max-w-5xl overflow-hidden rounded-3xl lg:grid-cols-2">
        <section className="relative hidden flex-col justify-between overflow-hidden bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-500 p-10 text-white lg:flex">
          <div aria-hidden="true" className="absolute -top-16 -right-16 size-64 rounded-full bg-white/10" />
          <div aria-hidden="true" className="absolute -bottom-20 -left-10 size-72 rounded-full bg-white/10" />

          <div className="relative flex items-center gap-2.5">
            <span className="grid size-10 place-items-center rounded-xl bg-white/20 ring-1 ring-white/30 backdrop-blur">
              <Icon name="cap" className="size-6" />
            </span>
            <span className="text-xl font-extrabold tracking-tight">Placement Prep</span>
          </div>

          <div className="relative">
            <p className="text-4xl leading-tight font-extrabold">Your placement season, organised.</p>
            <p className="mt-3 text-white/80">
              One place for drives, applications and interview-ready resumes.
            </p>

            <ul className="mt-8 space-y-4">
              {FEATURES.map((feature) => (
                <li key={feature.title} className="flex items-start gap-3">
                  <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/15 ring-1 ring-white/25">
                    <Icon name={feature.icon} className="size-5" />
                  </span>
                  <div>
                    <p className="font-semibold">{feature.title}</p>
                    <p className="text-sm text-white/75">{feature.text}</p>
                  </div>
                </li>
              ))}
            </ul>
          </div>

          <p className="relative text-xs text-white/60">For students and the training and placement cell.</p>
        </section>

        <div className="p-6 sm:p-10">
          <div className="mb-6 flex items-center gap-2.5 lg:hidden">
            <span className="grid size-9 place-items-center rounded-xl bg-linear-to-br from-indigo-600 via-violet-600 to-fuchsia-500 text-white shadow-lg shadow-violet-500/30">
              <Icon name="cap" className="size-5" />
            </span>
            <span className="text-lg font-extrabold tracking-tight text-slate-900">
              <span className="gradient-text">Placement</span> Prep
            </span>
          </div>
          {children}
        </div>
      </div>
    </main>
  );
}
