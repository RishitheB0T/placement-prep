import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import { useNavigate } from "react-router-dom";

import { streamAssistant } from "../api/agent";
import { fetchMyProfile } from "../api/auth";
import { isStudentRole } from "../api/roles";
import { ApiError } from "../api/client";
import { searchJobDescriptions } from "../api/jobDescriptions";
import type { ChatTurn, JobDescriptionMatch, UserProfile } from "../api/types";
import AppHeader from "../components/AppHeader";
import { CompanyAvatar, Icon, PageHero, Spinner, type IconName } from "../components/ui";

/** How each of the assistant's tools is named on screen, under a reply that used it. */
const TOOL_LABELS: Record<string, { label: string; icon: IconName }> = {
  eligibleDrives: { label: "Your eligible drives", icon: "check" },
  allDrives: { label: "All drives", icon: "briefcase" },
  myApplications: { label: "Your applications", icon: "list" },
  applyToDrive: { label: "Apply to a drive", icon: "send" },
  searchJobDescriptions: { label: "Job-description search", icon: "search" },
};

/** A message on screen: what is sent back as history, plus the tools a reply used. */
type Turn = ChatTurn & { toolsUsed?: string[] };

const SUGGESTIONS = [
  "Which drives am I eligible for?",
  "How are my applications going?",
  "Which jobs ask for Java and SQL?",
  "What does the selection process usually involve?",
];

/**
 * Two AI features side by side.
 *
 * The chat sends each message, with the conversation so far, to the backend assistant,
 * which answers by calling real backend methods - eligible drives, my applications,
 * applying, searching job descriptions - as tools. The reply is shown while it is being
 * written, the way it streams in from the model. The search panel calls the
 * job-description search directly: it finds passages by meaning, using a model that runs
 * inside the backend, and by their exact words - so it works even when no AI key is
 * configured for the chat.
 */
export default function Assistant() {
  const navigate = useNavigate();

  const [profile, setProfile] = useState<UserProfile | null>(null);

  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState("");
  const [thinking, setThinking] = useState(false);
  // The reply written so far while it streams in; null when no reply is arriving.
  const [partial, setPartial] = useState<string | null>(null);
  const [chatError, setChatError] = useState<string | null>(null);
  const endOfChat = useRef<HTMLDivElement>(null);

  const [query, setQuery] = useState("");
  const [matches, setMatches] = useState<JobDescriptionMatch[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [searchError, setSearchError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchMyProfile()
      .then((me) => {
        if (cancelled) return;
        // The assistant's tools all act as a student; the backend refuses everyone else too.
        if (!isStudentRole(me.role)) {
          navigate("/drives", { replace: true });
          return;
        }
        setProfile(me);
      })
      .catch((e) => {
        if (!cancelled && e instanceof ApiError && e.status === 401) {
          navigate("/login", { replace: true });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  // Keep the newest message in view as the conversation grows.
  useEffect(() => {
    endOfChat.current?.scrollIntoView?.({ behavior: "smooth", block: "end" });
  }, [turns, thinking, partial]);

  async function send(text: string) {
    const message = text.trim();
    if (message === "" || thinking) return;

    setChatError(null);
    setDraft("");
    // The earlier turns are sent as they were before this message was added.
    const history = turns;
    setTurns([...history, { text: message, fromAssistant: false }]);
    setThinking(true);

    try {
      const { reply, toolsUsed } = await streamAssistant(message, history, (piece) =>
        setPartial((sofar) => (sofar ?? "") + piece),
      );
      setTurns((current) => [...current, { text: reply, fromAssistant: true, toolsUsed }]);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        navigate("/login", { replace: true });
        return;
      }
      setChatError(e instanceof ApiError ? e.message : "Could not reach the server");
    } finally {
      // A reply cut off by an error is dropped rather than kept half-written.
      setPartial(null);
      setThinking(false);
    }
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void send(draft);
  }

  /** Enter sends; Shift+Enter starts a new line, as in most chat apps. */
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      void send(draft);
    }
  }

  async function handleSearch(event: FormEvent) {
    event.preventDefault();
    if (query.trim() === "") return;
    setSearching(true);
    setSearchError(null);
    try {
      setMatches(await searchJobDescriptions(query.trim()));
    } catch (e) {
      setSearchError(e instanceof ApiError ? e.message : "Could not reach the server");
    } finally {
      setSearching(false);
    }
  }

  return (
    <>
      <title>Assistant · Placement Prep</title>
      <AppHeader profile={profile} active="assistant" />

      <main className="mx-auto max-w-6xl px-4 pt-8 pb-16">
        <PageHero
          eyebrow="Ask in plain English"
          title="Placement assistant"
          subtitle="Ask about drives you can apply to, how your applications are going, or what a job description asks for. When you ask it to, it can apply to a drive for you."
        />

        <div className="mt-8 grid items-start gap-6 lg:grid-cols-5">
          {/* --------------------------------------------------------------- Chat */}
          <section className="card animate-fade-up flex min-h-[32rem] flex-col overflow-hidden lg:col-span-3">
            <div className="flex items-center gap-3 border-b border-slate-100 px-6 py-4">
              <span className="grid size-10 place-items-center rounded-2xl bg-linear-to-br from-violet-500 to-fuchsia-500 text-white shadow-lg shadow-fuchsia-500/25">
                <Icon name="chat" className="size-5" />
              </span>
              <div>
                <h2 className="font-bold text-slate-900">Chat</h2>
                <p className="text-xs text-slate-500">Answers come from your real drives and applications</p>
              </div>
            </div>

            <div className="flex-1 space-y-4 overflow-y-auto px-6 py-5" aria-live="polite" aria-busy={thinking}>
              {turns.length === 0 && (
                <div className="py-6 text-center">
                  <p className="text-sm font-semibold text-slate-700">Try asking</p>
                  <div className="mt-3 flex flex-wrap justify-center gap-2">
                    {SUGGESTIONS.map((suggestion) => (
                      <button
                        key={suggestion}
                        type="button"
                        onClick={() => void send(suggestion)}
                        className="btn btn-sm btn-secondary"
                      >
                        {suggestion}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {turns.map((turn, index) =>
                turn.fromAssistant ? (
                  <AssistantBubble key={index} text={turn.text} toolsUsed={turn.toolsUsed} />
                ) : (
                  <div key={index} className="animate-pop flex justify-end">
                    <p className="max-w-[85%] rounded-2xl rounded-tr-sm bg-linear-to-r from-indigo-600 to-fuchsia-500 px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap text-white shadow-md shadow-indigo-500/20">
                      {turn.text}
                    </p>
                  </div>
                ),
              )}

              {partial !== null && <AssistantBubble text={partial} writing />}

              {thinking && partial === null && (
                <div className="flex items-center gap-3 text-sm text-slate-500">
                  <span className="grid size-8 place-items-center rounded-xl bg-violet-100 text-violet-600">
                    <Spinner className="size-4" />
                  </span>
                  Thinking...
                </div>
              )}

              {chatError && (
                <p role="alert" className="alert-error animate-pop">
                  {chatError}
                </p>
              )}
              <div ref={endOfChat} />
            </div>

            <form onSubmit={handleSubmit} className="flex items-end gap-3 border-t border-slate-100 bg-white/60 px-4 py-4">
              <label className="sr-only" htmlFor="assistant-message">
                Message
              </label>
              <textarea
                id="assistant-message"
                rows={2}
                value={draft}
                maxLength={2000}
                onChange={(e) => setDraft(e.target.value)}
                onKeyDown={handleKeyDown}
                placeholder="Ask anything about your placements..."
                className="input mt-0 flex-1 resize-none"
              />
              <button type="submit" disabled={thinking || draft.trim() === ""} className="btn btn-primary">
                <Icon name="send" />
                Send
              </button>
            </form>
          </section>

          {/* ------------------------------------------------- Semantic search */}
          <section className="card animate-fade-up p-6 [animation-delay:100ms] lg:col-span-2">
            <div className="flex items-center gap-3">
              <span className="grid size-10 place-items-center rounded-2xl bg-linear-to-br from-sky-500 to-indigo-500 text-white shadow-lg shadow-sky-500/25">
                <Icon name="search" className="size-5" />
              </span>
              <div>
                <h2 className="font-bold text-slate-900">Search job descriptions</h2>
                <p className="text-xs text-slate-500">Finds matches by meaning and by your exact words</p>
              </div>
            </div>

            <form onSubmit={(e) => void handleSearch(e)} className="mt-5 flex gap-2">
              <label className="sr-only" htmlFor="jd-search">
                Search job descriptions
              </label>
              <input
                id="jd-search"
                value={query}
                maxLength={500}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="e.g. backend work with databases"
                className="input mt-0"
              />
              <button type="submit" disabled={searching || query.trim() === ""} className="btn btn-secondary">
                {searching ? <Spinner /> : <Icon name="search" />}
                Search
              </button>
            </form>

            {searchError && (
              <p role="alert" className="alert-error animate-pop mt-4">
                {searchError}
              </p>
            )}

            {matches !== null && matches.length === 0 && (
              <p className="mt-5 text-sm text-slate-500">
                Nothing close enough. Try other words, or ask the placement cell to upload job descriptions.
              </p>
            )}

            {matches !== null && matches.length > 0 && (
              <ul className="mt-5 space-y-3">
                {matches.map((match, index) => (
                  <li key={`${match.driveId}-${index}`} className="animate-pop rounded-2xl border border-slate-100 bg-white/80 p-4">
                    <div className="flex items-center gap-3">
                      <CompanyAvatar name={match.companyName} size="sm" />
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-bold text-slate-900">{match.companyName}</p>
                        <p className="truncate text-xs text-slate-500">{match.role}</p>
                      </div>
                      <MatchChip match={match} />
                    </div>
                    <p className="mt-3 line-clamp-4 text-sm leading-relaxed text-slate-600">{match.excerpt}</p>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </main>
    </>
  );
}

/**
 * One of the assistant's messages. While it is still being written, a blinking bar after
 * the text shows that more is coming; once finished, the tools it used are listed under
 * it, so a student can see the answer came from their real data.
 */
function AssistantBubble({
  text,
  writing = false,
  toolsUsed = [],
}: {
  text: string;
  writing?: boolean;
  toolsUsed?: string[];
}) {
  return (
    <div className="animate-pop flex items-start gap-3">
      <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-linear-to-br from-violet-500 to-fuchsia-500 text-white">
        <Icon name="sparkles" className="size-4" />
      </span>
      <div className="max-w-[85%]">
        <p className="rounded-2xl rounded-tl-sm bg-slate-50 px-4 py-3 text-sm leading-relaxed whitespace-pre-wrap text-slate-800 ring-1 ring-slate-100">
          {text}
          {writing && (
            <span aria-hidden="true" className="ml-0.5 inline-block h-4 w-1.5 animate-pulse rounded-sm bg-violet-400 align-text-bottom" />
          )}
        </p>
        {toolsUsed.length > 0 && (
          <ul aria-label="Looked up" className="mt-2 flex flex-wrap gap-1.5">
            {toolsUsed.map((tool) => (
              <li key={tool} className="chip bg-violet-50 text-violet-700 ring-1 ring-violet-100">
                <Icon name={TOOL_LABELS[tool]?.icon ?? "sparkles"} className="size-3.5" />
                {TOOL_LABELS[tool]?.label ?? tool}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/**
 * How well a search result matched. A passage found by meaning shows how close it is; one
 * found only by its words has no closeness to show, so it says so instead.
 */
function MatchChip({ match }: { match: JobDescriptionMatch }) {
  const closeness = `${Math.round(match.score * 100)}% match`;
  if (match.matchedBy === "KEYWORDS") {
    return <span className="chip shrink-0 bg-sky-100 text-sky-700">Word match</span>;
  }
  return (
    <span className="chip shrink-0 bg-emerald-100 text-emerald-700">
      {match.matchedBy === "BOTH" ? `${closeness} + words` : closeness}
    </span>
  );
}
