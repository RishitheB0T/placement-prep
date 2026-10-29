# Placement Prep - frontend

The React single-page app for [Placement Prep](../README.md): React 19, TypeScript, Vite,
React Router 7, Axios and Tailwind CSS 4.

```bash
npm install
npm run dev        # http://localhost:5173 - proxies /api to the backend on :8081
npm test           # Vitest + React Testing Library
npm run lint       # oxlint
npm run build      # type-check, then a production build in dist/
```

## Layout

| Folder | Holds |
| --- | --- |
| `src/pages/` | one component per route - Dashboard, Drives, Applications, Resumes, Assistant, Profile, PostDrive, Login, Register |
| `src/components/` | shared pieces - the header and notification bell, the route guard, the UI kit in `ui.tsx` |
| `src/api/` | every call to the backend, one file per area; `client.ts` is the single Axios instance that attaches the token and turns errors into `ApiError`; `roles.ts` says what each role may do |
| `src/test/` | test setup, render helpers and fixtures |

Pages never call HTTP directly - they call functions in `src/api/`. The design system
(colours, `.card`, `.btn`, `.input`, `.chip`, animations) lives in `src/index.css`.

See the [development guide](../docs/DEVELOPMENT.md) for the full setup.
