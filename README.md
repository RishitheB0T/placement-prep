# placement-prep

A campus placement preparation platform: the training-and-placement (TNP) cell posts
placement drives, students see only the ones they actually qualify for, and AI features
help them prepare.

## Status

| Feature | State |
| --- | --- |
| Drives board - TNP CRUD and the student eligibility filter | **Built** |
| Applications - a student applying to a drive, and the cell tracking status | **Built** |
| Accounts, login and three-tier roles | **Built** - Spring Security + JWT |
| Separate Administration sign-in and staff profile for the person in charge | **Built** |
| Web UI - register, sign in, profile, drives board, applications, posting a drive | **Built** |
| Resume upload with AI feedback | Planned - background job queue |
| Q&A over placement notices | Planned - RAG with Spring AI + pgvector |
| Company-wise AI mock interviews | Planned |
| Deadline reminders | Planned - scheduled jobs |

The first TNP account (the person in charge) is seeded by a migration - see
[Signing in as the person in charge](#signing-in-as-the-person-in-charge).

## Stack

**Backend** - Java 21, Maven, Spring Boot 4.1, Spring Web MVC, Spring Security 7, Bean
Validation, Spring Data JPA, Flyway, Actuator, Testcontainers.
**Frontend** - React 19, TypeScript, Vite, Tailwind CSS v4, React Router v7.
**Infrastructure** - PostgreSQL 16 with the pgvector extension, via Docker Compose.

## How it fits together

Five views of the same system, from the outside in. Everything below describes the
development setup this repository actually runs - `npm run dev` for the frontend and
`mvnw spring-boot:run` for the backend. There is no production build or deployment story
here yet, so none of these diagrams invent one.

### 1. Frontend to backend, layer by layer

Every request in the application takes this path. Nothing bypasses it.

```mermaid
flowchart TD
    User(["Student, coordinator<br/>or person in charge"])

    subgraph BROWSER["🖥️ Browser — http://localhost:5173"]
        direction TB
        Pages["<b>pages/*.tsx</b><br/>Login · Register · Profile<br/>Drives · Applications · PostDrive<br/><i>each branches on the role</i>"]
        ApiLayer["<b>api/*.ts</b><br/>auth · drives · applications<br/><i>one function per endpoint</i>"]
        Client["<b>api/client.ts</b><br/>the single fetch wrapper<br/>attaches the Bearer token,<br/>unwraps errors, clears on 401"]
        Storage[("localStorage<br/>placementprep.token")]

        Pages -->|"never fetch() directly"| ApiLayer
        ApiLayer --> Client
        Client <-->|"read every request"| Storage
    end

    subgraph VITE["⚙️ Vite dev server — :5173"]
        direction TB
        Proxy["<b>server.proxy</b><br/>/api + /actuator → :8081<br/><i>one origin, so no CORS anywhere</i>"]
    end

    subgraph SPRING["☕ Spring Boot — :8081"]
        direction TB

        subgraph SEC["🔒 Filter chain — before any controller"]
            direction TB
            JwtFilter["<b>JwtAuthenticationFilter</b><br/>verify signature + expiry,<br/>load the account,<br/>put the real role in context"]
            Rules["<b>SecurityConfig</b><br/>method + path vs the rule list<br/>permitAll · authenticated · hasRole"]
            ErrHandler["<b>JsonAuthenticationErrorHandler</b><br/>401 no/bad token · 403 wrong role"]
            JwtFilter --> Rules
            Rules -.->|"refused"| ErrHandler
        end

        subgraph MVC["🧩 The module owning the path"]
            direction TB
            Controller["<b>controller/</b> — HTTP only<br/>@Valid runs Bean Validation,<br/>Optional.empty() becomes 404/409<br/><i>DTOs in and out, never entities</i>"]
            Service["<b>service/</b> — business rules<br/>@Transactional, readOnly on reads<br/><i>no web types, so a job could<br/>call the same method</i>"]
            Repo["<b>repository/</b> — Spring Data JPA<br/>derived queries, native SQL for<br/>arrays and full-text"]
            Entity["<b>model/</b> — JPA entities<br/>ddl-auto: validate, never create"]
            Controller --> Service --> Repo --> Entity
        end

        Valid["<b>ValidationErrorHandler</b><br/>adds field → message to every 400"]

        SEC ==>|"authorised"| MVC
        Controller -.->|"body rejected"| Valid
    end

    DB[("🐘 <b>PostgreSQL 16 + pgvector</b> — :5433<br/>users · drives · applications<br/>schema owned by Flyway")]

    User -->|"clicks"| Pages
    Client ==>|"HTTP + JSON<br/>Authorization: Bearer …"| Proxy
    Proxy ==> JwtFilter
    Entity ==>|"JDBC via HikariCP"| DB

    DB -.->|"rows"| Entity
    MVC -.->|"DTO as JSON"| Client
    ErrHandler -.->|"RFC 9457 problem+json"| Client
    Valid -.->|"400 + errors map"| Client

    classDef browser fill:#dbeafe,stroke:#1e40af,stroke-width:2px,color:#1e3a8a
    classDef vite fill:#fef3c7,stroke:#b45309,stroke-width:2px,color:#78350f
    classDef spring fill:#dcfce7,stroke:#15803d,stroke-width:2px,color:#14532d
    classDef security fill:#fee2e2,stroke:#b91c1c,stroke-width:2px,color:#7f1d1d
    classDef data fill:#f3e8ff,stroke:#7e22ce,stroke-width:2px,color:#581c87
    classDef actor fill:#f1f5f9,stroke:#475569,stroke-width:2px,color:#0f172a

    class Pages,ApiLayer,Client browser
    class Storage data
    class Proxy vite
    class Controller,Service,Repo,Entity,Valid spring
    class JwtFilter,Rules,ErrHandler security
    class DB data
    class User actor
```

**What this diagram is being precise about:**

| Claim | Why it matters |
| --- | --- |
| **The token travels on every request, not just at login.** | `api/client.ts` attaches `Authorization: Bearer <token>` to all calls. There is no session and no cookie; the server keeps nothing between requests. |
| **The role is re-read from the database on every request.** | `JwtAuthenticationFilter` does not trust the `role` claim inside the token. A promotion, or an account that has been changed, takes effect on the *next* request rather than whenever the token happens to expire (up to 24 hours later). |
| **A 401 or 403 never reaches a controller.** | `SecurityConfig`'s rules run inside the filter chain, before dispatch. A student calling a PIC-only endpoint is refused there; the controller method never executes, so it needs no role check of its own. |
| **Controllers never see or return an entity.** | Everything crossing the HTTP boundary is a DTO. `model/` (the database shape) and `dto/` (the public API shape) are free to change on different days without one breaking the other. |
| **Services return `Optional`/`boolean`, never a status code.** | Deciding that absent means `404`, or that a clash means `409`, is the controller's job. The same service method stays callable from a future scheduled job or CLI command with no HTTP request in sight. |
| **The frontend's role checks are convenience, never security.** | Hiding "Post a drive" from a student is a nicety. The thing that actually stops them is the backend answering `403` — which is exactly what the test suite asserts. |
| **No CORS configuration exists anywhere.** | The Vite proxy makes the browser see a single origin. Remove the proxy and you would need CORS; with it, the question never arises. |

### 2. Signing in — and the two doors

There is one login endpoint. The Student and Administration tabs are the *same* HTTP call;
what differs is what the frontend does with the answer. The backend cannot enforce the
split, because which tab was clicked is not something it has any reason to know before the
password has even been checked.

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
    Note over L,A: Public route — no token needed
    A->>S: login(request)
    S->>DB: findByEmail(lowercased)

    alt unknown email OR wrong password
        DB-->>S: absent / hash mismatch
        S-->>A: Optional.empty()
        A-->>L: 401 "Invalid email or password"
        Note over A,L: Identical for both cases, on purpose:<br/>naming which one failed would reveal<br/>who is registered
        L-->>U: show error, stay on form
    else credentials correct
        DB-->>S: user row
        S->>S: generateToken(email, role)
        S-->>A: AuthResponse(token, role, expiresAt)
        A-->>L: 200 + AuthResponse
        L->>L: setToken() → localStorage

        alt Administration tab, role ≠ TNP_PIC
            L->>L: logout() — discard that token
            L-->>U: "Not an administration account"
            Note over L,U: Password WAS correct. Token is thrown<br/>away anyway — leaving it would make the<br/>next page load act signed-in
        else Student tab, or Admin tab + TNP_PIC
            L-->>U: navigate to /drives
        end
    end
```

Only the **Administration** tab is enforced, and only against `TNP_PIC`. The **Student**
tab is the original, unrestricted form: it accepts any account, including a coordinator or
a PIC, exactly as it did before the toggle existed. A coordinator was never given a door of
their own, so locking them out of the one they already used would have been a regression,
not a feature.

### 3. Who may call what

The rule list in `SecurityConfig` in one picture. Everything not drawn here falls through
to `anyRequest().authenticated()` — a default deny, so a new endpoint is locked until a
rule is written for it.

Roles are cumulative *up to the coordinator* — a coordinator is a student who also runs the
board, so they keep every student endpoint. The person in charge breaks the chain: they are
faculty rather than a student, so they hold the cell's tools and their own extras but none
of the student tier.

```mermaid
flowchart TD
    T0["<b>🚫 NO TOKEN</b><br/>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<br/>POST /api/auth/register<br/>POST /api/auth/login<br/>GET /actuator/health<br/><br/><i>everything else → 401</i>"]

    T1["<b>🎓 STUDENT</b> — any signed-in account<br/>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<br/>GET · PUT /api/users/me<br/>GET /api/drives<br/>GET /api/drives/{id}<br/>GET /api/drives/eligible<br/>POST /api/applications<br/>GET /api/applications/me<br/>POST /api/applications/{id}/withdraw"]

    T2["<b>📋 TNP_COORDINATOR</b> — the above, plus<br/>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<br/>POST /api/drives<br/>PUT /api/drives/{id}<br/>DELETE /api/drives/{id}<br/>GET /api/applications/drive/{driveId}<br/>PATCH /api/applications/{id}/status"]

    T3["<b>⭐ TNP_PIC</b> — the cell's tools, plus<br/>━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━<br/>POST /api/users/{id}/promote<br/>PUT /api/users/me/staff-profile<br/>and the Administration sign-in tab<br/><br/><i>but NOT the student tier — faculty,<br/>so no CGPA and no applying</i>"]

    T0 -->|"sign in"| T1
    T1 -->|"promoted by a PIC —<br/>keeps every student ability"| T2
    T2 -->|"seeded in the database only —<br/>no API route grants this"| T3

    T1 -.->|"403 — a coordinator's<br/>endpoints are closed"| T2
    T2 -.->|"403 — the cell cannot<br/>staff itself"| T3

    classDef anon fill:#f1f5f9,stroke:#475569,stroke-width:3px,color:#0f172a
    classDef stu fill:#dbeafe,stroke:#1e40af,stroke-width:3px,color:#1e3a8a
    classDef coord fill:#fef3c7,stroke:#b45309,stroke-width:3px,color:#78350f
    classDef pic fill:#fee2e2,stroke:#b91c1c,stroke-width:3px,color:#7f1d1d

    class T0 anon
    class T1 stu
    class T2 coord
    class T3 pic
```

Two details worth reading twice:

- **`/me` paths carry no id.** `GET /api/users/me` takes the account from the verified
  token, so there is no parameter a caller could change to read somebody else's profile.
  The only endpoint that names another account is promotion, and that is exactly why it is
  restricted to the person in charge.
- **Withdrawing is scoped by two keys.** `ApplicationService.withdraw` looks the row up by
  `(id, studentId)` together. Guessing another student's application id does not return a
  `403` — it returns a `404`, because the row simply does not match. A `403` would confirm
  the id exists.

### 4. The life of an application

A student applies; the cell decides. The one loop in this diagram is the part that changed
most recently: a withdrawal is no longer a dead end.

```mermaid
stateDiagram-v2
    direction TB

    [*] --> APPLIED: student clicks Apply<br/>POST /api/applications

    APPLIED --> SHORTLISTED: cell · PATCH status
    APPLIED --> REJECTED: cell · PATCH status
    APPLIED --> SELECTED: cell · PATCH status
    SHORTLISTED --> SELECTED: cell · PATCH status
    SHORTLISTED --> REJECTED: cell · PATCH status

    APPLIED --> WITHDRAWN: student pulls out<br/>POST /{id}/withdraw
    SHORTLISTED --> WITHDRAWN: student pulls out

    WITHDRAWN --> APPLIED: student applies again<br/>same row reopens, note cleared,<br/>appliedAt reset to now

    SELECTED --> [*]
    REJECTED --> [*]

    note right of WITHDRAWN
        UNIQUE (student_id, drive_id) means there is
        never a second row for the same pair, so
        "apply again" has to mean "reopen this row".
        Only WITHDRAWN reopens — applying again after
        REJECTED is a 409, because that was a decision
        the cell made, not one to quietly erase.
    end note

    note right of APPLIED
        A student can only ever reach APPLIED and
        WITHDRAWN. Every other transition is the
        placement cell's, enforced by SecurityConfig
        restricting PATCH .../status to staff.
    end note
```

### 5. The data model

Three tables. The `applications` table is the only one with real foreign keys, because it
is the only one that refers to anything else.

```mermaid
erDiagram
    USERS ||--o{ APPLICATIONS : "submits"
    DRIVES ||--o{ APPLICATIONS : "receives"

    USERS {
        bigint id PK
        varchar email UK "lowercased on write"
        varchar password_hash "BCrypt"
        varchar role "STUDENT or the two TNP roles"
        numeric cgpa "student only"
        varchar branch "student only, uppercase"
        numeric tenth_percentage "student only"
        numeric twelfth_percentage "student only"
        integer backlogs "NOT NULL, default 0"
        varchar designation "PIC only"
        varchar department "PIC only"
        varchar staff_id UK "PIC only"
        varchar office_location "PIC only"
        varchar phone_number "PIC only"
        timestamptz created_at
        timestamptz updated_at
    }

    DRIVES {
        bigint id PK
        varchar company_name "NOT NULL"
        varchar role "the job title"
        numeric ctc "CHECK gt 0"
        integer tier "NOT NULL"
        numeric cgpa_cutoff "null means no rule"
        numeric tenth_cutoff "null means no rule"
        numeric twelfth_cutoff "null means no rule"
        boolean backlogs_allowed "NOT NULL"
        integer max_backlogs "null means no cap"
        varchar eligible_branches "array, NOT NULL"
        timestamptz application_deadline "indexed"
        text description "nullable"
        timestamptz created_at
        timestamptz updated_at
    }

    APPLICATIONS {
        bigint id PK
        bigint student_id FK "to users"
        bigint drive_id FK "to drives, CASCADE"
        varchar status "APPLIED to WITHDRAWN"
        text note "the cell's comment"
        timestamptz applied_at "reset on reopen"
        timestamptz updated_at
    }
```

**Why the columns sit where they do.** All three kinds of account live in one `users`
table, which keeps sign-in a single lookup rather than a search across three tables. The
cost is columns that only apply to some rows, and the schema is explicit about that rather
than relying on convention:

- `cgpa`, `branch`, `tenth_percentage`, `twelfth_percentage` are the student's, and are
  nullable because an account exists from the moment it registers while the marks are
  filled in afterwards.
- `designation`, `department`, `staff_id`, `office_location`, `phone_number` belong to the
  person in charge alone, enforced by a `CHECK` constraint —
  `role = 'TNP_PIC' OR (all five IS NULL)`. A coordinator or a student cannot hold these
  even if a future bug tries to write them.
- `applications.drive_id` cascades on delete, because an application with no drive behind
  it is clutter rather than history. `student_id` does not cascade: there is no way to
  delete an account in this application yet, and refusing the delete is the safer default
  to revisit deliberately if that ever changes.

## Prerequisites

| Tool | Version |
| --- | --- |
| JDK | 21 |
| Node.js | 24 or newer |
| Docker Desktop | running |

## Quick start

Start the database first - both the backend and its tests need PostgreSQL.

```powershell
docker compose up -d
```

Backend (http://localhost:8081):

```powershell
cd backend
.\mvnw.cmd spring-boot:run
```

Frontend (http://localhost:5173):

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173, register with a college address, fill in your profile, and the
drives board will show what you qualify for.

### Ports

PostgreSQL is published on host port **5433**, not the usual 5432, and the backend listens
on **8081**, not 8080. Both defaults avoid clashing with software that commonly holds those
ports. Override with the `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `SERVER_PORT`
environment variables; the container itself still uses 5432 internally.

### Configuration

Nothing secret is committed. Every value below has a local default that matches
`docker-compose.yml`, and is overridden by an environment variable in a real deployment.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/placementprep` | database connection |
| `DB_USERNAME` / `DB_PASSWORD` | `placementprep` | database credentials |
| `SERVER_PORT` | `8081` | backend port |
| `JWT_SECRET` | a local development placeholder | token signing key |
| `JWT_EXPIRATION` | `86400000` (24 hours) | token lifetime in milliseconds |

`JWT_SECRET` **must** be set to a random private value anywhere real. It is validated at
startup: shorter than 32 characters and the application refuses to boot, naming the
property, rather than failing later inside the signing library.

## Commands

| What | Where | Command |
| --- | --- | --- |
| Start database | root | `docker compose up -d` |
| Stop database | root | `docker compose down` |
| Reset database (deletes data) | root | `docker compose down -v` |
| Run backend tests | `backend/` | `.\mvnw.cmd test` |
| Full backend build + tests | `backend/` | `.\mvnw.cmd verify` |
| Run backend | `backend/` | `.\mvnw.cmd spring-boot:run` |
| Run frontend | `frontend/` | `npm run dev` |
| Build frontend | `frontend/` | `npm run build` |

On macOS or Linux use `./mvnw` instead of `.\mvnw.cmd`.

## Authentication and roles

Login exchanges an email and password for a **JSON Web Token (JWT)**, which the client
then sends on every request as `Authorization: Bearer <token>`.

The token's payload is signed, not encrypted - anyone holding one can read what is inside
it, so nothing confidential goes in a claim. The signature makes it tamper-evident: a
student can read their own role but cannot change it without invalidating the token.

The role travels in the token purely as a convenience for the client. **The server never
trusts it** and re-reads the real role from the database on every request, so a revoked
privilege takes effect on the next call rather than whenever the token happens to expire.

### The three roles

| Role | May do |
| --- | --- |
| `STUDENT` | read the board, see their own eligible drives, edit their own academic profile, apply to and withdraw from drives |
| `TNP_COORDINATOR` | everything a student may, plus create, edit and delete drives, and review/decide applications |
| `TNP_PIC` | manages the board and applications like a coordinator, plus promotes students to coordinator, edits their own staff details, and uses the Administration sign-in. **Not a student** - holds no CGPA, cannot apply to drives |

**A coordinator is a student.** They are a final-year student who also helps run the cell,
so they keep everything a student has - an academic profile, an eligibility list, the
ability to apply to and withdraw from drives - and gain the board and applicant tools on
top. The interface reflects that: a coordinator sees the "Eligible for me" tab and an
**Apply** button *and* a **Delete** button on the same drive card, and gets both a "My
applications" and an "Applicants" tab.

The person in charge is the exception. They are faculty, not a student, which is why the
`CHECK` constraint gives them the staff columns and why the UI gives them no eligibility
tab and no Apply button - `/api/drives/eligible` would answer `409` for an account with no
CGPA anyway.

So "is this account management?" and "is this account a student?" are two independent
questions, and the frontend asks them separately rather than collapsing both into one
`isStaff` flag - doing that is precisely what once left coordinators unable to apply to
anything.

Registration always creates a `STUDENT`. There is deliberately no role field on the
registration request - if clients could choose, anyone could sign up as staff and publish
fake drives. Coordinators are promoted from existing students by the person in charge
(PIC), and a PIC is seeded directly into the database, because anyone who could grant that
role through the API would already need to hold it.

### College addresses only

Registration is restricted to college email addresses of the form `*@*.nits.ac.in`. A
department subdomain is required, so `name@cse.nits.ac.in` is accepted and the bare
`name@nits.ac.in` is not. The check requires a dot immediately before `nits.ac.in`, which
is what stops a lookalike domain such as `notnits.ac.in` from passing a naive suffix test.

### Signing in as the person in charge

`V8__seed_person_in_charge.sql` inserts the first `TNP_PIC` account directly, so a fresh
database already has someone who can post a drive and promote others - no manual SQL
needed:

| Email | Password |
| --- | --- |
| `tnp@pic.nits.ac.in` | `9676596160` |

The sign-in page has two tabs, **Student** and **Administration**. Either one accepts this
account, but only the Administration tab is *enforced*, and only against `TNP_PIC`:

- **Administration tab** — if the credentials belong to a student or a coordinator, the
  token that was just issued is discarded and the form says so. The password was correct;
  the wrong door was used.
- **Student tab** — the original, unrestricted form. It accepts any account, including a
  coordinator or the PIC. A coordinator was never given a door of their own, so locking
  them out of the one they already used would be a regression rather than a feature.

The backend has no separate administration endpoint and cannot have one: which tab was
clicked is not something the server has any reason to know before the password has even
been checked. Both tabs `POST /api/auth/login`; the enforcement lives in `Login.tsx`, after
a valid token comes back, by reading the role it carries.

The stored hash
was generated with this project's own `BCryptPasswordEncoder` and round-trip verified
with `encoder.matches(...)` before being written into the migration, not typed by hand -
a hash that does not actually match its password is a seed account that quietly cannot
log in. That password is a local-development placeholder committed on purpose for this
seed row; it is not meant to guard anything real, which is also why registration itself
stays gated to college addresses rather than accepting arbitrary emails.

A signed-in `TNP_PIC` promotes further students to `TNP_COORDINATOR` through the API:

```sql
-- only needed if you want a second PIC account; coordinators are promoted through
-- POST /api/users/{id}/promote instead, once signed in as the seeded PIC above
UPDATE users SET role = 'TNP_PIC' WHERE email = 'you@cse.nits.ac.in';
```

## API

All paths require `Authorization: Bearer <token>` unless marked public.

### Accounts

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| `POST` | `/api/auth/register` | create a student account, returns a token | `201` / `409` |
| `POST` | `/api/auth/login` | exchange credentials for a token | `200` / `401` |
| `GET` | `/api/users/me` | the signed-in user and their profile | `200` |
| `PUT` | `/api/users/me` | replace my academic details | `200` |
| `PUT` | `/api/users/me/staff-profile` | replace my staff details - **PIC only** | `200` / `403` |
| `POST` | `/api/users/{id}/promote` | make a student a coordinator - **PIC only** | `200` / `404` / `409` |

`/api/auth/register` and `/api/auth/login` are public, as is `/actuator/health`.

A failed login is a flat `401` with no detail. Distinguishing "no such account" from
"wrong password" would turn the endpoint into a way to discover which addresses are
registered.

### Profile fields

Sent to `PUT /api/users/me`. All are required, and the eligibility filter needs them
before it can return anything.

| Field | Notes |
| --- | --- |
| `cgpa` | 0-10 |
| `branch` | e.g. `CSE`; stored upper-cased |
| `tenthPercentage`, `twelfthPercentage` | 0-100 |
| `backlogs` | active backlogs; 0 or more |

The response also carries a computed `complete` boolean, so the UI can prompt a student to
finish their profile without re-implementing the same checks. It reflects the *academic*
fields only - a PIC has no CGPA to be missing, and nothing gates a feature behind their
staff details being filled in.

### Staff fields

Sent to `PUT /api/users/me/staff-profile`, and **restricted to `TNP_PIC`** - a coordinator
gets a `403` here exactly like a student does.

| Field | Notes |
| --- | --- |
| `designation` | e.g. `Training & Placement Officer`; max 100 chars |
| `department` | the department or cell they belong to; max 100 chars |
| `staffId` | institutional staff id; max 50 chars, unique across accounts |
| `officeLocation` | cabin or room; max 100 chars |
| `phoneNumber` | contact number; max 20 chars |

Every field is optional, unlike the academic profile: nothing in the system depends on a
PIC's details being complete, so there is no all-or-nothing rule to enforce. `PUT` still
replaces the whole sub-resource, so a field left out or sent blank becomes `null`.

These five columns live on the same `users` table as everything else, guarded by a `CHECK`
constraint - `role = 'TNP_PIC' OR (all five IS NULL)`. A student or a coordinator cannot
hold them even if a future bug tries to write them, and `GET /api/users/me` returns them as
`null` for those accounts.

### Drives

Base path `/api/drives`.

| Method | Path | Purpose | Who | Success |
| --- | --- | --- | --- | --- |
| `GET` | `/api/drives` | all drives, newest first | any signed-in user | `200` |
| `GET` | `/api/drives/eligible` | drives **I** qualify for | any signed-in user | `200` / `409` |
| `GET` | `/api/drives/{id}` | one drive | any signed-in user | `200` / `404` |
| `POST` | `/api/drives` | post a drive | PIC, coordinator | `201` |
| `PUT` | `/api/drives/{id}` | replace a drive | PIC, coordinator | `200` / `404` |
| `DELETE` | `/api/drives/{id}` | remove a drive | PIC, coordinator | `204` / `404` |

`/api/drives/eligible` takes no parameters. It reads the marks from the signed-in user's
own profile, so one student can never enumerate what another would qualify for. It answers
`409` when the caller's profile is not filled in yet.

A drive is returned when **every** condition holds:

- the student meets `cgpaCutoff`, `tenthCutoff` and `twelfthCutoff` - a cutoff left unset
  on the drive imposes no requirement
- the student's branch appears in `eligibleBranches`, matched case-insensitively
- the student has no backlogs, or the drive permits backlogs and the student is within
  `maxBacklogs`
- `applicationDeadline` has not passed

Results are ordered by deadline, soonest first.

### Drive fields

| Field | Required | Notes |
| --- | --- | --- |
| `companyName`, `role` | yes | non-blank |
| `ctc` | yes | positive, in rupees per annum |
| `tier` | yes | |
| `cgpaCutoff` | yes | minimum CGPA |
| `tenthCutoff`, `twelfthCutoff` | no | minimum percentage, 0-100. Omit to set no requirement |
| `backlogsAllowed` | yes | whether students with active backlogs may apply |
| `maxBacklogs` | only when `backlogsAllowed` is true | how many are tolerated; omit for no ceiling |
| `eligibleBranches` | yes | non-empty list, e.g. `["CSE","ECE"]` |
| `applicationDeadline` | yes | ISO-8601 instant |
| `description` | no | free text |

Example, as a coordinator:

```powershell
curl.exe -X POST http://localhost:8081/api/drives `
  -H "Authorization: Bearer $token" -H "Content-Type: application/json" -d '{
  \"companyName\": \"Zoho Corporation\", \"role\": \"Member Technical Staff\",
  \"ctc\": 900000, \"tier\": 1, \"cgpaCutoff\": 7.5,
  \"tenthCutoff\": 75.0, \"twelfthCutoff\": 70.0,
  \"backlogsAllowed\": false,
  \"eligibleBranches\": [\"CSE\", \"ECE\", \"IT\"],
  \"applicationDeadline\": \"2026-12-20T18:00:00Z\"}'
```

### Applications

Base path `/api/applications`.

| Method | Path | Purpose | Who | Success |
| --- | --- | --- | --- | --- |
| `POST` | `/api/applications` | apply to a drive | any signed-in user | `201` / `409` |
| `GET` | `/api/applications/me` | my own applications | any signed-in user | `200` |
| `GET` | `/api/applications/drive/{driveId}` | everyone who applied to a drive | PIC, coordinator | `200` |
| `PATCH` | `/api/applications/{id}/status` | move an application along | PIC, coordinator | `200` / `404` |
| `POST` | `/api/applications/{id}/withdraw` | pull out of a drive | any signed-in user | `200` / `404` |

`POST /api/applications` takes only `{"driveId": ...}` - the student comes from the
signed-in account, never from the body, so nobody can apply on someone else's behalf.
There is a real `UNIQUE (student_id, drive_id)` constraint in the database, so there is
never more than one application row per student per drive - a second `POST` either
reopens that row or is refused, depending on its current status:

- if it is `WITHDRAWN`, applying **reopens the same row**: status goes back to `APPLIED`,
  the note from the withdrawn cycle is cleared, and `appliedAt` is reset to now, so the
  application does not misleadingly look months old the moment it becomes active again
- for any other status - `APPLIED`, `SHORTLISTED`, `REJECTED`, `SELECTED` - a second
  `POST` is a `409`. Only a withdrawal is undoable this way; a rejection is a decision
  the cell already made, not a state a second `POST` should quietly erase

Status is one of `APPLIED`, `SHORTLISTED`, `REJECTED`, `SELECTED`, `WITHDRAWN`. A student
can move it to `APPLIED` (by applying, including re-applying after a withdrawal) and to
`WITHDRAWN` (by withdrawing); every other transition is the placement cell's call through
`PATCH .../status`.

Withdrawing someone else's application answers `404`, not `403` - `ApplicationService`
looks the row up by `(id, studentId)` together, so a guessed id that belongs to another
student simply does not match, and the response never confirms that an application with
that id exists at all.

### Errors

Every error is [RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) JSON, so
one client-side handler covers the whole API. A body-validation failure also names the
offending fields:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "Invalid request content.",
  "instance": "/api/auth/register",
  "errors": { "email": "Registration is open only to college email addresses ending in .nits.ac.in" }
}
```

The rejected value is deliberately never echoed back, since on a registration form the
submitted body contains a password.

## Layout

```
placement-prep/
├── backend/                Spring Boot API (Java 21, Maven)
│   └── src/main/java/com/rishikesh/placementprep/
│       ├── common/             Shared building blocks and validation error handling
│       ├── infrastructure/
│       │   └── security/       Filter chain, JWT filter, rule config, error handlers
│       └── modules/
│           ├── auth/           Accounts, login, roles, JWT            (built)
│           ├── drive/          Placement drives and eligibility       (built)
│           │   ├── controller/ HTTP endpoints
│           │   ├── service/    Business logic
│           │   ├── repository/ Spring Data JPA interfaces
│           │   ├── model/      JPA entities
│           │   └── dto/        Request and response records
│           ├── application/    A student applying, and its status     (built)
│           ├── resume/         Resume uploads and AI feedback         (planned)
│           ├── knowledgebase/  RAG over placement notices             (planned)
│           ├── interview/      AI mock interviews                     (planned)
│           └── notification/   Deadline reminders                     (planned)
├── frontend/               React + TypeScript single-page app (Vite)
│   └── src/
│       ├── api/                Functions that call the backend
│       ├── components/         Reusable presentational pieces
│       └── pages/              Login, Register, Profile, Drives,
│                                Applications, PostDrive
└── docker-compose.yml      PostgreSQL with the pgvector extension
```

Every module gets the same five sub-packages as it grows. Controllers accept and return
DTOs, never entities, so the database schema and the public API stay free to change
independently. Services return `Optional` or `boolean` to mean "not found" and never
import a web type, which keeps them callable from a scheduled job or a queue consumer.

## Database

The schema is owned by Flyway. Hibernate runs with `ddl-auto: validate` and will never
create or alter a table. Every schema change is a new migration in
`backend/src/main/resources/db/migration/`; an applied migration is never edited, because
Flyway stores a checksum of each file and refuses to start if one changes.

| Migration | Adds |
| --- | --- |
| `V1__enable_pgvector.sql` | the `vector` extension, for the later RAG feature |
| `V2__create_drives_table.sql` | the `drives` table |
| `V3__add_eligibility_criteria_to_drives.sql` | 10th and 12th cutoffs, backlogs allowed flag |
| `V4__add_max_backlogs_to_drives.sql` | backlog ceiling |
| `V5__create_users_table.sql` | the `users` table |
| `V6__add_student_profile_to_users.sql` | CGPA, branch, school percentages, backlogs |
| `V7__update_user_roles.sql` | splits the single TNP role into coordinator and PIC |
| `V8__seed_person_in_charge.sql` | inserts the first `TNP_PIC` account, hash pre-verified |
| `V9__create_applications_table.sql` | the `applications` table, with real foreign keys into `users` and `drives` |
| `V10__add_pic_staff_details.sql` | PIC-only designation, department, staff id, office, phone - plus the `CHECK` that keeps them off every other role |

## Testing

```powershell
cd backend
.\mvnw.cmd test
```

88 integration tests. They run against a real PostgreSQL container started by
Testcontainers using the `pgvector/pgvector:pg16` image, so **Docker must be running**.
Nothing is mocked and no test touches the development database. New integration tests
should import `TestcontainersConfiguration` rather than pointing at a hand-managed
database.

The suite generates real signed tokens rather than stubbing the security context, so the
JWT filter and the authorisation rules are genuinely exercised - including the cases that
matter most, such as a student being refused a write and a coordinator being refused a
promotion.

## Notes

- The frontend dev server proxies `/api` and `/actuator` to the backend, so there is no
  CORS configuration anywhere.
- The token is held in `localStorage`. That is readable by any script on the page, so it
  trades some XSS exposure for not needing CSRF protection on a cookie; a production
  deployment would likely move to an httpOnly cookie and add CSRF tokens.
- Sessions are stateless - no `HttpSession` is ever created, so any instance can serve any
  request without shared session storage.
