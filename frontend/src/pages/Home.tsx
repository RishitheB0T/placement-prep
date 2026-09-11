import { useEffect, useState } from "react";
import { fetchBackendHealth, type HealthStatus } from "../api/health";

export default function Home() {
  const [status, setStatus] = useState<HealthStatus | "LOADING">("LOADING");

  useEffect(() => {
    // `cancelled` stops us calling setStatus after the component has gone away,
    // which React would warn about.
    let cancelled = false;
    fetchBackendHealth().then((result) => {
      if (!cancelled) {
        setStatus(result);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const isUp = status === "UP";
  const label =
    status === "LOADING" ? "Backend: checking..." : `Backend: ${status}`;

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-6 bg-slate-50 p-6">
      <div className="text-center">
        <h1 className="text-3xl font-semibold text-slate-900">Placement Prep</h1>
        <p className="mt-2 text-slate-600">Campus placement preparation platform</p>
      </div>

      <div
        className={[
          "flex items-center gap-3 rounded-lg border px-5 py-3 text-lg font-medium",
          status === "LOADING"
            ? "border-slate-300 bg-white text-slate-500"
            : isUp
              ? "border-emerald-300 bg-emerald-50 text-emerald-700"
              : "border-red-300 bg-red-50 text-red-700",
        ].join(" ")}
      >
        <span
          className={[
            "size-3 rounded-full",
            status === "LOADING"
              ? "bg-slate-400"
              : isUp
                ? "bg-emerald-500"
                : "bg-red-500",
          ].join(" ")}
        />
        {label}
      </div>
    </main>
  );
}
