import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";

import * as notificationsApi from "../api/notifications";

/**
 * Runs before every test file.
 *
 * Two things are reset between tests rather than trusted to be clean: the rendered DOM,
 * and localStorage. The token lives in localStorage, so a test that signs in would
 * otherwise leak a session into the next one and make it pass for the wrong reason.
 *
 * The notifications API is mocked for every file. Every page renders AppHeader, and a
 * student's header holds the NotificationBell, which loads on mount - unmocked, it would
 * send a real request from jsdom's localhost:3000 page. Each test starts with an empty
 * bell; a test about the bell sets its own list after this runs.
 */
vi.mock("../api/notifications");

beforeEach(() => {
  localStorage.clear();
  vi.mocked(notificationsApi.fetchMyNotifications).mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
