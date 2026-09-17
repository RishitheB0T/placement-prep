# placement-prep

A campus placement preparation platform: the training-and-placement (TNP) cell posts
placement drives, students see only the ones they actually qualify for, and AI features
help them prepare.

## Status

| Feature | State |
| --- | --- |
| Drives board - TNP CRUD and the student eligibility filter | **Built** |
| Accounts, login and three-tier roles | **Built** - Spring Security + JWT |
| Web UI - register, sign in, profile, drives board | **Built** |
| Resume upload with AI feedback | Planned - background job queue |
| Q&A over placement notices | Planned - RAG with Spring AI + pgvector |
| Company-wise AI mock interviews | Planned |
| Deadline reminders | Planned - scheduled jobs |

Not built yet: there is no screen for posting a drive, and no seeded TNP account. See
[Creating the first TNP account](#creating-the-first-tnp-account).

## Stack

**Backend** - Java 21, Maven, Spring Boot 4.1, Spring Web MVC, Spring Security 7, Bean
Validation, Spring Data JPA, Flyway, Actuator, Testcontainers.
**Frontend** - React 19, TypeScript, Vite, Tailwind CSS v4, React Router v7.
**Infrastructure** - PostgreSQL 16 with the pgvector extension, via Docker Compose.

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
| `STUDENT` | read the board, see their own eligible drives, edit their own profile |
| `TNP_COORDINATOR` | everything a student may, plus create, edit and delete drives |
| `TNP_PIC` | everything a coordinator may, plus promote a student to coordinator |

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

### Creating the first TNP account

No TNP account is seeded, so a fresh database has no one who can post a drive. Register
normally, then promote yourself directly:

```sql
UPDATE users SET role = 'TNP_PIC' WHERE email = 'you@cse.nits.ac.in';
```

From then on that account can promote others through the API.

## API

All paths require `Authorization: Bearer <token>` unless marked public.

### Accounts

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| `POST` | `/api/auth/register` | create a student account, returns a token | `201` / `409` |
| `POST` | `/api/auth/login` | exchange credentials for a token | `200` / `401` |
| `GET` | `/api/users/me` | the signed-in user and their profile | `200` |
| `PUT` | `/api/users/me` | replace my academic details | `200` |
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
finish their profile without re-implementing the same checks.

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
│           ├── resume/         Resume uploads and AI feedback         (planned)
│           ├── knowledgebase/  RAG over placement notices             (planned)
│           ├── interview/      AI mock interviews                     (planned)
│           └── notification/   Deadline reminders                     (planned)
├── frontend/               React + TypeScript single-page app (Vite)
│   └── src/
│       ├── api/                Functions that call the backend
│       ├── components/         Reusable presentational pieces
│       └── pages/              Login, Register, Profile, Drives
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

## Testing

```powershell
cd backend
.\mvnw.cmd test
```

71 integration tests. They run against a real PostgreSQL container started by
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
