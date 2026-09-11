# placement-prep

A campus placement preparation platform: the training-and-placement (TNP) cell posts
placement drives, students see the ones they are eligible for, and AI features help them
prepare.

## Status

| Feature | State |
| --- | --- |
| Drives board - admin CRUD and student eligibility filter | **Built** (backend only) |
| Student and TNP-admin login with roles | Planned - Spring Security + JWT |
| Resume upload with AI feedback | Planned - background job queue |
| Q&A over placement notices | Planned - RAG with Spring AI + pgvector |
| Company-wise AI mock interviews | Planned |
| Deadline reminders | Planned - scheduled jobs |

The frontend currently contains only a home page that reports backend health. The drives
board is not yet wired into the UI.

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

Open http://localhost:5173 and the home page should read **Backend: UP**.

### Ports

PostgreSQL is published on host port **5433**, not the usual 5432, and the backend listens
on **8081**, not 8080. Both defaults avoid clashing with software that commonly holds those
ports. Override with the `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `SERVER_PORT`
environment variables; the container itself still uses 5432 internally.

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

## API - drives

Base path `/api/drives`.

| Method | Path | Purpose | Success |
| --- | --- | --- | --- |
| `POST` | `/api/drives` | Admin posts a drive | `201` |
| `GET` | `/api/drives` | All drives, newest first | `200` |
| `GET` | `/api/drives/eligible` | Drives a given student may apply for | `200` |
| `GET` | `/api/drives/{id}` | One drive | `200` / `404` |
| `PUT` | `/api/drives/{id}` | Replace a drive | `200` / `404` |
| `DELETE` | `/api/drives/{id}` | Remove a drive | `204` / `404` |

A request that fails validation returns `400` and changes nothing.

### Drive fields

| Field | Required | Notes |
| --- | --- | --- |
| `companyName`, `role` | yes | non-blank |
| `ctc` | yes | positive |
| `tier` | yes | |
| `cgpaCutoff` | yes | minimum CGPA |
| `tenthCutoff`, `twelfthCutoff` | no | minimum percentage, 0-100. Omit to set no requirement |
| `backlogsAllowed` | yes | whether students with active backlogs may apply |
| `maxBacklogs` | only when `backlogsAllowed` is true | how many are tolerated; omit for no ceiling |
| `eligibleBranches` | yes | non-empty list, e.g. `["CSE","ECE"]` |
| `applicationDeadline` | yes | ISO-8601 instant |
| `description` | no | free text |

### Eligibility filter

```
GET /api/drives/eligible?cgpa=8.5&tenth=80&twelfth=80&backlogs=0&branch=CSE
```

All five parameters are required. A drive is returned when **every** condition holds:

- the student meets `cgpaCutoff`, `tenthCutoff` and `twelfthCutoff` - a cutoff left unset
  on the drive imposes no requirement
- the student's branch appears in `eligibleBranches`, matched case-insensitively
- the student has no backlogs, or the drive permits backlogs and the student is within
  `maxBacklogs`
- `applicationDeadline` has not passed

Results are ordered by deadline, soonest first.

Example:

```powershell
curl.exe -X POST http://localhost:8081/api/drives -H "Content-Type: application/json" -d '{
  \"companyName\": \"Zoho Corporation\", \"role\": \"Member Technical Staff\",
  \"ctc\": 900000, \"tier\": 1, \"cgpaCutoff\": 7.5,
  \"tenthCutoff\": 75.0, \"twelfthCutoff\": 70.0,
  \"backlogsAllowed\": false,
  \"eligibleBranches\": [\"CSE\", \"ECE\", \"IT\"],
  \"applicationDeadline\": \"2026-12-20T18:00:00Z\"}'
```

## Layout

```
placement-prep/
├── backend/                Spring Boot API (Java 21, Maven)
│   └── src/main/java/com/rishikesh/placementprep/
│       ├── common/             Shared building blocks
│       ├── infrastructure/     Database config, storage, schedulers, external clients
│       └── modules/
│           ├── auth/           Accounts, login, roles, JWT            (planned)
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
│       └── pages/              One file per route
└── docker-compose.yml      PostgreSQL with the pgvector extension
```

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

## Testing

```powershell
cd backend
.\mvnw.cmd test
```

Tests run against a real PostgreSQL container started by Testcontainers using the
`pgvector/pgvector:pg16` image, so **Docker must be running**. Nothing is mocked and no
test touches the development database. New integration tests should import
`TestcontainersConfiguration` rather than pointing at a hand-managed database.

## Notes

- The frontend dev server proxies `/api` and `/actuator` to the backend, so there is no
  CORS configuration anywhere.
- No credentials are committed. The database connection is read from environment
  variables with local defaults that match `docker-compose.yml`.
