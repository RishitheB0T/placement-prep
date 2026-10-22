import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as authApi from "../api/auth";
import { ApiError } from "../api/client";
import { renderPage } from "../test/render";
import { personInCharge, recruiter, student } from "../test/fixtures";
import Profile from "./Profile";

vi.mock("../api/auth");

/** The profile page by role, and the change-password card every role gets. */
describe("Profile", () => {
  beforeEach(() => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
  });

  const fillPasswords = async (current: string, next: string, confirm: string) => {
    await userEvent.type(await screen.findByLabelText("Current password"), current);
    await userEvent.type(screen.getByLabelText(/^New password/), next);
    await userEvent.type(screen.getByLabelText("Confirm new password"), confirm);
    await userEvent.click(screen.getByRole("button", { name: "Change password" }));
  };

  it("shows a student their academic form", async () => {
    renderPage(<Profile />);

    expect(await screen.findByLabelText("CGPA")).toBeInTheDocument();
  });

  it("shows a recruiter a note instead of a form, plus the password card", async () => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(recruiter());

    renderPage(<Profile />);

    expect(await screen.findByText("Nothing to fill in")).toBeInTheDocument();
    expect(screen.queryByLabelText("CGPA")).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Change password" })).toBeInTheDocument();
  });

  it("gives the admin the staff form", async () => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());

    renderPage(<Profile />);

    expect(await screen.findByLabelText("Designation")).toBeInTheDocument();
  });

  it("changes the password", async () => {
    vi.mocked(authApi.changeMyPassword).mockResolvedValue(undefined);

    renderPage(<Profile />);
    await fillPasswords("old-password", "a-new-password", "a-new-password");

    expect(authApi.changeMyPassword).toHaveBeenCalledWith("old-password", "a-new-password");
    expect(await screen.findByText(/Password changed/)).toBeInTheDocument();
  });

  it("catches a mistyped confirmation before sending anything", async () => {
    renderPage(<Profile />);
    await fillPasswords("old-password", "a-new-password", "a-different-one");

    expect(await screen.findByRole("alert")).toHaveTextContent("do not match");
    expect(authApi.changeMyPassword).not.toHaveBeenCalled();
  });

  it("shows the server's answer when the current password is wrong", async () => {
    vi.mocked(authApi.changeMyPassword).mockRejectedValue(new ApiError(400, "Your current password is incorrect"));

    renderPage(<Profile />);
    await fillPasswords("a-wrong-guess", "a-new-password", "a-new-password");

    expect(await screen.findByRole("alert")).toHaveTextContent("Your current password is incorrect");
  });
});
