import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as authApi from "../api/auth";
import * as drivesApi from "../api/drives";
import * as resumesApi from "../api/resumes";
import type { Resume } from "../api/types";
import { renderPage, renderPageWithRoutes } from "../test/render";
import { drive, personInCharge, student } from "../test/fixtures";
import Resumes from "./Resumes";

vi.mock("../api/auth");
vi.mock("../api/drives");
vi.mock("../api/resumes", async (importOriginal) => ({
  // MAX_RESUME_BYTES is a plain constant the page compares against, so the real one is
  // kept; only the functions that would talk to the server are replaced.
  ...(await importOriginal<typeof import("../api/resumes")>()),
  uploadResume: vi.fn(),
  fetchMyResumes: vi.fn(),
  retryResumeReview: vi.fn(),
  fetchResumeFile: vi.fn(),
}));

function resume(overrides: Partial<Resume> = {}): Resume {
  return {
    id: 1,
    driveId: 10,
    originalFilename: "asha-resume.pdf",
    sizeBytes: 120_000,
    status: "COMPLETED",
    score: 82,
    summary: "A strong fit for a backend role.",
    strengths: ["Two Spring Boot projects"],
    improvements: ["Quantify the internship"],
    failureReason: null,
    uploadedAt: "2026-10-01T10:00:00Z",
    reviewedAt: "2026-10-01T10:01:00Z",
    ...overrides,
  };
}

const pdf = () => new File(["%PDF-1.4"], "resume.pdf", { type: "application/pdf" });

/**
 * Resume feedback, from the student's side.
 *
 * The upload itself is the backend's business; what matters here is that the page
 * refuses a wrong file before spending an upload on it, sends the right drive with a
 * good one, and keeps the person in charge - who has no resume - out entirely.
 */
describe("Resumes", () => {
  beforeEach(() => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
    vi.mocked(drivesApi.fetchAllDrives).mockResolvedValue([drive()]);
    vi.mocked(resumesApi.fetchMyResumes).mockResolvedValue([]);
  });

  it("shows a finished review with its score and both lists", async () => {
    vi.mocked(resumesApi.fetchMyResumes).mockResolvedValue([resume()]);

    renderPage(<Resumes />);

    expect(await screen.findByText("82")).toBeInTheDocument();
    expect(screen.getByText("Strong match")).toBeInTheDocument();
    expect(screen.getByText("Two Spring Boot projects")).toBeInTheDocument();
    expect(screen.getByText("Quantify the internship")).toBeInTheDocument();
  });

  it("offers a retry on a failed review, with the server's reason", async () => {
    vi.mocked(resumesApi.fetchMyResumes).mockResolvedValue([
      resume({ status: "FAILED", score: null, summary: null, strengths: [], improvements: [], failureReason: "AI feedback is not switched on." }),
    ]);
    vi.mocked(resumesApi.retryResumeReview).mockResolvedValue(resume({ status: "PENDING", score: null }));

    renderPage(<Resumes />);

    expect(await screen.findByText("AI feedback is not switched on.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));

    expect(resumesApi.retryResumeReview).toHaveBeenCalledWith(1);
    expect(await screen.findByText("Waiting in the queue...")).toBeInTheDocument();
  });

  it("refuses a file that is not a PDF without uploading anything", async () => {
    // applyAccept off, so the file reaches the page's own check rather than being
    // filtered out by the input's accept attribute first - the page must not rely on it.
    const user = userEvent.setup({ applyAccept: false });
    renderPage(<Resumes />, { route: "/resumes?driveId=10" });

    const picker = await screen.findByLabelText(/drag your pdf here/i);
    await user.upload(picker, new File(["hello"], "notes.txt", { type: "text/plain" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/not a PDF/i);
    expect(screen.getByRole("button", { name: "Get feedback" })).toBeDisabled();
    expect(resumesApi.uploadResume).not.toHaveBeenCalled();
  });

  it("uploads a PDF for the drive it arrived with and lists it straight away", async () => {
    vi.mocked(resumesApi.uploadResume).mockResolvedValue(
      resume({ id: 2, status: "PENDING", score: null, summary: null, strengths: [], improvements: [] }),
    );
    renderPage(<Resumes />, { route: "/resumes?driveId=10" });

    // The drive from ?driveId is already chosen once the list has loaded.
    await screen.findByRole("option", { name: /Zoho/ });
    expect(screen.getByLabelText(/which drive/i)).toHaveValue("10");

    await userEvent.upload(screen.getByLabelText(/drag your pdf here/i), pdf());
    await userEvent.click(screen.getByRole("button", { name: "Get feedback" }));

    expect(resumesApi.uploadResume).toHaveBeenCalledWith(10, expect.any(File));
    expect(await screen.findByText(/Uploaded!/)).toBeInTheDocument();
    expect(screen.getByText("Waiting in the queue...")).toBeInTheDocument();
  });

  it("sends the person in charge back to the board", async () => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());

    renderPageWithRoutes(<Resumes />, {
      route: "/resumes",
      path: "/resumes",
      destinations: { "/drives": "drives board" },
    });

    expect(await screen.findByText("drives board")).toBeInTheDocument();
    expect(resumesApi.fetchMyResumes).not.toHaveBeenCalled();
  });
});
