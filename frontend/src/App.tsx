import { Navigate, Route, Routes } from "react-router-dom";

import ProtectedRoute from "./components/ProtectedRoute";
import Applications from "./pages/Applications";
import Drives from "./pages/Drives";
import Login from "./pages/Login";
import PostDrive from "./pages/PostDrive";
import Profile from "./pages/Profile";
import Register from "./pages/Register";

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
        Reachable only by TNP_COORDINATOR and TNP_PIC. ProtectedRoute only checks that
        someone is signed in at all, not which role - PostDrive itself redirects a
        STUDENT back to /drives, and SecurityConfig's 403 on the backend is the part of
        this that a browser cannot be talked around.
      */}
      <Route
        path="/post-drive"
        element={
          <ProtectedRoute>
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

      {/* The landing page. ProtectedRoute sends a signed-out visitor on to /login. */}
      <Route path="/" element={<Navigate to="/drives" replace />} />

      {/* Anything unrecognised, rather than a blank screen. */}
      <Route path="*" element={<Navigate to="/drives" replace />} />
    </Routes>
  );
}
