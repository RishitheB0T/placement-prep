# Architecture

How Placement Prep is put together, from the browser down to the database, and why each
piece is shaped the way it is. For setup see [DEVELOPMENT.md](DEVELOPMENT.md); for every
endpoint see [API.md](API.md).

**Contents**

1. [The big picture](#1-the-big-picture)
2. [A request, layer by layer](#2-a-request-layer-by-layer)
3. [Signing in](#3-signing-in)
4. [Roles and who may call what](#4-roles-and-who-may-call-what)
5. [The life of an application](#5-the-life-of-an-application)
6. [Notifications and deadline reminders](#6-notifications-and-deadline-reminders)
7. [Resume feedback: a database table as a job queue](#7-resume-feedback-a-database-table-as-a-job-queue)
8. [RAG: job-description search by meaning](#8-rag-job-description-search-by-meaning)
9. [The AI assistant: tool calling](#9-the-ai-assistant-tool-calling)
10. [The data model](#10-the-data-model)
11. [Background jobs](#11-background-jobs)
12. [Design principles](#12-design-principles)

---

## 1. The big picture

A modular monolith: one Spring Boot backend split into feature modules, one React
single-page app, one PostgreSQL database that also stores vector embeddings.

```mermaid
flowchart LR
    subgraph CLIENT["🖥️ Browser"]
        SPA["<b>React 19 SPA</b><br/>pages · components<br/>Axios client with JWT"]
    end

    subgraph EDGE["🌐 One origin"]
        PROXY["<b>Vite dev proxy</b> (dev)<br/><b>nginx</b> (Docker)<br/>/api → backend"]
    end

    subgraph API["☕ Spring Boot 4"]
        SEC["🔒 Security<br/>JWT filter + rules"]
        MODS["<b>Modules</b><br/>auth · drive · application<br/>resume · knowledgebase<br/>agent · notification"]
        JOBS["⏱️ Scheduled jobs<br/>resume review · reminders<br/>file sweep"]
        EMB["🧮 Local embedding model<br/>all-MiniLM-L6-v2 (ONNX)"]
        SEC --> MODS
        MODS --- EMB
        JOBS --- MODS
    end

    DB[("🐘 <b>PostgreSQL 16</b><br/>+ pgvector<br/>schema by Flyway")]
    LLM["🤖 <b>Anthropic API</b><br/>Claude via Spring AI"]
    DISK[("📄 Resume PDFs<br/>on disk")]

    SPA <==>|"JSON + Bearer token"| PROXY
    PROXY <==> SEC
    MODS <==>|"JPA · JDBC"| DB
    EMB -.->|"384-dim vectors"| DB
    MODS <-.->|"chat · tool calls · PDF review"| LLM
    MODS <--> DISK

    classDef client fill:#dbeafe,stroke:#1d4ed8,stroke-width:2px,color:#1e3a8a
    classDef edge fill:#fef3c7,stroke:#b45309,stroke-width:2px,color:#78350f
    classDef api fill:#dcfce7,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef data fill:#f3e8ff,stroke:#7e22ce,stroke-width:2px,color:#581c87
    classDef ai fill:#fce7f3,stroke:#be185d,stroke-width:2px,color:#831843
    class SPA client
    class PROXY edge
    class SEC,MODS,JOBS,EMB api
    class DB,DISK data
    class LLM ai
```

| Piece | Choice | Why |
| --- | --- | --- |
| Shape | Modular monolith | One deployable and one database is the right size for one college; the module boundaries keep a later split possible |
| API style | REST + JSON, RFC 9457 errors | One error shape for the whole API, so one client-side handler covers everything |
| Auth | Stateless JWT | Any instance can serve any request; no session storage |
| Vectors | pgvector in the same PostgreSQL | No second database to run; SQL and similarity search side by side |
| Embeddings | Local ONNX model | Free, private, and works with no API key at all |
| Generation | Claude through Spring AI | Native PDF reading for resumes, and first-class tool calling for the assistant |

---

## 2. A request, layer by layer

Every request in the application takes this path. Nothing bypasses it.

```mermaid
flowchart TD
    User(["Student · coordinator · recruiter · admin"])

    subgraph BROWSER["🖥️ Browser"]
        direction TB
        Pages["<b>pages/*.tsx</b><br/>Dashboard · Drives · Applications<br/>Resumes · Assistant · Profile · PostDrive<br/><i>each branches on the role</i>"]
        ApiLayer["<b>api/*.ts</b><br/>auth · drives · applications · resumes<br/>notifications · agent · jobDescriptions<br/><i>one function per endpoint</i>"]
        Client["<b>api/client.ts</b> — one Axios instance<br/>request interceptor: Bearer token<br/>response interceptor: ApiError,<br/>clears the token on 401"]
        Storage[("localStorage<br/>placementprep.token")]

        Pages -->|"never call HTTP directly"| ApiLayer
        ApiLayer --> Client
        Client <-->|"read on every request"| Storage
    end

    PROXY["⚙️ <b>Vite proxy</b> (dev) / <b>nginx</b> (Docker)<br/>/api → :8081 · one origin, so no CORS"]

    subgraph SPRING["☕ Spring Boot — :8081"]
        direction TB

        subgraph SEC["🔒 Filter chain — before any controller"]
            direction TB
            JwtFilter["<b>JwtAuthenticationFilter</b><br/>verify signature + expiry,<br/>re-load the account,<br/>put the real role in context"]
            Rules["<b>SecurityConfig</b><br/>method + path vs the rule list<br/>default deny"]
            ErrHandler["<b>JsonAuthenticationErrorHandler</b><br/>401 no/bad token · 403 wrong role"]
            JwtFilter --> Rules
            Rules -.->|"refused"| ErrHandler
        end

        subgraph MVC["🧩 The module owning the path"]
            direction TB
            Controller["<b>controller/</b> — HTTP only<br/>@Valid runs Bean Validation,<br/>empty / refused becomes 404 / 409<br/><i>DTOs in and out, never entities</i>"]
            Service["<b>service/</b> — business rules<br/>@Transactional, readOnly on reads<br/><i>no web types, so a scheduled job<br/>calls the same methods</i>"]
            Repo["<b>repository/</b> — Spring Data JPA<br/>derived queries, native SQL<br/>for the eligibility filter"]
            Entity["<b>model/</b> — JPA entities<br/>ddl-auto: validate, never create"]
            Controller --> Service --> Repo --> Entity
        end

        Valid["<b>ValidationErrorHandler</b><br/>adds field → reason to every 400"]

        SEC ==>|"authorised"| MVC
        Controller -.->|"body rejected"| Valid
    end

    DB[("🐘 <b>PostgreSQL 16 + pgvector</b><br/>schema owned by Flyway")]

    User -->|"clicks"| Pages
    Client ==>|"HTTP + JSON<br/>Authorization: Bearer …"| PROXY
    PROXY ==> JwtFilter
    Entity ==>|"JDBC via HikariCP"| DB

    MVC -.->|"DTO as JSON"| Client
    ErrHandler -.->|"RFC 9457 problem+json"| Client
    Valid -.->|"400 + errors map"| Client

    classDef browser fill:#dbeafe,stroke:#1e40af,stroke-width:2px,color:#1e3a8a
    classDef edge fill:#fef3c7,stroke:#b45309,stroke-width:2px,color:#78350f
    classDef spring fill:#dcfce7,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef security fill:#fee2e2,stroke:#b91c1c,stroke-width:2px,color:#7f1d1d
    classDef data fill:#f3e8ff,stroke:#7e22ce,stroke-width:2px,color:#581c87
    classDef actor fill:#f1f5f9,stroke:#475569,stroke-width:2px,color:#0f172a
    class Pages,ApiLayer,Client browser
    class Storage,DB data
    class PROXY edge
    class Controller,Service,Repo,Entity,Valid spring
    class JwtFilter,Rules,ErrHandler security
    class User actor
```

| Claim | Why it matters |
| --- | --- |
| **The token travels on every request.** | `api/client.ts` attaches `Authorization: Bearer <token>` to all calls. There is no session and no cookie; the server keeps nothing between requests. |
| **The role is re-read from the database on every request.** | `JwtAuthenticationFilter` does not trust the `role` claim inside the token, so a promotion takes effect on the *next* request. The browser catches up too: when the profile and the token disagree, it fetches a fresh token from `/api/auth/refresh`, so the new pages appear without signing in again. |
| **A 401 or 403 never reaches a controller.** | The rules run inside the filter chain, before dispatch. A controller method never needs a role check of its own. |
| **Controllers never see or return an entity.** | Everything crossing the HTTP boundary is a DTO, so the database shape and the API shape change independently. |
| **Services never decide status codes.** | A service returns `Optional`/`boolean` or throws a domain exception naming the reason; the controller maps that to `404`/`409`. The same method stays callable from a scheduled job. |
| **Frontend role checks are convenience, never security.** | Hiding "Post a drive" from a student is a nicety. What actually stops them is the backend answering `403` - which is what the tests assert. |
| **No CORS configuration exists anywhere.** | The Vite proxy (development) and nginx (Docker) make the browser see a single origin. |

---

## 3. Signing in

One login endpoint, three tabs. The tabs are the *same* HTTP call; what differs is what the
frontend does with the answer.

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant L as Login page
    participant A as AuthController
    participant S as AuthService
    participant DB as PostgreSQL

    U->>L: pick a tab, submit email + password
    L->>A: POST /api/auth/login
    A->>S: login(request)
    S->>DB: find the account (email lowercased)

    alt unknown email OR wrong password
        S-->>A: Optional.empty()
        A-->>L: 401 "Invalid email or password"
        Note over A,L: Identical for both cases, so the endpoint<br/>never reveals who is registered
    else credentials correct
        S-->>A: AuthResponse(token, role, expiresAt)
        A-->>L: 200
        L->>L: store the token
        alt Recruiter tab and role ≠ RECRUITER,<br/>or Administration tab and role ≠ TNP_PIC
            L->>L: discard the token
            L-->>U: "This is not a recruiter / an administration account"
        else Student tab (any role), or the matching tab
            L-->>U: open /dashboard - the view for that role
        end
    end
```

- **Registration** always creates a `STUDENT` and accepts college addresses only
  (`name@<dept>.nits.ac.in`; a dot before `nits.ac.in` blocks lookalikes such as
  `notnits.ac.in`). The request has no role field, so nobody can register as staff.
- **The token** is HMAC-SHA256 signed by `JwtService`, expires after 24 hours, and carries
  the email and role. Its payload is readable by anyone, so nothing secret goes in it.
- **Recruiters** cannot register: the admin creates their account with a company email.
- **Passwords** are BCrypt-hashed. Any account can change its own password (the current
  one is checked again); the admin can set a temporary one for someone who forgot theirs.

---

## 4. Roles and who may call what

| Role | Shown as | What it can do |
| --- | --- | --- |
| `STUDENT` | Student | Browse and search drives, see the ones they qualify for, apply and withdraw, get notifications, resume feedback, the assistant |
| `TNP_COORDINATOR` | Coordinator | Everything a student can (a coordinator *is* a student) **plus** post, edit and delete drives and review every applicant |
| `RECRUITER` | Recruiter | A company's hiring contact: posts drives and reviews applicants **for their own drives only**. Not a student |
| `TNP_PIC` | Admin | The person in charge: everything on the hiring side, plus adding recruiters, promoting students, resetting passwords. Not a student |

```mermaid
flowchart TD
    T0["<b>🚫 NO TOKEN</b><br/>━━━━━━━━━━━━━━━━━━━━━━━━<br/>register · login · health<br/><i>everything else → 401</i>"]
    T1["<b>🎓 STUDENT</b><br/>━━━━━━━━━━━━━━━━━━━━━━━━<br/>my profile · drives · eligible drives<br/>apply · withdraw · my applications<br/>notifications · resumes · assistant"]
    T2["<b>📋 COORDINATOR</b> — the above, plus<br/>━━━━━━━━━━━━━━━━━━━━━━━━<br/>post · edit · delete any drive<br/>applicants · decisions · stats"]
    TR["<b>💼 RECRUITER</b> — hiring only<br/>━━━━━━━━━━━━━━━━━━━━━━━━<br/>the same hiring tools,<br/><b>for drives they posted only</b><br/><i>other drives answer 404</i>"]
    T3["<b>⭐ ADMIN</b> — hiring, plus<br/>━━━━━━━━━━━━━━━━━━━━━━━━<br/>add recruiters · promote students<br/>reset passwords · list accounts"]

    T0 -->|"register"| T1
    T1 -->|"promoted by the admin —<br/>keeps every student ability"| T2
    T3 -->|"creates the account"| TR
    T2 -.->|"403 — the cell cannot<br/>staff itself"| T3

    classDef anon fill:#f1f5f9,stroke:#475569,stroke-width:3px,color:#0f172a
    classDef stu fill:#dbeafe,stroke:#1e40af,stroke-width:3px,color:#1e3a8a
    classDef coord fill:#fef3c7,stroke:#b45309,stroke-width:3px,color:#78350f
    classDef rec fill:#d1fae5,stroke:#047857,stroke-width:3px,color:#064e3b
    classDef pic fill:#fee2e2,stroke:#b91c1c,stroke-width:3px,color:#7f1d1d
    class T0 anon
    class T1 stu
    class T2 coord
    class TR rec
    class T3 pic
```

**Two layers of protection.** `SecurityConfig` decides *which roles* may call an endpoint
(`403` otherwise). Ownership lookups decide *which rows* a caller may touch (`404`
otherwise): a student's application is found by `(id, studentId)`, a notification by
`(id, userId)`, a recruiter's drive by `(id, created_by)`. Answering `404` instead of `403`
means a caller can never confirm that someone else's record exists.

**"Is this account management?" and "is this account a student?" are independent
questions**, asked separately on both sides: `MANAGERS` and `STUDENTS` in `SecurityConfig`,
mirrored by `canManageDrives` and `isStudentRole` in `frontend/src/api/roles.ts`. A
coordinator answers yes to both, so they see **Apply** and **Delete** on the same card.

**How "a recruiter sees only their own drives" works.** Every drive records who posted it
(`drives.created_by`). `UserProfileDTO.driveScope()` turns the signed-in account into a
*scope* - the recruiter's own id, or empty for everyone else - and the controllers pass it
to `DriveService`, where one private `findVisible(id, scope)` applies the rule to every
scoped read and write. The account lookup fails closed: a missing account is a `404`,
never "no scope".

---

## 5. The life of an application

```mermaid
stateDiagram-v2
    direction TB
    [*] --> APPLIED: student applies<br/>POST /api/applications

    APPLIED --> SHORTLISTED: hiring side
    APPLIED --> REJECTED: hiring side
    APPLIED --> SELECTED: hiring side
    SHORTLISTED --> SELECTED: hiring side
    SHORTLISTED --> REJECTED: hiring side

    APPLIED --> WITHDRAWN: student pulls out
    SHORTLISTED --> WITHDRAWN: student pulls out
    WITHDRAWN --> APPLIED: student applies again<br/>(the same row reopens)

    SELECTED --> [*]
    REJECTED --> [*]

    note right of WITHDRAWN
        UNIQUE (student_id, drive_id): one row per pair,
        so "apply again" reopens it. Only APPLIED and
        SHORTLISTED can be withdrawn, and a WITHDRAWN
        application cannot be decided - a decision can
        never be undone by withdrawing and re-applying.
    end note
```

Applying is checked in one place, `ApplicationService.apply`, shared by the **Apply**
button and the assistant's `applyToDrive` tool:

| # | Check | If it fails |
| --- | --- | --- |
| 1 | The drive exists | `404` |
| 2 | No active application already | `409` |
| 3 | The deadline has not passed | `409` |
| 4 | The profile is complete | `409` |
| 5 | The stored marks meet the drive's criteria - the same query as "Eligible for me" | `409` |

A refusal is a domain exception (`ApplicationRefusedException` with a `Reason`), so the
service stays free of HTTP types: the controller maps the reason to a status, and the
assistant hands the message back to the model. Every status change sends the student a
notification, written in the same transaction.

**The eligibility rule.** A drive is open to a student when every cutoff is met - each
written `(cutoff IS NULL OR cutoff <= :value)`, because in SQL a comparison with `NULL` is
neither true nor false - the branch is in the drive's list (case-insensitive), backlogs are
within what the drive allows, and the deadline is in the future.

---

## 6. Notifications and deadline reminders

```mermaid
flowchart LR
    JOB["⏱️ <b>DeadlineReminderJob</b><br/>every 15 min"]
    S["Students and coordinators<br/>with a complete profile"]
    E["Their eligible open drives<br/><i>same query as the board</i>"]
    A{"Already<br/>applied?"}
    T{"Time left"}
    N1["🆕 NEW_DRIVE<br/>more than 3 days"]
    N3["⏰ DEADLINE_3_DAYS"]
    N4["🚨 DEADLINE_1_DAY"]
    DB[("notifications<br/>unique (user, drive, kind)")]
    APP["ApplicationService<br/>status changed"]
    N5["✅ STATUS_CHANGED"]
    BELL["🔔 Bell in the header"]

    JOB --> S --> E --> A
    A -- "yes" --> X(["skip"])
    A -- "no" --> T
    T -- "> 3 days" --> N1 --> DB
    T -- "≤ 3 days" --> N3 --> DB
    T -- "≤ 1 day" --> N4 --> DB
    APP --> N5 --> DB
    DB --> BELL
```

- **At most once.** Each reminder is sent once per student, drive and kind - checked
  before inserting, and enforced by a *partial unique index* in the database
  (`WHERE kind <> 'STATUS_CHANGED'`, because an application can be shortlisted and later
  selected). That is what makes running the job every few minutes safe.
- **Fault isolation.** Each notification is its own short transaction, so one failing
  student is logged and skipped without undoing anyone else's reminders.
- **The bell** loads when the header mounts, shows an unread badge, and marks a
  notification read when it is opened; "mark all read" is one `UPDATE`.

---

## 7. Resume feedback: a database table as a job queue

```mermaid
sequenceDiagram
    autonumber
    actor S as Student
    participant UI as Resumes page
    participant C as ResumeController
    participant RS as ResumeService
    participant J as ResumeReviewJob
    participant V as pgvector
    participant AI as Claude

    S->>UI: drop a PDF, pick a drive
    UI->>C: POST /api/resumes (multipart)
    C->>C: size, %PDF- magic bytes, drive exists
    C->>RS: upload
    RS->>RS: save <uuid>.pdf to disk, insert row PENDING
    C-->>UI: 201 PENDING - returns at once
    loop every 5 seconds
        J->>RS: claim the oldest PENDING → PROCESSING
        J->>V: retrieve the drive's job-description passages
        J->>AI: the PDF + the job + the passages
        AI-->>J: {score, summary, strengths, improvements}
        J->>RS: COMPLETED (or FAILED, with a reason)
    end
    UI->>C: poll GET /api/resumes/me every 4 s
    C-->>UI: the review
```

- **The `resumes` table is the queue.** No message broker: work survives a restart, and
  rows stuck in `PROCESSING` after a shutdown are put back on startup.
- **No connection is held during the slow call.** Claiming a row is its own short
  transaction; the model call happens outside any transaction.
- **The PDF goes to Claude as a native document**, so no PDF parsing is needed, and the
  reply is parsed straight into a Java record with `.entity(Review.class)`.
- **Grounded in the real job description.** Before each review, the job retrieves the most
  relevant passages of the drive's uploaded job-description PDF from pgvector - the
  retrieval step of RAG - so feedback can say whether the resume shows what the job asks
  for. If retrieval fails, the review still runs without them.
- **Safe storage.** The server names every file (`<uuid>.pdf`), so the client's filename
  is never used as a path. A sweep every six hours deletes files whose row is gone (a
  deleted drive cascades its rows, but a database cannot delete files).

---

## 8. RAG: job-description search by meaning

**RAG** (retrieval-augmented generation): find the passages relevant to a question, then
give them to the language model so it answers from real documents instead of memory.

```mermaid
flowchart LR
    subgraph INDEX["📥 Indexing — when a JD PDF is uploaded"]
        direction TB
        PDF["Job-description PDF"] --> READ["<b>PagePdfDocumentReader</b><br/>one document per page"]
        READ --> SPLIT["<b>TokenTextSplitter</b><br/>~200-token passages"]
        SPLIT --> LABEL["clean whitespace,<br/>label: driveId · company · role"]
        LABEL --> EMBED1["<b>all-MiniLM-L6-v2</b><br/>384 numbers per passage"]
    end

    STORE[("🐘 <b>pgvector</b><br/>job_description_chunks<br/>HNSW index · cosine")]

    subgraph QUERY["🔎 Retrieval — search, assistant, resume review"]
        direction TB
        Q["question in plain English"] --> EMBED2["the same model<br/>→ 384 numbers"]
        EMBED2 --> NN["nearest passages<br/>by cosine distance"]
    end

    EMBED1 --> STORE
    STORE --> NN
    NN --> SEARCH["Search panel<br/>ranked matches"]
    NN --> TOOL["Assistant's<br/>searchJobDescriptions tool"]
    NN --> REVIEW["Resume review<br/>prompt context"]
    TOOL --> GEN["🤖 Claude answers<br/>from the passages"]
    REVIEW --> GEN
```

- **Search by meaning, not words.** "server-side programming with databases" finds a job
  description that says "Java, Spring Boot, SQL" with no word in common - a test proves it
  against a real pgvector database.
- **Local and pinned.** The embedding model runs inside the JVM (ONNX Runtime), needs no
  API key, and is pinned to one model revision so stored vectors stay comparable.
- **Replace, not append.** Re-uploading a drive's PDF deletes its old passages in the same
  transaction; deleting the drive removes them too.
- **Scoped retrieval.** The resume reviewer filters by `driveId`, so a review is only ever
  judged against its own drive's job description.

---

## 9. The AI assistant: tool calling

**Tool calling**: the model is told which Java methods exist. Instead of answering
directly it can reply "call this method with these arguments"; Spring AI runs the method,
sends the result back, and the model continues with real data.

```mermaid
sequenceDiagram
    autonumber
    actor S as Student
    participant AS as AgentService
    participant AI as Claude
    participant T as AssistantTools
    participant DB as PostgreSQL / pgvector

    S->>AS: "Apply me to the Zoho drive"
    AS->>AI: message + history + 5 tool descriptions
    AI-->>AS: call eligibleDrives()
    AS->>T: eligibleDrives()
    T->>DB: the eligibility query, with the student's own marks
    DB-->>T: open drives
    T-->>AI: JSON result
    AI-->>AS: call applyToDrive(driveId=12)
    AS->>T: applyToDrive(12)
    T->>DB: ApplicationService.apply - the same rules as the button
    T-->>AI: "Applied to Zoho - SDE"
    AI-->>S: "Done - you have applied to Zoho."
```

| Tool | Does |
| --- | --- |
| `eligibleDrives()` | Open drives the student qualifies for |
| `allDrives()` | Every drive on the board |
| `myApplications()` | The student's applications, with company names |
| `applyToDrive(driveId)` | Applies through `ApplicationService.apply` - every rule still applies |
| `searchJobDescriptions(query)` | The RAG search above |

**Safe by construction.** A new `AssistantTools` object is built for each request, bound
to the signed-in student; no tool takes a student id, so neither the model nor a cleverly
worded message can reach another student's data. Chat history is sent as typed message
objects rather than pasted into a template, at most 8 tool calls run per message, and the
server stores no conversation.

---

## 10. The data model

```mermaid
erDiagram
    USERS ||--o{ APPLICATIONS : submits
    DRIVES ||--o{ APPLICATIONS : receives
    USERS |o--o{ DRIVES : "posts (created_by)"
    USERS ||--o{ RESUMES : uploads
    DRIVES ||--o{ RESUMES : "reviewed against"
    USERS ||--o{ NOTIFICATIONS : receives
    DRIVES |o--o{ NOTIFICATIONS : about
    DRIVES ||..o{ JOB_DESCRIPTION_CHUNKS : "metadata.driveId"

    USERS {
        bigint id PK
        varchar email UK "lowercased"
        varchar password_hash "BCrypt"
        varchar role "STUDENT, TNP_COORDINATOR, RECRUITER, TNP_PIC"
        numeric cgpa "student only"
        varchar branch "student only"
        numeric tenth_percentage
        numeric twelfth_percentage
        integer backlogs
        varchar designation "admin only, by CHECK"
        varchar staff_id UK "admin only"
    }
    DRIVES {
        bigint id PK
        varchar company_name
        varchar role
        numeric ctc
        integer tier "1 to 3"
        numeric cgpa_cutoff
        numeric tenth_cutoff "null = no rule"
        numeric twelfth_cutoff "null = no rule"
        boolean backlogs_allowed
        integer max_backlogs
        varchar_array eligible_branches
        timestamptz application_deadline
        bigint created_by FK "SET NULL"
    }
    APPLICATIONS {
        bigint id PK
        bigint student_id FK
        bigint drive_id FK "CASCADE"
        varchar status
        text note
        timestamptz applied_at
    }
    RESUMES {
        bigint id PK
        bigint student_id FK
        bigint drive_id FK "CASCADE"
        varchar storage_key UK "uuid.pdf"
        varchar status "the job queue"
        integer score "0 to 100"
        text_array strengths
        text_array improvements
    }
    NOTIFICATIONS {
        bigint id PK
        bigint user_id FK "CASCADE"
        bigint drive_id FK "CASCADE"
        varchar kind
        varchar message
        timestamptz read_at "null = unread"
    }
    JOB_DESCRIPTION_CHUNKS {
        uuid id PK
        text content
        json metadata "driveId, company, role"
        vector embedding "384 dims, HNSW"
    }
```

- **One `users` table for every role** keeps sign-in a single lookup. Student-only columns
  are nullable (marks are filled in after registering); the admin's staff columns are
  guarded by a `CHECK` - `role = 'TNP_PIC' OR (all IS NULL)`.
- **Cascades follow meaning.** Deleting a drive removes its applications, resumes,
  notifications and passages. Deleting a recruiter's account *keeps* their drives
  (`created_by` becomes `NULL` and the drive passes back to the cell).
- **Flyway owns the schema** (15 migrations); Hibernate only validates. An applied
  migration is never edited - Flyway checksums every file.

---

## 11. Background jobs

| Job | Runs | Does |
| --- | --- | --- |
| Resume review | every 5 s | drains the `PENDING` queue (section 7) |
| Requeue on startup | once, when the app is ready | puts interrupted `PROCESSING` reviews back |
| Deadline reminders | every 15 min, first after 1 min | section 6 |
| Orphan file sweep | every 6 h | deletes stored PDFs no row points at, if over an hour old |

All use `fixedDelay`, so a run never overlaps itself, and each job has a scheduler thread
of its own, so a slow model call never holds up the reminders. In tests, the automatic
runs are pushed a day out and tests trigger the jobs themselves.

### Threads

| Work | Runs on | Why |
| --- | --- | --- |
| Every HTTP request | a **Java 21 virtual thread** (`spring.threads.virtual.enabled`) | A request waiting on the database or the model parks its virtual thread, which costs next to nothing. With Tomcat's 200 pooled threads, 200 slow assistant chats would leave every other request queueing. |
| The three scheduled jobs | a pool of **3 platform threads**, declared in `PlacementprepApplication` | With virtual threads on, Spring Boot's own scheduler runs every fixed-delay job on *one* shared thread. A pool keeps the jobs independent; three jobs gain nothing from virtual threads. |

`PlacementprepApplicationTests` covers both: a real HTTP request must arrive on a virtual
thread, and a second job must start while the first is still busy.

---

## 12. Design principles

1. **Controllers speak HTTP, services speak the domain.** DTOs at the boundary; `Optional`,
   `boolean` or a reason-carrying domain exception inside; status codes only in controllers.
2. **One request record per resource** serves both `POST` and `PUT`; cross-field rules are
   `@AssertTrue` methods on the record.
3. **Validation mirrors the columns.** Every size and range matches the column it fills,
   so a value the database cannot hold is a `400` naming the field, never a `500`.
4. **Fail closed.** Default-deny security rules; a lookup failure never widens access.
5. **One place per rule.** Eligibility is one query; apply rules are one method; role lists
   are one constant on each side.
6. **Real dependencies in tests.** Integration tests run against PostgreSQL + pgvector in
   Docker (Testcontainers) with real signed tokens; the AI loop runs against a fake
   Anthropic server.

| Trade-off | Chosen | Cost accepted |
| --- | --- | --- |
| Token in `localStorage` | simple, survives refresh, nothing to forge with CSRF | readable by any script on the page |
| Table as a job queue | restart-safe, nothing extra to run | one backend instance (no row locking yet) |
| In-app notifications | no mail server | nobody is told while away from the site |
| Admin password reset | works without email | the admin hands over a temporary password |
| Local embeddings | free and private | ~350 MB first download |
