import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as authApi from "../api/auth";
import { renderPageWithRoutes } from "../test/render";
import Login from "./Login";

vi.mock("../api/auth");

/**
 * The two doors.
 *
 * There is one login endpoint; the tabs differ only in what the page does with the
 * answer. Only Administration is enforced, and only against TNP_PIC - the Student tab is
 * the original unrestricted form, because a coordinator was never given a door of their
 * own and locking them out of the one they used would be a regression.
 */
describe("Login", () => {
  const renderLogin = () =>
    renderPageWithRoutes(<Login />, {
      route: "/login",
      path: "/login",
      destinations: { "/drives": "drives board" },
    });

  const signIn = async (tab?: "Student" | "Administration") => {
    if (tab) await userEvent.click(screen.getByRole("button", { name: tab }));
    await userEvent.type(screen.getByLabelText(/email/i), "someone@cse.nits.ac.in");
    await userEvent.type(screen.getByLabelText(/password/i), "a-password");
    await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  };

  const loginResolvesAs = (role: "STUDENT" | "TNP_COORDINATOR" | "TNP_PIC") =>
    vi.mocked(authApi.login).mockResolvedValue({
      token: "a.b.c",
      email: "someone@cse.nits.ac.in",
      role,
      expiresAt: Date.now() + 86_400_000,
    });

  beforeEach(() => {
    vi.mocked(authApi.logout).mockImplementation(() => {});
  });

  describe("the Student tab", () => {
    it("lets a student through", async () => {
      loginResolvesAs("STUDENT");
      renderLogin();
      await signIn();

      expect(await screen.findByText("drives board")).toBeInTheDocument();
    });

    it("lets a coordinator through, since they have no door of their own", async () => {
      loginResolvesAs("TNP_COORDINATOR");
      renderLogin();
      await signIn();

      expect(await screen.findByText("drives board")).toBeInTheDocument();
    });

    it("lets the PIC through too - only the other tab is restricted", async () => {
      loginResolvesAs("TNP_PIC");
      renderLogin();
      await signIn();

      expect(await screen.findByText("drives board")).toBeInTheDocument();
    });
  });

  describe("the Administration tab", () => {
    it("lets the person in charge through", async () => {
      loginResolvesAs("TNP_PIC");
      renderLogin();
      await signIn("Administration");

      expect(await screen.findByText("drives board")).toBeInTheDocument();
    });

    it("turns a student away even though the password was right", async () => {
      loginResolvesAs("STUDENT");
      renderLogin();
      await signIn("Administration");

      expect(await screen.findByRole("alert")).toHaveTextContent(/not an administration account/i);
      expect(screen.queryByText("drives board")).not.toBeInTheDocument();
    });

    it("turns a coordinator away as well - this is the PIC's door, not the cell's", async () => {
      loginResolvesAs("TNP_COORDINATOR");
      renderLogin();
      await signIn("Administration");

      expect(await screen.findByRole("alert")).toHaveTextContent(/not an administration account/i);
    });

    it("throws the token away when it refuses, so the next page load is not signed in", async () => {
      loginResolvesAs("STUDENT");
      renderLogin();
      await signIn("Administration");

      await screen.findByRole("alert");
      expect(authApi.logout).toHaveBeenCalled();
    });
  });
});
