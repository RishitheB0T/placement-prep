import { api } from "./client";
import type { ChatResponse, ChatTurn } from "./types";

/** The backend accepts at most this many earlier turns, each at most this long. */
const MAX_HISTORY_TURNS = 20;
const MAX_TURN_LENGTH = 4000;

/**
 * Sends one message to the placement assistant, along with the conversation so far.
 *
 * The history is trimmed here to what the backend accepts - the most recent turns, each
 * cut to length - so a long conversation keeps working instead of starting to fail with a
 * 400 once it grows past the limit.
 *
 * Throws an ApiError with status 503 when the server has no AI key configured, and 502
 * when the AI provider failed to answer.
 */
export function askAssistant(message: string, history: ChatTurn[]): Promise<ChatResponse> {
  const recent = history.slice(-MAX_HISTORY_TURNS).map((turn) => ({
    ...turn,
    text: turn.text.slice(0, MAX_TURN_LENGTH),
  }));
  return api.post<ChatResponse>("/api/agent/chat", { message, history: recent });
}
