import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as applicationsApi from "../api/applications";
import * as authApi from "../api/auth";
import * as drivesApi from "../api/drives";
import { renderPage } from "../test/render";
import { application, coordinator, drive, personInCharge, student } from "../test/fixtures";
import Drives from "./Drives";

vi.mock("../api/auth");
vi.mock("../api/drives");
vi.mock("../api/applications");

/**
 * The drives board, by role.
 *
 * These exist because of a real bug: the page once collapsed "is this account
 * management?" and "is this account a student?" into a single isStaff flag, which handed
 * coordinators the management half and silently took the student half away - no Apply
 * button, no eligibility tab, no sight of their own applications. Every assertion about
 * the coordinator below is there to keep that from coming back.
 */
describe("Drives", () => {
  beforeEach(() => {
    vi.mocked(drivesApi.fetchAllDrives).mockResolvedValue([drive()]);
    vi.mocked(drivesApi.fetchEligibleDrives).mockResolvedValue([drive()]);
    vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([]);
  });

  describe("a student", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
    });

    it("can apply, and sees the eligibility tabs", async () => {
      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Apply" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Eligible for me" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "All drives" })).toBeInTheDocument();
    });

    it("gets none of the management controls", async () => {
      renderPage(<Drives />);
      await screen.findByRole("button", { name: "Apply" });

      expect(screen.queryByRole("button", { name: "Post a drive" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Delete" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    });

    it("shows a status badge instead of Apply once applied", async () => {
      vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([
        application({ driveId: 10, status: "SHORTLISTED" }),
      ]);

      renderPage(<Drives />);

      expect(await screen.findByText("shortlisted")).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Apply" })).not.toBeInTheDocument();
    });

    it("offers Apply again after a withdrawal, since the backend reopens that row", async () => {
      vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([
        application({ driveId: 10, status: "WITHDRAWN" }),
      ]);

      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Apply" })).toBeInTheDocument();
    });

    it("prompts for a profile without calling the eligibility endpoint", async () => {
      // An incomplete profile is knowable from the profile response itself, so the page
      // must not fire a request it already knows will be refused with a 409.
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student({ complete: false }));

      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Complete profile" })).toBeInTheDocument();
      expect(drivesApi.fetchEligibleDrives).not.toHaveBeenCalled();
    });
  });

  describe("a coordinator", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(coordinator());
    });

    it("keeps every student ability", async () => {
      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Apply" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Eligible for me" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "My applications" })).toBeInTheDocument();
      expect(applicationsApi.fetchMyApplications).toHaveBeenCalled();
    });

    it("also gets the management controls, on the same card", async () => {
      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Apply" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Delete" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Edit" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Post a drive" })).toBeInTheDocument();
    });

    it("actually applies when Apply is clicked", async () => {
      vi.mocked(applicationsApi.applyToDrive).mockResolvedValue(application({ driveId: 10 }));

      renderPage(<Drives />);
      await userEvent.click(await screen.findByRole("button", { name: "Apply" }));

      await waitFor(() => expect(applicationsApi.applyToDrive).toHaveBeenCalledWith(10));
      expect(await screen.findByText("Applied")).toBeInTheDocument();
    });
  });

  describe("the person in charge", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());
    });

    it("manages the board but is not a student", async () => {
      renderPage(<Drives />);

      expect(await screen.findByRole("button", { name: "Delete" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Post a drive" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Apply" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Eligible for me" })).not.toBeInTheDocument();
    });

    it("never asks for applications or eligibility, having neither", async () => {
      renderPage(<Drives />);
      await screen.findByRole("button", { name: "Delete" });

      expect(applicationsApi.fetchMyApplications).not.toHaveBeenCalled();
      expect(drivesApi.fetchEligibleDrives).not.toHaveBeenCalled();
      expect(drivesApi.fetchAllDrives).toHaveBeenCalled();
    });
  });
});
