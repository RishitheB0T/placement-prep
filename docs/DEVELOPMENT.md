# Development guide

Everything needed to run, configure and test Placement Prep locally. For how the system is
built see [ARCHITECTURE.md](ARCHITECTURE.md); for endpoints see [API.md](API.md).

## Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| Docker Desktop | running | the database, the backend tests, or the whole app in containers |
| JDK | 21 | running the backend outside Docker |
| Node.js | 24 or newer | running the frontend outside Docker |

## Run it

### Option A - everything in Docker

No JDK or Node needed:

```bash
docker compose --profile app up -d --build
```

Open **http://localhost:3000**. The frontend container is nginx serving the built React app
and forwarding `/api` to the backend container, so the browser sees one address. Set
`ANTHROPIC_API_KEY` in your shell first to switch on the AI features. The first start
downloads the embedding model (~350 MB with its runtime) into the
`placementprep-backend-data` volume; later starts are quick.

### Option B - development mode, with hot reload

```bash
docker compose up -d                 # PostgreSQL + pgvector on port 5433

cd backend
./mvnw spring-boot:run               # http://localhost:8081  (Windows: .\mvnw.cmd)

cd frontend
npm install
npm run dev                          # http://localhost:5173
```

The Vite dev server proxies `/api` to the backend, so there is no CORS setup.

Run one backend at a time against the database - the Docker one or the mvnw one. Each runs
the background jobs, and the resume queue assumes a single backend.

### First sign-in

A fresh database already holds the person in charge, seeded by migration V8:

| Role | Email | Password | Tab |
| --- | --- | --- | --- |
| Admin (person in charge) | `tnp@pic.nits.ac.in` | `9676596160` | Administration |

From there: post drives, add a recruiter (People → Recruiters), and register students
with any `name@<dept>.nits.ac.in` address. That password is a local-development
placeholder - change it on the Profile page before anything real.

## Configuration

Nothing secret is committed. Every value has a local default matching
`docker-compose.yml` and can be overridden with an environment variable.

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5433/placementprep` | database connection |
| `DB_USERNAME` / `DB_PASSWORD` | `placementprep` | database credentials |
| `SERVER_PORT` | `8081` | backend port |
| `JWT_SECRET` | a development placeholder | token signing key - **set a random value of 32+ characters anywhere real**; a shorter one stops startup |
| `JWT_EXPIRATION` | `86400000` (24 h) | token lifetime in milliseconds |
| `ANTHROPIC_API_KEY` | empty | turns on resume feedback and the assistant |
| `ANTHROPIC_MODEL` | `claude-opus-5-5` | the model both use |
| `RESUME_STORAGE_DIR` | `uploads/resumes` | where uploaded PDFs are kept (git-ignored) |
| `RESUME_REVIEW_POLL_DELAY` | `PT5S` | how often the review job checks for new resumes |
| `NOTIFICATION_INITIAL_DELAY` | `PT1M` | first deadline-reminder run after startup |
| `NOTIFICATION_POLL_DELAY` | `PT15M` | wait between reminder runs |
| `EMBEDDING_CACHE_DIR` / `DJL_CACHE_DIR` | under your home folder | where the embedding model is cached |

**Ports.** PostgreSQL is published on **5433** and the backend listens on **8081**, rather
than 5432 and 8080, to avoid clashing with software that commonly holds those ports.

## Commands

| What | Where | Command |
| --- | --- | --- |
| Start the database | root | `docker compose up -d` |
| Stop the database | root | `docker compose down` |
| Reset the database (deletes data) | root | `docker compose down -v` |
| Run the whole app in Docker | root | `docker compose --profile app up -d --build` |
| Stop the whole app | root | `docker compose --profile app down` |
| Backend tests | `backend/` | `./mvnw test` |
| Backend build + tests | `backend/` | `./mvnw verify` |
| Run the backend | `backend/` | `./mvnw spring-boot:run` |
| Frontend dev server | `frontend/` | `npm run dev` |
| Frontend tests | `frontend/` | `npm test` (`npm run test:watch` to watch) |
| Frontend lint | `frontend/` | `npm run lint` |
| Frontend production build | `frontend/` | `npm run build` |

On Windows use `.\mvnw.cmd` instead of `./mvnw`.

## Testing

| Suite | Count | Runs on | Needs |
| --- | --- | --- | --- |
| Backend integration (`*ApiTest` + context test) | 8 classes | real PostgreSQL + pgvector in Docker (Testcontainers), real signed JWTs | Docker |
| Backend unit (`*ServiceTest`, `*JobTest`, `AssistantToolsTest`, `ToolChoiceEvalTest`) | 9 classes | Mockito or plain Java, no Spring, no database | - |
| **Backend total** | **243 tests** | JUnit Jupiter | Docker |
| AI evaluations (`AssistantEvalTest`, `ResumeReviewEvalTest`) | 2 classes | the real model - run on purpose with `-Peval` | Docker, `ANTHROPIC_API_KEY` |
| Frontend (Vitest + Testing Library) | **99 tests** | jsdom, every API call stubbed | - |
| Postman collection | **87 requests, 117 assertions** | a running backend | the app |

Highlights:

- **Real infrastructure.** Integration tests start a PostgreSQL container with pgvector, so
  the eligibility query, the vector search and the migrations are all exercised for real.
  `JobDescriptionApiTest` proves search by meaning with the real embedding model.
- **The AI loop without a paid key.** `AgentApiTest` points Spring AI at a small fake
  Anthropic server that asks for a tool, so the whole tool-calling loop - model asks, Java
  method runs, result goes back - is tested end to end.
- **Access rules everywhere.** Every restricted endpoint is called by a role that may and
  a role that may not, in both the backend tests and the Postman collection.
- **Jobs on demand.** In tests the scheduled jobs' automatic runs are pushed a day out
  (`src/test/resources/application.properties`) and tests run them directly with a fixed
  clock.
- **Frontend by role.** The page tests check that each role gets exactly the controls it
  should - Apply where eligible, Delete for the hiring side, the bell for students.

The first backend test run downloads the embedding model (~350 MB, cached afterwards).

### AI evaluations

Unit tests check code that always behaves the same; a model does not. The evaluations run
fixed sets of cases against the real model and score the batch against a threshold:

| Evaluation | Checks | Threshold |
| --- | --- | --- |
| Tool choice (`AssistantEvalTest`) | 11 messages in `src/test/resources/eval/assistant-tool-choice.json`, each naming the tools that must be called and those that must not | 80% of cases |
| Resume review (`ResumeReviewEvalTest`) | the same resume reviewed 3 times, and a fitting resume against an unrelated one | spread ≤ 15 points, gap ≥ 20 |

```bash
cd backend
ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Peval
```

On Windows PowerShell: `$env:ANTHROPIC_API_KEY = "sk-ant-..."; .\mvnw.cmd test -Peval`.

They are tagged `eval`: the normal build and CI leave them out, `-Peval` runs only them,
and without a key they are skipped. A run makes roughly 30 model calls - every case is one
or more, since a tool call is a round trip - and writes `target/eval/assistant-tool-choice.md`
and `target/eval/resume-review-consistency.md`. Run them after changing the system prompt,
a tool description or `ANTHROPIC_MODEL`, and compare the reports.

### Postman

Import [`postman/placement-prep.postman_collection.json`](../postman/placement-prep.postman_collection.json),
set `baseUrl` if the backend is not on `http://localhost:8081`, and run the folders in
order. File uploads use the two sample PDFs next to it. A run leaves behind one
`postman.<timestamp>@cse.nits.ac.in` student and one recruiter account.

## Free showcase deployment

[`render.yaml`](../render.yaml) describes a free [Render](https://render.com) showcase: the API,
the nginx-served frontend (which proxies `/api` to the API over HTTPS) and a PostgreSQL
database. Free services sleep when idle, uploads are temporary and the database expires
after 30 days, so it is for demos only.

To fit the free 512 MB instance, the showcase leaves out the local embedding model and the
vector store (`SPRING_AUTOCONFIGURE_EXCLUDE`). `JobDescriptionService` takes the vector store
as optional, so everything else runs normally: job-description search and upload answer
`503`, and resume reviews run without job-description excerpts.

## Continuous integration

GitHub Actions ([`.github/workflows/ci.yml`](../.github/workflows/ci.yml)) runs on every
push: the backend `verify` (all tests, against Docker on the runner) and the frontend
tests and production build. The embedding model is cached between runs.

## Conventions

- Every backend module has the same five packages: `controller`, `service`, `repository`,
  `model`, `dto`. DTOs are Java records; entities are classes.
- Every schema change is a new Flyway migration in `backend/src/main/resources/db/migration/`.
  Never edit one that has been applied.
- Frontend pages never call HTTP directly - they call functions in `src/api/`.
