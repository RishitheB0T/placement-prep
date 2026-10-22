import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as applicationsApi from "../api/applications";
import * as authApi from "../api/auth";
import * as drivesApi from "../api/drives";
import * as resumesApi from "../api/resumes";
import type { ApplicationStats } from "../api/types";
import { renderPage } from "../test/render";
import { application, coordinator, drive, personInCharge, recruiter, student } from "../test/fixtures";
import Dashboard from "./Dashboard";

vi.mock("../api/auth");
vi.mock("../api/drives");
vi.mock("../api/applications");
vi.mock("../api/resumes");

const STATS: ApplicationStats = {
  byStatus: { APPLIED: 1, SHORTLISTED: 2, SELECTED: 0, REJECTED: 0, WITHDRAWN: 0 },
  awaitingReviewByDrive: { "10": 1 },
};

/**
 * One route, a dashboard per role. Each test checks a role gets its own view - and does not
 * ask the backend for things that role is not allowed to see.
 */
describe("Dashboard", () => {
  beforeEach(() => {
    vi.mocked(drivesApi.fetchAllDrives).mockResolvedValue([drive()]);
    vi.mocked(drivesApi.fetchEligibleDrives).mockResolvedValue([drive()]);
    vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([]);
    vi.mocked(applicationsApi.fetchApplicationStats).mockResolvedValue(STATS);
    vi.mocked(resumesApi.fetchMyResumes).mockResolvedValue([]);
    vi.mocked(authApi.fetchUsers).mockResolvedValue([]);
  });

  describe("a student", () => {
    it("sees their own journey: drives to apply to and their applications", async () => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
      vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([application({ status: "SHORTLISTED" })]);

      renderPage(<Dashboard />);

      expect(await screen.findByRole("heading", { name: "Welcome back, Asha" })).toBeInTheDocument();
      expect(await screen.findByText("Closing soon for you")).toBeInTheDocument();
      expect(screen.getByText("shortlisted")).toBeInTheDocument();
      // The cell's numbers and the account list are not a student's to see.
      expect(applicationsApi.fetchApplicationStats).not.toHaveBeenCalled();
      expect(authApi.fetchUsers).not.toHaveBeenCalled();
    });

    it("is pointed at the profile form before eligibility can be checked", async () => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student({ cgpa: null, complete: false }));

      renderPage(<Dashboard />);

      expect(await screen.findByRole("button", { name: "Complete profile" })).toBeInTheDocument();
      expect(drivesApi.fetchEligibleDrives).not.toHaveBeenCalled();
    });
  });

  describe("a coordinator", () => {
    it("sees what is waiting for review, and their own placement as a student", async () => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(coordinator());

      renderPage(<Dashboard />);

      expect(await screen.findByText("Coordinator dashboard")).toBeInTheDocument();
      expect(await screen.findByText("1 new")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Review applicants" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Post a new drive" })).toBeInTheDocument();
      expect(screen.getByText("Your own placement")).toBeInTheDocument();
      expect(applicationsApi.fetchMyApplications).toHaveBeenCalled();
      // Promoting people is the admin's, not the coordinator's.
      expect(authApi.fetchUsers).not.toHaveBeenCalled();
    });
  });

  describe("a recruiter", () => {
    it("sees what is waiting for review, and nothing of a student's", async () => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(recruiter());

      renderPage(<Dashboard />);

      expect(await screen.findByText("Recruiter dashboard")).toBeInTheDocument();
      expect(await screen.findByText("1 new")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Post a new drive" })).toBeInTheDocument();
      // Not a student: no own placement, and no request the backend would answer 403.
      expect(screen.queryByText("Your own placement")).not.toBeInTheDocument();
      expect(applicationsApi.fetchMyApplications).not.toHaveBeenCalled();
      expect(authApi.fetchUsers).not.toHaveBeenCalled();
    });
  });

  describe("the admin", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());
      vi.mocked(authApi.fetchUsers).mockResolvedValue([student({ id: 7, email: "rahul@cse.nits.ac.in" })]);
    });

    it("sees the whole cell and the people in it", async () => {
      renderPage(<Dashboard />);

      expect(await screen.findByRole("heading", { name: "Placement cell overview" })).toBeInTheDocument();
      expect(await screen.findByText("rahul@cse.nits.ac.in")).toBeInTheDocument();
      expect(screen.getByText("Needs your review")).toBeInTheDocument();
    });

    it("can make a student a coordinator, after confirming", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(true);
      vi.mocked(authApi.promoteToCoordinator).mockResolvedValue(
        coordinator({ id: 7, email: "rahul@cse.nits.ac.in" }),
      );
      renderPage(<Dashboard />);

      await userEvent.click(await screen.findByRole("button", { name: "Make coordinator" }));

      expect(authApi.promoteToCoordinator).toHaveBeenCalledWith(7);
      expect(await screen.findByText(/is now a coordinator/)).toBeInTheDocument();
      // Now a coordinator, so they leave the Students list - nobody left to promote.
      expect(screen.queryByRole("button", { name: "Make coordinator" })).not.toBeInTheDocument();
    });

    it("promotes nobody when the confirmation is cancelled", async () => {
      vi.spyOn(window, "confirm").mockReturnValue(false);
      renderPage(<Dashboard />);

      await userEvent.click(await screen.findByRole("button", { name: "Make coordinator" }));

      expect(authApi.promoteToCoordinator).not.toHaveBeenCalled();
    });

    /** The "forgot password" path: the admin sets a temporary one and hands it over. */
    it("can reset someone's password", async () => {
      vi.mocked(authApi.resetPassword).mockResolvedValue(undefined);
      renderPage(<Dashboard />);

      await userEvent.click(await screen.findByRole("button", { name: "Reset password for rahul@cse.nits.ac.in" }));
      await userEvent.type(screen.getByLabelText("New password for rahul@cse.nits.ac.in"), "temporary-pass");
      await userEvent.click(screen.getByRole("button", { name: "Set password" }));

      expect(authApi.resetPassword).toHaveBeenCalledWith(7, "temporary-pass");
      expect(await screen.findByText(/can now sign in with the new password/)).toBeInTheDocument();
    });

    it("can add a company recruiter from the Recruiters tab", async () => {
      vi.mocked(authApi.createRecruiter).mockResolvedValue(recruiter({ id: 9, email: "hr@zoho.com" }));
      renderPage(<Dashboard />);

      await userEvent.click(await screen.findByRole("button", { name: /Recruiters/ }));
      await userEvent.type(screen.getByLabelText("Recruiter email"), "hr@zoho.com");
      await userEvent.type(screen.getByLabelText("First password"), "first-password");
      await userEvent.click(screen.getByRole("button", { name: "Add recruiter" }));

      expect(authApi.createRecruiter).toHaveBeenCalledWith("hr@zoho.com", "first-password");
      expect(await screen.findByText(/can now sign in on the Recruiter tab/)).toBeInTheDocument();
      // The new account appears in the Recruiters list straight away.
      expect(screen.getByText("Company recruiter")).toBeInTheDocument();
    });
  });
});
