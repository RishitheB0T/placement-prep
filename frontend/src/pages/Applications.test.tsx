import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as applicationsApi from "../api/applications";
import * as authApi from "../api/auth";
import * as drivesApi from "../api/drives";
import { renderPage } from "../test/render";
import { application, coordinator, drive, personInCharge, student } from "../test/fixtures";
import Applications from "./Applications";

vi.mock("../api/auth");
vi.mock("../api/drives");
vi.mock("../api/applications");

/**
 * One page, two views: your own applications, and the applicants to a drive.
 *
 * Which of them an account reaches follows from what it is, and the two are independent -
 * a coordinator is a student who also reviews, so they get both and a tab to switch.
 */
describe("Applications", () => {
  beforeEach(() => {
    vi.mocked(drivesApi.fetchAllDrives).mockResolvedValue([drive()]);
    vi.mocked(applicationsApi.fetchMyApplications).mockResolvedValue([application({ driveId: 10 })]);
    vi.mocked(applicationsApi.fetchApplicationsForDrive).mockResolvedValue([
      application({ id: 200, studentId: 7, studentEmail: "rahul@cse.nits.ac.in" }),
    ]);
  });

  describe("a student", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
    });

    it("sees their own applications, named by company", async () => {
      renderPage(<Applications />);

      expect(await screen.findByRole("heading", { name: "My applications" })).toBeInTheDocument();
      expect(screen.getByText("Zoho")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
    });

    it("gets no review tools at all", async () => {
      renderPage(<Applications />);
      await screen.findByRole("heading", { name: "My applications" });

      expect(screen.queryByRole("button", { name: "Applicants" })).not.toBeInTheDocument();
      expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
      expect(applicationsApi.fetchApplicationsForDrive).not.toHaveBeenCalled();
    });

    it("withdraws, and the button goes away once it has", async () => {
      vi.mocked(applicationsApi.withdrawApplication).mockResolvedValue(
        application({ driveId: 10, status: "WITHDRAWN" }),
      );

      renderPage(<Applications />);
      await userEvent.click(await screen.findByRole("button", { name: "Withdraw" }));

      await waitFor(() => expect(screen.getByText("withdrawn")).toBeInTheDocument());
      expect(screen.queryByRole("button", { name: "Withdraw" })).not.toBeInTheDocument();
    });
  });

  describe("a coordinator", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(coordinator());
    });

    it("opens on their own applications, with a tab to the other view", async () => {
      renderPage(<Applications />);

      expect(await screen.findByRole("heading", { name: "My applications" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Applicants" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Withdraw" })).toBeInTheDocument();
    });

    it("can switch to reviewing applicants", async () => {
      renderPage(<Applications />);
      await userEvent.click(await screen.findByRole("button", { name: "Applicants" }));

      expect(await screen.findByRole("heading", { name: "Applicants" })).toBeInTheDocument();
      // The drive picker, not the prompt text - "Choose a drive" appears twice, once as
      // the placeholder option and once as the empty-state paragraph.
      expect(screen.getByRole("combobox")).toBeInTheDocument();
    });
  });

  describe("the person in charge", () => {
    beforeEach(() => {
      vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());
    });

    it("opens straight into review, having no applications of their own", async () => {
      renderPage(<Applications />);

      expect(await screen.findByRole("heading", { name: "Applicants" })).toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "My applications" })).not.toBeInTheDocument();
      expect(applicationsApi.fetchMyApplications).not.toHaveBeenCalled();
    });

    it("labels each applicant by email rather than a bare id", async () => {
      // The whole point of the backend enriching this list: an id is not something a
      // human can review anybody on.
      renderPage(<Applications />);
      await screen.findByRole("heading", { name: "Applicants" });

      await userEvent.selectOptions(screen.getByRole("combobox"), "10");

      expect(await screen.findByText("rahul@cse.nits.ac.in")).toBeInTheDocument();
      expect(screen.queryByText("Student #7")).not.toBeInTheDocument();
    });

    it("falls back to the id when an applicant's account has gone missing", async () => {
      vi.mocked(applicationsApi.fetchApplicationsForDrive).mockResolvedValue([
        application({ id: 200, studentId: 7, studentEmail: null }),
      ]);

      renderPage(<Applications />);
      await screen.findByRole("heading", { name: "Applicants" });
      await userEvent.selectOptions(screen.getByRole("combobox"), "10");

      expect(await screen.findByText("Student #7")).toBeInTheDocument();
    });

    it("can move an application along", async () => {
      vi.mocked(applicationsApi.updateApplicationStatus).mockResolvedValue(
        application({ id: 200, studentId: 7, studentEmail: "rahul@cse.nits.ac.in", status: "SHORTLISTED" }),
      );

      renderPage(<Applications />);
      await screen.findByRole("heading", { name: "Applicants" });
      await userEvent.selectOptions(screen.getByRole("combobox"), "10");
      await userEvent.click(await screen.findByRole("button", { name: "Mark shortlisted" }));

      await waitFor(() =>
        expect(applicationsApi.updateApplicationStatus).toHaveBeenCalledWith(200, "SHORTLISTED"),
      );
    });
  });
});
