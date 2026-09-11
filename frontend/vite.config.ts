import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Forward backend paths to Spring Boot while developing. The browser only ever
    // talks to the Vite dev server, so as far as it is concerned everything is one
    // origin and the backend needs no CORS configuration.
    proxy: {
      "/api": "http://localhost:8081",
      "/actuator": "http://localhost:8081",
    },
  },
});
