# syntax=docker/dockerfile:1

# package.json pins pnpm via "packageManager" (pnpm@11.9.0), so corepack
# resolves the exact version — no global install or duplicated version literal.

# ---- Dependencies stage: install with a cached pnpm store ----
FROM node:24-alpine AS deps
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

# ---- Build stage: produce the Next.js standalone output ----
FROM node:24-alpine AS build
WORKDIR /app
RUN corepack enable
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
# NEXT_PUBLIC_* is inlined into the bundle at build time, so the API base URL
# must be fixed here, not at runtime. Default to the local backend so a bare
# `docker build` and `docker compose up --build` keep talking cross-origin to
# :8080. The production build overrides this with an empty string
# (--build-arg NEXT_PUBLIC_API_BASE_URL=) so api.ts emits relative /api paths
# and Caddy serves frontend + backend from one origin (first-party cookies).
ARG NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL
RUN pnpm build

# ---- Runtime stage: the self-contained standalone server ----
# next.config.ts sets output: "standalone", so .next/standalone carries a
# minimal server.js plus only the traced node_modules -- no full install here.
FROM node:24-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup -S nodejs && adduser -S nextjs -G nodejs
COPY --from=build /app/public ./public
COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
USER nextjs

EXPOSE 3000
CMD ["node", "server.js"]
