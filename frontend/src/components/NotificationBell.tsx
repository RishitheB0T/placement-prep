import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";

import { fetchMyNotifications, markAllNotificationsRead, markNotificationRead } from "../api/notifications";
import type { Notification, NotificationKind } from "../api/types";
import { Icon, type IconName } from "./ui";

const KIND_ICON: Record<NotificationKind, IconName> = {
  NEW_DRIVE: "briefcase",
  DEADLINE_3_DAYS: "clock",
  DEADLINE_1_DAY: "warning",
  STATUS_CHANGED: "list",
};

const KIND_TONE: Record<NotificationKind, string> = {
  NEW_DRIVE: "from-indigo-500 to-violet-500",
  DEADLINE_3_DAYS: "from-amber-500 to-orange-500",
  DEADLINE_1_DAY: "from-rose-500 to-pink-500",
  STATUS_CHANGED: "from-emerald-500 to-teal-500",
};

/**
 * The student's notification bell: new drives, deadline reminders and application
 * updates, with a badge counting the unread ones.
 *
 * Loads once when the header mounts - every page renders the header, so moving between
 * pages is what refreshes it; there is no polling. Errors are swallowed on purpose: a
 * bell that failed to load must never take the page around it down with it.
 */
export default function NotificationBell() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Notification[]>([]);
  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMyNotifications()
      .then((list) => {
        if (!cancelled) setItems(list);
      })
      .catch(() => {
        // Leave the bell empty; see the comment above.
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Close on a click outside the panel, or on Escape - the two ways people expect a
  // dropdown to go away.
  useEffect(() => {
    if (!open) return;
    function onClick(event: MouseEvent) {
      if (panelRef.current && !panelRef.current.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  const unread = items.filter((item) => !item.read).length;

  async function openItem(item: Notification) {
    if (!item.read) {
      try {
        const updated = await markNotificationRead(item.id);
        setItems((all) => all.map((n) => (n.id === updated.id ? updated : n)));
      } catch {
        // Still take them where the notification points.
      }
    }
    setOpen(false);
    navigate(item.kind === "STATUS_CHANGED" ? "/applications" : "/drives");
  }

  async function markAll() {
    try {
      await markAllNotificationsRead();
      setItems((all) => all.map((n) => ({ ...n, read: true })));
    } catch {
      // Nothing changed; the badge keeps showing the truth.
    }
  }

  return (
    <div ref={panelRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-label={unread > 0 ? `Notifications, ${unread} unread` : "Notifications"}
        aria-expanded={open}
        className="btn btn-sm btn-ghost relative"
      >
        <Icon name="bell" className="size-5" />
        {unread > 0 && (
          <span
            aria-hidden="true"
            className="absolute -top-0.5 -right-0.5 grid min-w-5 place-items-center rounded-full bg-rose-500 px-1 text-[10px] font-bold text-white shadow"
          >
            {unread > 9 ? "9+" : unread}
          </span>
        )}
      </button>

      {open && (
        <div
          role="dialog"
          aria-label="Notifications"
          // bg-white over the card's translucent white: a dropdown floats over page text,
          // which must not show through it.
          className="card animate-pop absolute right-0 z-40 mt-2 w-[min(22rem,calc(100vw-2rem))] overflow-hidden bg-white p-0 shadow-xl"
        >
          <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
            <p className="font-bold text-slate-900">Notifications</p>
            {unread > 0 && (
              <button type="button" onClick={() => void markAll()} className="text-xs font-semibold text-violet-600 hover:underline">
                Mark all read
              </button>
            )}
          </div>

          {items.length === 0 ? (
            <p className="px-4 py-6 text-center text-sm text-slate-500">
              Nothing yet. New drives you are eligible for, deadline reminders and application updates will show up here.
            </p>
          ) : (
            <ul className="max-h-96 overflow-y-auto">
              {items.map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => void openItem(item)}
                    className={[
                      "flex w-full items-start gap-3 px-4 py-3 text-left transition hover:bg-slate-50",
                      item.read ? "" : "bg-violet-50/60",
                    ].join(" ")}
                  >
                    <span
                      className={`mt-0.5 grid size-8 shrink-0 place-items-center rounded-xl bg-linear-to-br text-white ${KIND_TONE[item.kind]}`}
                    >
                      <Icon name={KIND_ICON[item.kind]} className="size-4" />
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className={`block text-sm ${item.read ? "text-slate-600" : "font-semibold text-slate-900"}`}>
                        {item.message}
                      </span>
                      <span className="mt-0.5 block text-xs text-slate-400">
                        {new Date(item.createdAt).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "numeric", minute: "2-digit" })}
                      </span>
                    </span>
                    {!item.read && <span aria-label="Unread" className="mt-2 size-2 shrink-0 rounded-full bg-violet-500" />}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
