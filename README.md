# placement-prep

A campus placement preparation platform: the training-and-placement cell posts drives,
students see the ones they are eligible for, and AI helps them prepare.

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

## Layout

```
placement-prep/
├── backend/     Spring Boot API (Java 21, Maven)
├── frontend/    React + TypeScript single-page app (Vite)
└── docker-compose.yml   PostgreSQL with the pgvector extension
```

## Notes

- The database schema is owned by Flyway. Hibernate is set to `validate` and will
  never create or alter a table.
- The frontend dev server proxies `/api` and `/actuator` to the backend, so there is
  no CORS configuration anywhere.
