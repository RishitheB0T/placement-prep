import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

/**
 * Test config kept separate from vite.config.ts on purpose.
 *
 * The dev config carries a proxy to localhost:8081, and tests must never depend on a
 * backend being up - every request in them is stubbed. Keeping the two files apart means
 * a test can never quietly start passing because a real server happened to be running.
 *
 * Tailwind is left out for the same reason: these tests assert on roles and text, never
 * on how anything looks, so compiling CSS would only slow the run down.
 */
export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
  },
});
