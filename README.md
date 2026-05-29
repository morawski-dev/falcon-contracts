# Falcon

**AI-powered contract review tool.** Paste a contract, get risk-flagged clauses in plain Polish plus a list of negotiation points.

Built as a project for the [10xDevs](https://10xdevs.pl) course. *Falcon* is the project codename.

> ⚠️ Falcon is an AI assistant, not a substitute for legal advice. Always have a qualified attorney review final terms.

---

## What it does

1. You log in and paste the text of a contract.
2. Falcon splits it into clauses and classifies each one with an LLM:
    - **risk level** — `LOW` / `MEDIUM` / `HIGH`
    - **risk type** — penalty, auto-renewal, no termination, unilateral change, etc.
    - **rationale** — a short plain-Polish explanation
3. It generates a list of **negotiation points** tied to the risky clauses.
4. You review the result, mark clauses (accept / negotiate / reject), and save the analysis.
5. Your analyses are private and accessible from your account.

---

## Tech stack

**Backend** — Java 25, Spring Boot 3.5, Maven, Spring AI 1.1 (OpenRouter), Spring Data JPA + Hibernate, Spring Security, Liquibase, PostgreSQL

**Frontend** — Next.js, TypeScript, Tailwind CSS, shadcn/ui

**AI** — Spring AI + OpenRouter (GPT-4o / Claude / Gemini), Langfuse for observability

**Infra** — Docker Compose (local), GitHub Actions (CI), AWS (production)

---

## Getting started

> Work in progress — setup instructions will be expanded as the project develops.

```bash
# clone
git clone https://github.com/morawski-dev/falcon-contracts.git
cd falcon-contracts

# start local environment (Postgres + app)
docker compose up -d

# backend
./mvnw spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

You'll need an OpenRouter API key set as `OPENROUTER_API_KEY`.

---

## Status

🚧 Early development. Core flow (paste → classify → negotiation points) is the current MVP focus. Document upload (PDF/DOCX), version comparison, and AWS deployment are planned as later layers.

## License

TBD
