import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import * as agentApi from "../api/agent";
import * as authApi from "../api/auth";
import { ApiError } from "../api/client";
import * as jobDescriptionsApi from "../api/jobDescriptions";
import type { ChatResponse } from "../api/types";
import { renderPage, renderPageWithRoutes } from "../test/render";
import { personInCharge, student } from "../test/fixtures";
import Assistant from "./Assistant";

vi.mock("../api/auth");
vi.mock("../api/agent");
vi.mock("../api/jobDescriptions");

/** The chat and the job-description search, from the student's side. */
describe("Assistant", () => {
  beforeEach(() => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(student());
  });

  it("shows the assistant's reply under the student's message", async () => {
    vi.mocked(agentApi.streamAssistant).mockResolvedValue({ reply: "You are eligible for Zoho." });
    renderPage(<Assistant />);

    await userEvent.type(screen.getByLabelText("Message"), "Which drives can I apply to?");
    await userEvent.click(screen.getByRole("button", { name: "Send" }));

    expect(screen.getByText("Which drives can I apply to?")).toBeInTheDocument();
    expect(await screen.findByText("You are eligible for Zoho.")).toBeInTheDocument();
    expect(agentApi.streamAssistant).toHaveBeenCalledWith("Which drives can I apply to?", [], expect.any(Function));
  });

  it("shows the reply while it is still being written", async () => {
    let finish: (response: ChatResponse) => void = () => {};
    vi.mocked(agentApi.streamAssistant).mockImplementation((_message, _history, onText) => {
      onText("You are eligible ");
      onText("for Zoho.");
      return new Promise((resolve) => {
        finish = resolve;
      });
    });
    renderPage(<Assistant />);

    await userEvent.type(screen.getByLabelText("Message"), "Which drives can I apply to?{Enter}");

    // Both pieces are on screen before the reply has finished...
    expect(await screen.findByText("You are eligible for Zoho.")).toBeInTheDocument();
    expect(screen.queryByText("Thinking...")).not.toBeInTheDocument();

    // ...and finishing it leaves one message, not the partial one plus the whole one.
    act(() => finish({ reply: "You are eligible for Zoho." }));
    expect(await screen.findAllByText("You are eligible for Zoho.")).toHaveLength(1);
  });

  it("sends the earlier turns along with a follow-up", async () => {
    vi.mocked(agentApi.streamAssistant)
      .mockResolvedValueOnce({ reply: "Zoho and TCS." })
      .mockResolvedValueOnce({ reply: "Zoho closes on 30 June." });
    renderPage(<Assistant />);

    await userEvent.click(await screen.findByRole("button", { name: "Which drives am I eligible for?" }));
    await screen.findByText("Zoho and TCS.");
    await userEvent.type(screen.getByLabelText("Message"), "When does the first close?{Enter}");

    expect(await screen.findByText("Zoho closes on 30 June.")).toBeInTheDocument();
    expect(agentApi.streamAssistant).toHaveBeenLastCalledWith(
      "When does the first close?",
      [
        { text: "Which drives am I eligible for?", fromAssistant: false },
        { text: "Zoho and TCS.", fromAssistant: true },
      ],
      expect.any(Function),
    );
  });

  it("explains when the AI is not switched on", async () => {
    vi.mocked(agentApi.streamAssistant).mockRejectedValue(
      new ApiError(503, "The AI assistant is not switched on for this server yet."),
    );
    renderPage(<Assistant />);

    await userEvent.type(screen.getByLabelText("Message"), "Hello{Enter}");

    expect(await screen.findByRole("alert")).toHaveTextContent(/not switched on/i);
  });

  it("searches job descriptions by meaning and shows each match's drive", async () => {
    vi.mocked(jobDescriptionsApi.searchJobDescriptions).mockResolvedValue([
      { driveId: 10, companyName: "Razorpay", role: "Backend Engineer", excerpt: "Strong SQL skills.", score: 0.62 },
    ]);
    renderPage(<Assistant />);

    await userEvent.type(screen.getByLabelText("Search job descriptions"), "backend work with databases");
    await userEvent.click(screen.getByRole("button", { name: "Search" }));

    expect(await screen.findByText("Razorpay")).toBeInTheDocument();
    expect(screen.getByText("62% match")).toBeInTheDocument();
    expect(jobDescriptionsApi.searchJobDescriptions).toHaveBeenCalledWith("backend work with databases");
  });

  it("sends the admin back to the board", async () => {
    vi.mocked(authApi.fetchMyProfile).mockResolvedValue(personInCharge());

    renderPageWithRoutes(<Assistant />, {
      route: "/assistant",
      path: "/assistant",
      destinations: { "/drives": "drives board" },
    });

    expect(await screen.findByText("drives board")).toBeInTheDocument();
  });
});
