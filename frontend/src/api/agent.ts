import { ApiError, api } from "./client";
import type { ChatChunk, ChatResponse, ChatTurn } from "./types";

/** The backend accepts at most this many earlier turns, each at most this long. */
const MAX_HISTORY_TURNS = 20;
const MAX_TURN_LENGTH = 4000;

/**
 * Sends one message to the placement assistant, along with the conversation so far, and
 * hands the reply back piece by piece while the model writes it.
 *
 * onText is called with each new piece of text; the returned promise resolves with the
 * whole reply once the assistant has finished.
 *
 * The history is trimmed here to what the backend accepts - the most recent turns, each
 * cut to length - so a long conversation keeps working instead of starting to fail with a
 * 400 once it grows past the limit.
 *
 * Throws an ApiError with status 503 when the server has no AI key configured, and 502
 * when the AI provider failed - before the reply began, or part-way through it.
 */
export async function streamAssistant(
  message: string,
  history: ChatTurn[],
  onText: (text: string) => void,
): Promise<ChatResponse> {
  const recent = history.slice(-MAX_HISTORY_TURNS).map((turn) => ({
    ...turn,
    text: turn.text.slice(0, MAX_TURN_LENGTH),
  }));

  for await (const { event, data } of api.postForEvents("/api/agent/chat/stream", { message, history: recent })) {
    if (event === "token") {
      onText((JSON.parse(data) as ChatChunk).text);
    } else if (event === "done") {
      return JSON.parse(data) as ChatResponse;
    } else if (event === "error") {
      // The same problem-detail shape as any other error from the backend.
      const problem = JSON.parse(data) as { status?: number; detail?: string };
      throw new ApiError(problem.status ?? 502, problem.detail ?? "The assistant could not answer just now.");
    }
  }

  // The connection closed before the server said the reply was finished.
  throw new ApiError(502, "The assistant stopped before finishing its answer. Try again in a moment.");
}
