# Falcon

**AI-powered contract review tool.** Paste a contract, get risk-flagged clauses in plain Polish plus a list of negotiation points.

Built as a project for the [10xDevs](https://10xdevs.pl) course. *Falcon* is the project codename; the repo/artifact is `falcon-contracts`.

> ⚠️ Falcon is an AI assistant, not a substitute for legal advice. Always have a qualified attorney review final terms.

The UI language and the model's output are **Polish**; the code, enums and API are English.

---

## What it does

1. You register / log in and paste the text of a contract.
2. Falcon splits it into clauses and classifies each one with an LLM:
    - **risk level** — `LOW` / `MEDIUM` / `HIGH`
    - **risk type** — penalty, auto-renewal, no termination, unilateral change, etc.
    - **rationale** — a short plain-Polish explanation
3. It generates a list of **negotiation points**, each linked back to the clause it came from.
4. The result is rendered as *a contract someone marked up*: every clause sits in a document column with a margin redline whose ink **and** thickness encode severity, and the level is spelled out in words — never colour alone.
5. You work the report as a checklist — mark each clause **accepted / to negotiate / rejected** — and the decisions persist.
6. Analyses are saved to your account, listed on the dashboard, reopenable, and deletable. They are strictly owner-scoped: another user's analysis returns 404, never someone else's data.

## Status

**The MVP is implemented and green in CI.** The full flow works end to end: register → paste → classify → saved report → per-clause decisions → history → delete. Every roadmap slice through `S-07` is delivered (see `context/foundation/roadmap.md`).

---

## Tech stack

**Backend** — Java 25, Spring Boot 4.0.7, Maven, Spring AI 2.0.0, Spring Data JPA + Hibernate, Spring Security (session cookies + CSRF), Liquibase, PostgreSQL 18

**Frontend** — Next.js 16 (App Router), React 19, TypeScript 6, Tailwind CSS 4 (CSS-first), shadcn/ui, pnpm

**AI** — Spring AI's OpenAI-compatible starter pointed at **OpenRouter** (`openai/gpt-4o`, `temperature=0.2`, JSON response format). OpenRouter is OpenAI-wire-compatible, so switching to Claude or Gemini is a one-property change.

**Tests** — JUnit + Testcontainers (backend), Playwright (browser E2E). The LLM is **never called in any test** — classifications are stubbed, so CI is deterministic and free.

**Infra** — Docker Compose (local), GitHub Actions (CI), AWS

---

## Getting started

```bash
git clone https://github.com/morawski-dev/falcon-contracts.git
cd falcon-contracts
cp .env.example .env    # put your OPENROUTER_API_KEY here
docker compose up --build
```

Then open **http://localhost:3000** (backend on `:8080`, Postgres on `:5432`). `docker compose down` stops the stack and keeps the database volume; `down -v` wipes it.

### Running the apps separately

There is no root-level build orchestration — the two apps have independent toolchains.

**Backend** (`cd backend`, use `mvnw.cmd` on Windows):

```bash
./mvnw spring-boot:run     # Postgres starts automatically via spring-boot-docker-compose
./mvnw test                # all tests (Testcontainers spins up its own Postgres)
./mvnw clean package       # build the jar
```

`spring-boot:run` needs a real `OPENROUTER_API_KEY` in the environment — there is no fallback, so the context won't start without one.

**Frontend** (`cd frontend`):

```bash
pnpm install
pnpm dev          # http://localhost:3000
pnpm lint
pnpm build
pnpm test:e2e     # Playwright; boots both servers itself, or reuses running ones
```

> Do **not** use `pnpm start`. `next.config.ts` sets `output: "standalone"`, which `next start` does not serve correctly. The production entry point (what CI and the Dockerfile use) is `node .next/standalone/server.js` with `public/` and `.next/static/` copied in.

### Running without an API key

The backend ships an `e2e` Spring profile that serves fixed classifications instead of calling the model, so you can drive the real paste → classify → save flow offline:

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e \
  -Dspring-boot.run.additional-classpath-elements=target/test-classes
```

This is what the Playwright suite runs against — and it's why **no `OPENROUTER_API_KEY` exists anywhere in CI**.

---

## API

Session-cookie auth (`JSESSIONID`), CSRF in SPA mode: `GET /api/auth/csrf` sets an `XSRF-TOKEN` cookie that every mutating request must echo back in the `X-XSRF-TOKEN` header. Unauthenticated requests get a bare `401` — never a redirect.

| Method   | Path                                          | Purpose                                     |
| -------- | --------------------------------------------- | ------------------------------------------- |
| `GET`    | `/api/auth/csrf`                              | prime the CSRF token                        |
| `POST`   | `/api/auth/register`                          | create an account                           |
| `POST`   | `/api/auth/login`                             | start a session                             |
| `GET`    | `/api/auth/me`                                | current user                                |
| `POST`   | `/api/analyses`                               | paste a contract → classify → save          |
| `GET`    | `/api/analyses`                               | list my analyses                            |
| `GET`    | `/api/analyses/{id}`                          | read one analysis                           |
| `PATCH`  | `/api/analyses/{analysisId}/clauses/{clauseId}` | set a clause decision                     |
| `DELETE` | `/api/analyses/{id}`                          | delete an analysis                          |

Ownership is enforced **in the repository query**, not in a service-layer check. A missing or foreign id is a `404`, never a `403` — Falcon does not confirm that another user's analysis exists.

---

## Project layout

```
backend/    Java 25 / Spring Boot — packages: auth/, user/, analysis/
frontend/   Next.js App Router — (auth)/ and (app)/ route groups; e2e/ Playwright specs
context/    the 10x workflow contracts — foundation/ (prd, roadmap, tech-stack,
            infrastructure, test-plan), changes/, archive/ (the "why" behind each slice)
```

## CI

`.github/workflows/ci.yml` runs three path-filtered jobs on every push and PR: **backend** (`mvnw clean package`, including Testcontainers integration tests), **frontend** (lint + build), and **e2e** (Playwright against the `e2e` profile and a Postgres service container).

## License

TBD
