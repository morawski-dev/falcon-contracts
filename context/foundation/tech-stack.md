---
starter_id: spring
package_manager: maven
project_name: falcon-contracts
hints:
  language_family: multi
  team_size: solo
  deployment_target: aws-ec2
  ci_provider: github-actions
  ci_default_flow: manual-promotion
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: true
  has_background_jobs: false
---

## Why this stack

Falcon is a solo, after-hours web-app MVP (≈3 weeks, small scale) whose core value is an AI decision on every contract clause — so the two load-bearing priors are `has_ai` and `has_auth` over sensitive per-user data. Spring Boot is the recommended default for `(web, java)` and clears all four agent-friendly gates with `verified` bootstrapper confidence, and it matches the builder's deliberate backend decision: Spring Boot 4.0.7 + Spring AI 2.0.0 GA on Java 25, built with Maven, over PostgreSQL, with Spring Security for per-user isolation and Spring AI (OpenRouter, OpenAI-compatible) for clause classification and structured output. The project is polyglot (`language_family: multi`): `spring` anchors the backend, with a separate Next.js / React / Tailwind / shadcn-ui frontend as the second surface the bootstrapper scaffolds or the builder adds manually. Deployment starts self-host on Docker Compose (backend + Postgres) and targets aws-ec2 for production, treated as a layer above the MVP. CI runs on GitHub Actions with manual promotion, mirroring the stated sequence: working locally → CI build+tests → production later.
