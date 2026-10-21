import { Navigate, Route, Routes } from "react-router-dom";

import type { Role } from "./api/types";
import ProtectedRoute from "./components/ProtectedRoute";
import Applications from "./pages/Applications";
import Assistant from "./pages/Assistant";
import Dashboard from "./pages/Dashboard";
import Drives from "./pages/Drives";
import Login from "./pages/Login";
import PostDrive from "./pages/PostDrive";
import Profile from "./pages/Profile";
import Register from "./pages/Register";
import Resumes from "./pages/Resumes";

/** Roles that manage the board: the recruiter and the admin. */
const MANAGERS: Role[] = ["TNP_COORDINATOR", "TNP_PIC"];

/** Roles that are students: the student, and the recruiter who is also one. */
const STUDENTS: Role[] = ["STUDENT", "TNP_COORDINATOR"];

export default function App() {
  return (
    <Routes>
      {/* Reachable signed out, because you cannot get a token any other way. */}
      <Route path="/login" element={<Login />} />
      <Route path="/register" element={<Register />} />

      <Route
        path="/drives"
        element={
          <ProtectedRoute>
            <Drives />
          </ProtectedRoute>
        }
      />
      <Route
        path="/profile"
        element={
          <ProtectedRoute>
            <Profile />
          </ProtectedRoute>
        }
      />
      {/*
        Part of the recruiter's and admin's dashboard only. ProtectedRoute sends a student
        back to the board before the page even loads; SecurityConfig's 403 on the backend
        is the part of this that a browser cannot be talked around.
      */}
      <Route
        path="/post-drive"
        element={
          <ProtectedRoute roles={MANAGERS}>
            <PostDrive />
          </ProtectedRoute>
        }
      />
      {/* The same form, loaded with an existing drive. PostDrive branches on the param. */}
      <Route
        path="/drives/:id/edit"
        element={
          <ProtectedRoute roles={MANAGERS}>
            <PostDrive />
          </ProtectedRoute>
        }
      />
      {/*
        One page, two views: Applications.tsx itself checks the signed-in role and shows
        "my applications" or "applicants for a drive" accordingly - there is no separate
        staff route to define here.
      */}
      <Route
        path="/applications"
        element={
          <ProtectedRoute>
            <Applications />
          </ProtectedRoute>
        }
      />

      {/*
        Part of the student's dashboard - and the recruiter's, since a recruiter is a
        student too. The admin has no resume and no applications of their own, so both are
        left out of theirs. Accepts ?driveId=12 to arrive with that drive already chosen.
      */}
      <Route
        path="/resumes"
        element={
          <ProtectedRoute roles={STUDENTS}>
            <Resumes />
          </ProtectedRoute>
        }
      />
      <Route
        path="/assistant"
        element={
          <ProtectedRoute roles={STUDENTS}>
            <Assistant />
          </ProtectedRoute>
        }
      />

      {/*
        Every role's home after signing in. One route, three dashboards: the page shows
        the student, recruiter or admin view according to the signed-in role.
      */}
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <Dashboard />
          </ProtectedRoute>
        }
      />

      {/* The landing page. ProtectedRoute sends a signed-out visitor on to /login. */}
      <Route path="/" element={<Navigate to="/dashboard" replace />} />

      {/* Anything unrecognised, rather than a blank screen. */}
      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
