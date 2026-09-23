import "@testing-library/jest-dom/vitest";

import { cleanup } from "@testing-library/react";
import { afterEach, beforeEach, vi } from "vitest";

/**
 * Runs before every test file.
 *
 * Two things are reset between tests rather than trusted to be clean: the rendered DOM,
 * and localStorage. The token lives in localStorage, so a test that signs in would
 * otherwise leak a session into the next one and make it pass for the wrong reason.
 */
beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
