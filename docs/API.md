# API reference

36 REST endpoints, all JSON apart from one stream of events. Every path needs
`Authorization: Bearer <token>` except the three marked **public**. Every error is an
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem detail (see [Errors](#errors)).

**Who** column: **S** student · **C** coordinator · **R** recruiter · **A** admin (the
person in charge). A recruiter's access to drives and applicants is limited to the drives
they posted; any other drive answers `404`.

A ready-made [Postman collection](../postman/placement-prep.postman_collection.json) calls
every endpoint, each restricted one both by a role that may and one that may not.

**Contents** - [Auth](#auth) · [Users](#users) · [Drives](#drives) ·
[Applications](#applications) · [Notifications](#notifications) · [Resumes](#resumes) ·
[Job descriptions](#job-descriptions-rag) · [Assistant](#ai-assistant) · [Errors](#errors)

---

## Auth

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/auth/register` | create a student account and sign in | **public** | `201` · `400` · `409` email taken |
| `POST` | `/api/auth/login` | exchange credentials for a token | **public** | `200` · `401` |
| `POST` | `/api/auth/refresh` | a fresh token with the account's current role | any | `200` · `401` |
| `GET` | `/actuator/health` | health check | **public** | `200` |

```json
POST /api/auth/register
{ "email": "asha.verma@cse.nits.ac.in", "password": "at-least-8-characters" }

200/201 →
{ "token": "eyJhbGciOiJIUzI1NiJ9...", "email": "asha.verma@cse.nits.ac.in",
  "role": "STUDENT", "expiresAt": 1798000000000 }
```

- Registration accepts college addresses only (`name@<dept>.nits.ac.in`) and always
  creates a `STUDENT`. Passwords are 8-72 characters (BCrypt ignores bytes past 72).
- A failed login is a flat `401` for both an unknown email and a wrong password, so the
  endpoint never reveals which addresses are registered.
- `refresh` is for after a role change: the frontend calls it when the profile's role and
  the token's role disagree.

## Users

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `GET` | `/api/users/me` | my account and profile | any | `200` |
| `PUT` | `/api/users/me` | replace my academic profile | S C | `200` · `400` |
| `PUT` | `/api/users/me/password` | change my own password | any | `204` · `400` wrong current password |
| `PUT` | `/api/users/me/staff-profile` | replace my staff details | A | `200` · `400` |
| `GET` | `/api/users?role=` | every account, optionally one role | A | `200` · `400` unknown role |
| `POST` | `/api/users/{id}/promote` | make a student a coordinator | A | `200` · `404` · `409` not a student |
| `POST` | `/api/users/recruiters` | add a company recruiter | A | `201` · `400` · `409` email taken |
| `POST` | `/api/users/{id}/password` | set a new password for someone who forgot theirs | A | `204` · `404` |

**Academic profile** (`PUT /api/users/me`) - all required. The response carries a computed
`complete` flag the UI uses to prompt for missing marks.

| Field | Rule |
| --- | --- |
| `cgpa` | 0-10 |
| `branch` | up to 32 characters, e.g. `CSE`; stored upper-case |
| `tenthPercentage`, `twelfthPercentage` | 0-100 |
| `backlogs` | 0 or more |

**Staff profile** (`PUT /api/users/me/staff-profile`) - all optional: `designation`,
`department`, `officeLocation` (up to 100 characters), `staffId` (up to 50, unique),
`phoneNumber` (up to 20). A database `CHECK` keeps these columns off every other role.

**Passwords** - `PUT /api/users/me/password` takes `{currentPassword, newPassword}`; the
current one is checked again so a browser left signed in cannot lock its owner out.
`POST /api/users/{id}/password` takes `{newPassword}`. **Recruiters** -
`POST /api/users/recruiters` takes `{email, password}` with any email domain, since a
recruiter uses their company address.

## Drives

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `GET` | `/api/drives` | all drives, newest first (a recruiter: their own) | any | `200` |
| `GET` | `/api/drives/eligible` | drives **I** qualify for, soonest deadline first | any | `200` · `409` no complete profile |
| `GET` | `/api/drives/{id}` | one drive | any | `200` · `404` |
| `POST` | `/api/drives` | post a drive (records the poster) | C R A | `201` · `400` |
| `PUT` | `/api/drives/{id}` | replace a drive | C R A | `200` · `400` · `404` |
| `DELETE` | `/api/drives/{id}` | delete a drive, with its applications, resumes, notifications and passages | C R A | `204` · `404` |

| Field | Required | Rule |
| --- | --- | --- |
| `companyName`, `role` | yes | up to 255 characters |
| `ctc` | yes | positive; rupees per annum; up to 8 digits before the decimal point |
| `tier` | yes | 1-3 |
| `cgpaCutoff` | yes | 0-10 |
| `tenthCutoff`, `twelfthCutoff` | no | 0-100; leave out for no requirement |
| `backlogsAllowed` | yes | whether students with active backlogs may apply |
| `maxBacklogs` | when `backlogsAllowed` is true | 0 or more |
| `eligibleBranches` | yes | non-empty list, each up to 32 characters |
| `applicationDeadline` | yes | ISO-8601 instant (not required to be in the future, so an old drive can still be corrected) |
| `description` | no | free text |

`/api/drives/eligible` takes no parameters: it reads the marks from the caller's own stored
profile, so nobody can ask what someone else would qualify for. A drive is returned when
every cutoff is met (an unset cutoff imposes nothing), the branch is listed
(case-insensitive), backlogs are within what the drive allows, and the deadline is ahead.

## Applications

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/applications` | apply, `{driveId}` | S C | `201` · `404` no drive · `409` see below |
| `GET` | `/api/applications/me` | my applications | S C | `200` |
| `POST` | `/api/applications/{id}/withdraw` | pull out | S C | `200` · `404` · `409` already decided |
| `GET` | `/api/applications/drive/{driveId}` | a drive's applicants, each with their email | C R A | `200` · `404` |
| `PATCH` | `/api/applications/{id}/status` | decide, `{status, note}` | C R A | `200` · `400` · `404` · `409` withdrawn |
| `GET` | `/api/applications/stats` | counts by status, and what is waiting per drive | C R A | `200` |

- **Applying** answers `409` when there is already an active application, the deadline has
  passed, the profile is incomplete, or the student does not meet the drive's criteria.
  The `detail` says which. Applying again after withdrawing reopens the same row.
- **Statuses**: `APPLIED`, `SHORTLISTED`, `REJECTED`, `SELECTED`, `WITHDRAWN`. Only
  `APPLIED` and `SHORTLISTED` can be withdrawn; the hiring side cannot set `WITHDRAWN`
  (`400`) or decide a withdrawn application (`409`). Every status change notifies the
  student.
- **Stats**: `{"byStatus": {"APPLIED": 11, ...every status...}, "awaitingReviewByDrive": {"12": 3}}`
  - a recruiter's counts cover only their own drives.

## Notifications

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `GET` | `/api/notifications/me` | my newest 50, newest first | S C | `200` |
| `POST` | `/api/notifications/{id}/read` | mark one of mine read | S C | `200` · `404` |
| `POST` | `/api/notifications/read-all` | mark all of mine read | S C | `204` |

```json
[{ "id": 41, "kind": "DEADLINE_3_DAYS", "driveId": 12, "read": false,
   "createdAt": "2026-10-23T18:24:00Z",
   "message": "Nimbus Systems - Software Development Engineer closes in less than 3 days, on 25 Oct 2026, 11:30 pm. You are eligible and have not applied yet." }]
```

Kinds: `NEW_DRIVE`, `DEADLINE_3_DAYS`, `DEADLINE_1_DAY` (sent by the scheduled reminder
job, at most once each per drive) and `STATUS_CHANGED`.

## Resumes

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/resumes` | upload a PDF for review, multipart `driveId` + `file` | S C | `201` `PENDING` · `400` not a PDF · `404` no drive · `409` a review is already running · `413` over 5 MB |
| `GET` | `/api/resumes/me` | my resumes and their reviews | S C | `200` |
| `GET` | `/api/resumes/{id}` | one of mine | S C | `200` · `404` |
| `GET` | `/api/resumes/{id}/file` | the PDF itself | S C | `200` · `404` |
| `POST` | `/api/resumes/{id}/retry` | retry a failed review | S C | `200` · `404` · `409` not failed |

A review moves `PENDING` → `PROCESSING` → `COMPLETED` (with `score` 0-100, `summary`,
`strengths`, `improvements`) or `FAILED` (with a readable `failureReason`). Needs
`ANTHROPIC_API_KEY` on the server.

## Job descriptions (RAG)

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/drives/{driveId}/job-description` | index a drive's JD PDF, replacing any earlier one | C R A | `201` `{driveId, passages}` · `400` · `404` · `422` no readable text |
| `GET` | `/api/job-descriptions/search?q=&limit=5` | passages matching by meaning and by words | S C A | `200` · `400` · `503` semantic search switched off |

`q` is 1-500 characters and `limit` 1-20. Each match is
`{driveId, companyName, role, excerpt, score, matchedBy}`, best first:

```json
{ "driveId": 12, "companyName": "Razorpay", "role": "Backend Engineer",
  "excerpt": "Strong SQL skills: PostgreSQL schema design, indexing and query tuning...",
  "score": 0.41, "matchedBy": "BOTH" }
```

The search is hybrid: one half finds passages close in meaning, the other finds passages
containing any of the query's words, and the two rankings are merged. `matchedBy` says
which half found the passage - `MEANING`, `KEYWORDS` or `BOTH`. `score` is the closeness
in meaning (0-1), and `0` for a passage found by its words alone; the order comes from both
halves together, so it is not sorted by `score`. Works with no API key - the embedding
model runs locally. On a deployment that switches semantic search off to save
memory (see the development guide), search and upload answer `503`; resume reviews still
run, just without job-description excerpts.

## AI assistant

| Method | Path | Purpose | Who | Success / errors |
| --- | --- | --- | --- | --- |
| `POST` | `/api/agent/chat` | ask in plain English | S C | `200` `{reply, toolsUsed}` · `400` · `502` provider failed · `503` no API key |
| `POST` | `/api/agent/chat/stream` | the same, with the reply streamed as it is written | S C | `200` `text/event-stream` · `400` · `503` no API key |

```json
{ "message": "Which drives can I still apply to?",
  "history": [{ "text": "Hi", "fromAssistant": false }, { "text": "Hello! How can I help?", "fromAssistant": true }] }
```

`message` is 1-2000 characters; `history` up to 20 turns of up to 4000 characters. The
server keeps no conversation - the client sends the recent turns each time.

The reply names the tools the assistant called to answer, each once, in the order first
called - `eligibleDrives`, `allDrives`, `myApplications`, `applyToDrive`,
`searchJobDescriptions` - and is empty when it answered without looking anything up:

```json
{ "reply": "You can apply to Zoho and Razorpay.", "toolsUsed": ["eligibleDrives"] }
```

**Streaming.** Both endpoints take the same body. `/chat/stream` answers with
[Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html), each
with JSON data:

```text
event:token
data:{"text":"You can apply to "}

event:token
data:{"text":"Zoho."}

event:done
data:{"reply":"You can apply to Zoho.","toolsUsed":["eligibleDrives"]}
```

Any number of `token` events, then exactly one `done` - the same body `/chat` answers
with - or, if the model provider fails
after the stream has begun (the status is already `200` by then), one `error` event whose
data is the usual problem detail with `"status": 502`. A `400` or `503` is decided before
streaming starts and arrives as an ordinary error response. The browser's `EventSource`
only sends `GET`, so the web app reads the stream with `fetch`.

---

## Errors

Every error has the same shape. A rejected body adds `errors`, one reason per field, which
the web app shows to the user:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/drives",
  "errors": {
    "cgpaCutoff": "must be less than or equal to 10.0",
    "backlogLimitConsistent": "Enter the maximum number of backlogs, since backlogs are allowed"
  }
}
```

| Status | Meaning here |
| --- | --- |
| `400` | the body or a parameter failed validation |
| `401` | no token, or an invalid or expired one |
| `403` | signed in, but this role may not call this endpoint |
| `404` | not found - or not yours (records you may not see answer `404`, never `403`) |
| `409` | well formed, but the current state does not allow it (closed drive, already applied...) |
| `413` | upload larger than 5 MB |
| `422` | a PDF with no readable text |
| `502` / `503` | the AI provider failed, or no API key is configured |

The rejected value is never echoed back - on a registration form it would be a password.
