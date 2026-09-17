import { Navigate, Route, Routes } from "react-router-dom";

import ProtectedRoute from "./components/ProtectedRoute";
import Drives from "./pages/Drives";
import Login from "./pages/Login";
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

      {/* The landing page. ProtectedRoute sends a signed-out visitor on to /login. */}
      <Route path="/" element={<Navigate to="/drives" replace />} />

      {/* Anything unrecognised, rather than a blank screen. */}
      <Route path="*" element={<Navigate to="/drives" replace />} />
    </Routes>
  );
}
