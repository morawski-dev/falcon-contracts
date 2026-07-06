---
bootstrapped_at: 2026-07-03T15:23:19Z
starter_id: spring
starter_name: Spring Boot
project_name: falcon-contracts
language_family: multi
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

# Bootstrap verification — Falcon

## Hand-off

Copied verbatim from `context/foundation/tech-stack.md`:

```yaml
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
```

**Why this stack** (from hand-off body): Falcon is a solo, after-hours web-app MVP (≈3 weeks, small scale) whose core value is an AI decision on every contract clause — so the two load-bearing priors are `has_ai` and `has_auth` over sensitive per-user data. Spring Boot is the recommended default for `(web, java)` and clears all four agent-friendly gates with `verified` bootstrapper confidence, matching the builder's deliberate backend decision: Spring Boot 4.0.7 + Spring AI 2.0.0 GA on Java 25, Maven, PostgreSQL, Spring Security for per-user isolation, Spring AI (OpenRouter) for clause classification and structured output. The project is polyglot (`multi`): `spring` anchors the backend, with a separate Next.js frontend as the second surface. Deployment starts self-host on Docker Compose and targets aws-ecs for production. CI on GitHub Actions with manual promotion.

## Pre-scaffold verification

| Signal      | Value    | Severity | Notes                                                        |
| ----------- | -------- | -------- | ------------------------------------------------------------ |
| npm package | not run  | n/a      | non-JS starter; `cmd_template` is a `curl`/`tar` invocation, no npm CLI to check |
| GitHub repo | not run  | n/a      | card `docs_url` is `docs.spring.io/spring-boot/` — not a `github.com/<owner>/<repo>` URL, so no recency signal available |

No recency signal available for this starter. Proceeded per WARN-AND-CONTINUE.

## Scaffold log

**Resolved invocation** (adapted from the `spring` card `cmd_template`): the card template (`curl … start.spring.io … -d javaVersion=21 -d groupId=com.example -d artifactId={name} | tar -xzf -`) extracts into cwd rather than a `{name}` subdirectory, which is incompatible with the temp-dir isolation the conflict policy requires. It was adapted with explicit user consent to (a) extract into `.bootstrap-scaffold/` for safe isolation and (b) match the builder's decided stack:

```
curl -sSf https://start.spring.io/starter.tgz \
  -d dependencies=web,data-jpa,security,liquibase,postgresql,actuator,validation,devtools \
  -d type=maven-project -d language=java -d javaVersion=25 \
  -d groupId=com.morawski -d artifactId=falcon -d name=falcon \
  -d packageName=com.morawski.falcon | tar -xzf - -C .bootstrap-scaffold
```

**Strategy**: subdir-then-move (extraction target adapted to `.bootstrap-scaffold/` because the template does not create a `{name}` directory)
**Exit code**: 0
**Files moved**: 8 top-level paths — `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `HELP.md`, `src/` (plus the nested `src/` tree and Liquibase's `src/main/resources/db/changelog/`)
**Conflicts (.scaffold siblings)**: none — no scaffold path collided with an existing cwd path
**.gitignore handling**: moved silently — cwd had no pre-existing `.gitignore`
**context/ preservation**: confirmed — `context/{prd.md, shape-notes.md, tech-stack.md, README.md}` untouched
**.bootstrap-scaffold cleanup**: deleted

Generated project facts (from `pom.xml`):
- `<parent>` Spring Boot **4.1.0** (Initializr's current default — NOT the decided 4.0.7; see Next steps)
- `<java.version>` **25**
- coordinates `com.morawski:falcon`, package `com.morawski.falcon`, main class `FalconApplication`
- dependencies: `spring-boot-starter-webmvc` (Boot 4 renamed `web`), `-data-jpa`, `-security`, `-liquibase`, `-validation`, `-actuator`, `devtools`, `postgresql`, plus Boot 4's per-module `-test` starters
- **not present** (deliberate, manual follow-up): Spring AI 2.0.0 BOM + `spring-ai-starter-model-openai`

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for `multi`
**Recommended external tool**: the scaffolded backend is Java — configure **OWASP Dependency-Check** or **Snyk** for JVM dependency scanning. When the Next.js frontend is added, run **`npm audit`** against its `package.json` separately. No single tool covers this multi-language stack.

## Hints recorded but not acted on

| Hint                    | Value              |
| ----------------------- | ------------------ |
| bootstrapper_confidence | verified           |
| quality_override        | false              |
| path_taken              | standard           |
| self_check_answers      | null               |
| team_size               | solo               |
| deployment_target       | aws-ecs            |
| ci_provider             | github-actions     |
| ci_default_flow         | manual-promotion   |
| has_auth                | true               |
| has_payments            | false              |
| has_realtime            | false              |
| has_ai                  | true               |
| has_background_jobs     | false              |

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git init` is not needed — this cwd is already a git repo.
- No `.scaffold` siblings were created (clean move-up), so nothing to reconcile there.
- Audit findings: none run (Java has no built-in audit); wire OWASP Dependency-Check / Snyk when convenient.

Falcon-specific follow-ups (the deliberate "last mile" of matching `docs/tech-stack.md`):
1. **Pin Spring Boot to your decided 4.0.7** — Initializr defaulted the `<parent>` to `4.1.0`. Change it to `4.0.7` in `pom.xml` (or keep `4.1.0` — it is also supported by Spring AI 2.0.x; the decision was 4.0.7).
2. **Add Spring AI 2.0.0** — add the Spring AI BOM (`org.springframework.ai:spring-ai-bom:2.0.0`) to `<dependencyManagement>` and the `spring-ai-starter-model-openai` dependency; configure `spring.ai.openai.base-url=https://openrouter.ai/api/v1` + `api-key` per `docs/clause-classification.md`.
3. **Scaffold the Next.js frontend** — the `spring` starter is backend-only; the decided Next.js 16 / React 19 / Tailwind 4 / shadcn-ui frontend is a separate surface.
4. **Note Boot 4 naming** — `web` mapped to `spring-boot-starter-webmvc`, and each module has a dedicated `-test` starter (Boot 4.x convention).
