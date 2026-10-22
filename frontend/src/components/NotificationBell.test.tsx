import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as notificationsApi from "../api/notifications";
import type { Notification } from "../api/types";
import { renderPageWithRoutes } from "../test/render";
import NotificationBell from "./NotificationBell";

// The notifications API is already mocked for every test file, in src/test/setup.ts.

const notification = (overrides: Partial<Notification> = {}): Notification => ({
  id: 1,
  kind: "DEADLINE_1_DAY",
  message: "Last day to apply: Zoho - SDE closes on 30 Jun 2099, 11:30 pm.",
  driveId: 10,
  createdAt: "2026-10-01T06:00:00Z",
  read: false,
  ...overrides,
});

const renderBell = () =>
  renderPageWithRoutes(<NotificationBell />, {
    route: "/dashboard",
    path: "/dashboard",
    destinations: { "/drives": "drives page", "/applications": "applications page" },
  });

/** The student's bell: unread count, the list, and marking things read. */
describe("NotificationBell", () => {
  beforeEach(() => {
    vi.mocked(notificationsApi.fetchMyNotifications).mockResolvedValue([
      notification(),
      notification({ id: 2, kind: "STATUS_CHANGED", message: "You have been shortlisted for Infosys.", read: true }),
    ]);
  });

  it("counts only the unread notifications", async () => {
    renderBell();

    expect(await screen.findByRole("button", { name: "Notifications, 1 unread" })).toBeInTheDocument();
  });

  it("lists every notification when opened", async () => {
    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: /Notifications/ }));

    expect(screen.getByText(/Last day to apply: Zoho/)).toBeInTheDocument();
    expect(screen.getByText("You have been shortlisted for Infosys.")).toBeInTheDocument();
  });

  it("marks a notification read and goes where it points", async () => {
    vi.mocked(notificationsApi.markNotificationRead).mockResolvedValue(notification({ read: true }));

    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: /Notifications/ }));
    await userEvent.click(screen.getByText(/Last day to apply: Zoho/));

    expect(notificationsApi.markNotificationRead).toHaveBeenCalledWith(1);
    // A deadline reminder is about a drive, so it opens the drives board.
    expect(await screen.findByText("drives page")).toBeInTheDocument();
  });

  it("marks everything read at once", async () => {
    vi.mocked(notificationsApi.markAllNotificationsRead).mockResolvedValue(undefined);

    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: "Notifications, 1 unread" }));
    await userEvent.click(screen.getByRole("button", { name: "Mark all read" }));

    await waitFor(() => expect(screen.getByRole("button", { name: "Notifications" })).toBeInTheDocument());
  });

  it("says so when there is nothing yet", async () => {
    vi.mocked(notificationsApi.fetchMyNotifications).mockResolvedValue([]);

    renderBell();
    await userEvent.click(await screen.findByRole("button", { name: "Notifications" }));

    expect(screen.getByText(/Nothing yet/)).toBeInTheDocument();
  });
});
