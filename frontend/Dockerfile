# The frontend as a container image, built in two stages.
#
# Stage 1 builds the React app into plain static files (dist/). Stage 2 is nginx serving
# those files and forwarding /api to the backend container - the job the Vite dev
# server's proxy does in development.

# ---------------------------------------------------------------- 1. Build
FROM node:24-alpine AS build
WORKDIR /app

# package files first, for the same layer-caching reason as the backend's pom.xml: the
# dependencies are only reinstalled when these two files change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Type-checks with tsc, then bundles with Vite.
RUN npm run build

# ------------------------------------------------------------------ 2. Run
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
