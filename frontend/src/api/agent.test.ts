import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { streamAssistant } from "./agent";
import type { ChatTurn } from "./types";

/**
 * streamAssistant turns the backend's event stream into two things a page can use: a
 * callback for each piece of text, and a promise for the finished reply - or an ApiError,
 * however the stream went wrong.
 */
describe("streamAssistant", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  /** A text/event-stream response made of these events, in order. */
  const events = (...list: [event: string, data: unknown][]) =>
    new Response(list.map(([event, data]) => `event:${event}\ndata:${JSON.stringify(data)}\n\n`).join(""), {
      status: 200,
      headers: { "Content-Type": "text/event-stream" },
    });

  const sentBody = async () => JSON.parse(await (fetchMock.mock.calls[0][0] as Request).text());

  it("hands over each piece as it arrives, then the whole reply", async () => {
    fetchMock.mockResolvedValue(
      events(["token", { text: "You can " }], ["token", { text: "apply." }], ["done", { reply: "You can apply.", toolsUsed: [] }]),
    );
    const pieces: string[] = [];

    const response = await streamAssistant("Which drives?", [], (text) => pieces.push(text));

    expect(pieces).toEqual(["You can ", "apply."]);
    expect(response).toEqual({ reply: "You can apply.", toolsUsed: [] });
  });

  it("turns an error event into an ApiError carrying the backend's message", async () => {
    fetchMock.mockResolvedValue(
      events(
        ["token", { text: "Let me" }],
        ["error", { status: 502, detail: "The assistant could not answer just now. Try again in a moment." }],
      ),
    );

    await expect(streamAssistant("Hello", [], () => {})).rejects.toMatchObject({
      status: 502,
      message: "The assistant could not answer just now. Try again in a moment.",
    });
  });

  it("treats a stream that ends without finishing as a failure", async () => {
    fetchMock.mockResolvedValue(events(["token", { text: "Half an ans" }]));

    await expect(streamAssistant("Hello", [], () => {})).rejects.toMatchObject({ status: 502 });
  });

  it("sends each earlier turn as just its text and who said it", async () => {
    fetchMock.mockResolvedValue(events(["done", { reply: "Sure.", toolsUsed: [] }]));
    const shown = { text: "Zoho and TCS.", fromAssistant: true, toolsUsed: ["eligibleDrives"] };

    await streamAssistant("And the deadline?", [shown], () => {});

    expect((await sentBody()).history).toEqual([{ text: "Zoho and TCS.", fromAssistant: true }]);
  });

  it("sends only as much history as the backend accepts", async () => {
    fetchMock.mockResolvedValue(events(["done", { reply: "Sure.", toolsUsed: [] }]));
    const history: ChatTurn[] = Array.from({ length: 25 }, (_, i) => ({
      text: i === 24 ? "x".repeat(5000) : `turn ${i}`,
      fromAssistant: i % 2 === 1,
    }));

    await streamAssistant("And then?", history, () => {});

    const body = await sentBody();
    expect(body.message).toBe("And then?");
    expect(body.history).toHaveLength(20);
    expect(body.history[0].text).toBe("turn 5");
    expect(body.history[19].text).toHaveLength(4000);
  });
});
