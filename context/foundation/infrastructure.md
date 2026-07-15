---
project: Falcon (falcon-contracts)
researched_at: 2026-07-02
recommended_platform: AWS EC2 (single VM) + Docker Compose
runner_up: AWS EC2
context_type: mvp
tech_stack:
  language: Java 25 (LTS)
  framework: Spring Boot 4.0.7 (backend) + Next.js 16 (frontend)
  runtime: JVM container (Docker) + Node.js 24
---

## Recommendation

**Deploy the whole stack — Spring Boot backend, Next.js frontend, and PostgreSQL 18 — as three containers in a single `docker-compose` on one Amazon EC2 `t4g.medium` (2 vCPU / 4 GB, ARM/Graviton) in eu-west-1 (Ireland).**

This is a **conscious override of the agent-friendly scoring**, chosen from the working thread, not a scored winner. On the five criteria a raw VM scores *worse* than the previous leader (ECS Fargate): it fails "managed/serverless" outright — you own OS patching, the Docker daemon, and disk — and it only partially satisfies "stable deploy API," since a deploy is `ssh + docker compose pull && up -d`, not a deterministic one-command rollout. It wins on the axes the developer weighted for this MVP: **cost** (~$34/mo all-in, the cheapest persistent-process option that keeps the database at $0), **dev/prod parity** (the exact `compose.yaml` that runs locally runs in production — one file, one mental model), and **AWS familiarity** (same cloud as the `aws-ecs` production target, but the simplest compute primitive on it).

**Three things are consciously accepted and must not be forgotten:** (1) **one box = one point of failure** — no redundancy, no health-based cross-host recovery; (2) **you are the DBA and the sysadmin** — Postgres backups and OS/Docker patching are unbuilt TODOs until you build them; (3) **`t4g` is ARM** — every image must be `arm64` or the deploy fails (or silently runs under qemu emulation with a large perf hit). **ECS Fargate remains the documented graduation path**: the same containers move there when the single-box operational risk outweighs the cost saving.

> **Sizing rationale (why 4 GB, not 2 GB):** three co-resident processes — JVM backend (~1–1.5 GB: heap + metaspace + off-heap), Next.js `next start` (~0.4–0.7 GB), PostgreSQL 18 (~0.25–0.5 GB), plus OS + Docker daemon (~0.4–0.6 GB) — sum to ~2.5–3.3 GB. `t3/t4g.small` (2 GB) would OOM-kill under any real load. The bottleneck is **RAM (via the JVM), not CPU** — the workload is I/O-bound waiting on OpenRouter — so a 1:2 vCPU:GB "general purpose" burstable instance is the right shape. (Sizing checked 2026-07-04; pricing re-derived from the AWS Pricing API for eu-west-1 on 2026-07-10.)

## Platform Comparison

The tech stack is the decisive filter. Falcon's backend is a **long-running JVM (Spring Boot 4)** — a persistent process, not a serverless function — so serverless-only hosts (Vercel, Netlify) are eliminated as *backend* hosts before scoring. Among the persistent-process options, EC2+compose is the **least managed** choice; it is picked despite that, for cost and parity.

| Platform | CLI-first | Managed | Agent docs | Deploy API | MCP | Can host the JVM backend? |
|---|:---:|:---:|:---:|:---:|:---:|---|
| **AWS EC2 + Docker Compose** (chosen) | 🟡 Partial (`aws ec2` provisions; deploy = SSH+compose) | ❌ **Fail** (raw VM — you patch OS/Docker/disk) | 🟡 Partial (AWS HTML-heavy; compose docs good) | 🟡 Partial (SSH `compose up`, no auto-rollback) | 🟡 Partial (AWS MCP GA covers EC2; compose layer outside it) | ✅ **Yes** — any Docker image |
| **AWS ECS + Fargate** (runner-up) | ✅ Pass (`aws ecs`) | 🟡 Partial (you own VPC/ALB/IAM, but no OS) | 🟡 Partial | ✅ Pass (circuit-breaker rollback) | ✅ Pass (AWS MCP GA; ECS MCP preview) | ✅ Yes — Fargate Docker |
| **Render** (third) | ✅ Pass (CLI GA) | ✅ Pass | ✅ Pass (markdown) | ✅ Pass | ✅ Pass (MCP GA) | ✅ Yes — Docker |
| Railway | ✅ Pass | ✅ Pass | ✅ Pass | ✅ Pass | ✅ Pass (MCP GA) | ✅ Yes — Docker (Java 25 build unconfirmed) |
| Fly.io | ✅ Pass (`flyctl`) | ✅ Pass | ✅ Pass (llms.txt) | ✅ Pass | 🟡 Partial (MCP experimental) | ✅ Yes — Firecracker microVM |
| Vercel / Netlify | — | — | ✅ (frontend) | ✅ | 🟡/✅ | ❌ No JVM runtime (serverless only) |

**AWS EC2 + Docker Compose** — the developer's chosen target. The single genuine *technical* advantage over every managed option: **one box can serve both surfaces on one origin.** A reverse proxy (Caddy/nginx) in the same compose routes `/` to Next.js and `/api` to Spring Boot, so Spring Security session cookies are **first-party** — no `SameSite=None; Secure` cross-origin cookie tax, no CORS allow-list, which the ECS/Render split all require. The cost is that every managed guarantee (backups, patching, health-based restart, zero-downtime rollout, TLS) becomes something you build or accept the absence of. Highest operational-ownership option here — deliberately traded for the lowest bill and local/prod symmetry.

**AWS ECS + Fargate** (runner-up) — the production graduation target named in `tech-stack.md`. Serverless containers (no OS to patch), deployment circuit-breaker auto-rollback, scoped IAM. The *same* `eclipse-temurin:25` image you run under compose runs here unchanged, so nothing is wasted by starting on EC2 — this is the documented pivot when single-box risk (no backups, no HA, no zero-downtime) outweighs the ~$5–20/mo saving.

**Render** (third) — the fully-managed cheap escape hatch. ~$14/mo flat ($7 web + $7 Postgres 18 Frankfurt), official CLI + MCP GA, automated Postgres backups included. If self-hosting Postgres and patching an EC2 box becomes a chore the solo timeline can't absorb, this trades ~$-cheap-DIY for managed peace of mind at roughly the same price — same container image.

**Railway / Fly.io** — both viable managed-container hosts; Fly is the only one with a Warsaw (`waw`) region. Behind the AWS options here only because the developer's stated target is AWS, keeping the whole stack in one cloud/account/bill.

### Shortlisted Platforms

#### 1. AWS EC2 + Docker Compose (Recommended — developer's conscious choice)

Cheapest persistent-process option (~$34/mo 24/7, or ~$17–19/mo on a Mon–Fri window; DB free), exact local/prod parity (one `compose.yaml`), same cloud as the production target, and single-origin serving that eliminates the cross-surface cookie/CORS tax. Accepted price: no backups/HA/zero-downtime/patching out of the box — all owned by you.

#### 2. AWS ECS + Fargate (Runner-up — the production graduation path)

The `aws-ecs` target itself. Same image, but managed compute + circuit-breaker rollback + no OS patching. The pivot when the single box's missing operational guarantees start to bite.

#### 3. Render (Third — fully-managed escape hatch)

~$14/mo flat, managed Postgres with automated backups, CLI + MCP GA. Same Docker image. The zero-ops alternative if DIY Postgres/patching outweighs the AWS familiarity benefit.

## Anti-Bias Cross-Check: AWS EC2 + Docker Compose

### Devil's Advocate — Weaknesses

1. **Single VM = single point of failure with no automatic recovery.** One instance, one AZ, no redundancy. A kernel panic, a failed EC2 status check, or an AZ event is a full outage until a human intervenes. `docker compose` restarts a *crashed* container (`restart: unless-stopped`) but cannot fail over to another host, and it won't notice a **deadlocked-but-alive JVM** — a wedged app stays in rotation serving errors.
2. **You own OS + Docker patching, forever, on a public box holding sensitive data.** AL2023/Ubuntu security updates, Docker daemon CVEs, kernel patches — all manual. On a solo after-hours project, "patch the server" is the first chore to lapse. An unpatched internet-facing host processing confidential contracts is a standing liability that no managed platform would leave exposed.
3. **Postgres data sits on one EBS volume with zero backups by default.** `docker compose` Postgres makes you the DBA. No automated snapshots unless you configure AWS Backup/DLM or a `pg_dump` cron. A stray `docker compose down -v`, volume corruption, or a botched migration destroys the *only* copy of every user's contract data. EBS volumes are also **AZ-locked** — snapshots are the sole cross-AZ recovery.
4. **No zero-downtime deploy.** `docker compose pull && up -d` restarts the backend container; users hit a 20–30 s **JVM-warmup gap** on every deploy. No blue-green, no rolling, no connection draining. This silently discourages frequent deploys, which slows the fix loop the MVP most needs.
5. **Deploy is "SSH-and-hope," and secrets live on the box.** The deploy is a human/agent SSHing in and running compose — no deterministic success signal, no circuit-breaker, no auto-rollback (rollback = SSH in, set the previous image tag, `up -d` again). `OPENROUTER_API_KEY` and the DB password sit in a plaintext `.env` on a box whose SSH keys are one leak away from full compromise.

### Pre-Mortem — How This Could Fail

The developer picked EC2 + docker-compose because it was cheap (~$34/mo) and byte-for-byte identical to local dev. Six months later it was a slow-motion disaster. Nobody owned OS maintenance on a solo project, so the box was never patched — and the single EBS volume holding Postgres was never snapshotted, because "it's just running." When the instance failed a status check during a routine AZ event and had to be stopped and started, a corrupted volume took the only copy of every user's contract data with it; there were no backups to restore. Deploys, each a manual SSH + `docker compose up`, caused a 25-second JVM-warmup outage every time, so deploys became rare and fixes shipped slowly. `OPENROUTER_API_KEY` sat in plaintext `.env` on a box several old SSH keys still reached. When usage bumped, 4 GB of RAM meant the JVM, Node, and Postgres fought for memory and the OOM-killer began reaping Postgres mid-query. The stack was cheap, but every guarantee ECS+RDS would have provided for free — backups, patching, health-based restart, zero-downtime rollout — was an unbuilt TODO. Root cause: mistaking "same as local dev" for a production strategy.

### Unknown Unknowns

- **`t4g` is ARM — every image must be `arm64`.** Build x86 in CI without `--platform linux/arm64` (buildx) and the deploy either refuses to run or silently runs under **qemu emulation** with a large, hard-to-diagnose perf hit. Prod is "mysteriously slow" and nothing in the app explains it.
- **compose has no health-gated traffic control.** `healthcheck:` only gates `depends_on` ordering; it does **not** pull an unhealthy container out of rotation the way an ALB target group or ECS health check does. A deadlocked JVM keeps receiving requests.
- **Postgres competes with the JVM for the same 4 GB *and* the same EBS IOPS.** gp3 baseline is 3000 IOPS shared across all containers; a heavy JPA query during a JVM GC pause contends on the same disk and memory. On RDS this is isolated; here it is not.
- **A public-IP EC2 makes SSH (port 22) a standing attack surface.** The better pattern is **SSM Session Manager** (no open 22, IAM-brokered sessions, auditable) — but that is an explicit setup step most single-box tutorials skip.
- **Liquibase forward migrations don't roll back with a container revert.** Rolling the backend image back to a previous tag leaves the *schema* on the new version. Reversible changesets and decoupled schema/app deploys are mandatory, not optional.

## Operational Story

- **Preview deploys**: None on the production box (single environment). Preview = run `docker compose up` locally (identical stack), or stand up a second cheap `t4g.small` as staging. Per-PR preview environments are out of scope for a solo MVP.
- **Secrets**: `OPENROUTER_API_KEY` + the Postgres password live in a **`.env` file on the box (`chmod 600`)** referenced by compose, or better in **AWS SSM Parameter Store (Standard, free)** fetched at container start via the instance IAM role — **never committed** to the repo or baked into the image. The test profile already hardcodes a dummy `test` key, so CI needs no real secret. Rotation: update the parameter / `.env`, `docker compose up -d` to restart.
- **Rollback**: Manual and deterministic-*enough*. Tag every image with the git SHA; to roll back, open an **SSM session**, set the backend image tag to the previous SHA in `.env`/compose, `docker compose up -d`. Time-to-revert ≈ image pull + JVM warmup (~1–2 min). No auto-rollback on failed health. **Caveat: Liquibase migrations do not roll back** — forward changesets stay applied.
- **Approval**: Human-only — provisioning/terminating the instance, editing security-group rules, restoring from an EBS snapshot, rotating the DB password or API key, and any **`docker compose down -v`** (which deletes the Postgres volume). Agent-unattended (scoped IAM) — building/pushing `arm64` images, deploying via `ssm send-command` / an SSM session, reading logs. Prefer **SSM Session Manager over open SSH** so there is no standing port 22 and every action is auditable.
- **Logs**: `docker compose logs -f backend` over an SSM session, or configure the **`awslogs` logging driver** in compose to ship to CloudWatch and read read-only with `aws logs tail /falcon/backend --follow`. No managed log UI exists by default.

**Cost-conscious, secure single-box networking:** Security group allows **443/80 from anywhere and *no* port 22** (use SSM Session Manager for shell access). **TLS terminates in a Caddy/nginx container** in the same compose (automatic Let's Encrypt) — this is the cheap alternative to an ALB (which would add ~$18/mo); an ALB is only worth it if you later need managed health checks or multiple targets. **Postgres is never published to the host** — it listens only on the internal compose network, reachable by the backend container alone, so the sensitive data layer has no public surface.

## Cost (eu-west-1 Ireland, on-demand, 24/7)

| Line item | Detail | ~$/mo |
|---|---|---|
| Compute | `t4g.medium` (2 vCPU / 4 GB), $0.0368/h × 730 | ~$27 |
| Storage | 30 GB gp3 EBS root+data, $0.088/GB-mo | ~$2.6 |
| Public IPv4 | 1 address × $0.005/h — billed **in-use and idle alike** | ~$3.7 |
| Postgres backups | EBS snapshots (retained), $0.05/GB-mo | ~$1–3 |
| Database | self-hosted in compose | $0 |
| Egress | < 100 GB/mo → within free allowance | ~$0 |
| **Total** | | **~$34–36/mo (~138–146 zł)** |

> Every rate above was read from the AWS Pricing API (`pricing get-products`, `regionCode=eu-west-1`) on 2026-07-10. eu-west-1 is ~4% cheaper than eu-central-1 on compute ($0.0368 vs $0.0384/h) and ~8% on gp3 ($0.088 vs $0.0952/GB-mo) — a real but immaterial ~$0.6/mo at this scale. The region was chosen by the developer; the saving was not the reason.
>
> **The `Public IPv4` row did not exist before and is not optional.** Since Feb 2024 AWS charges $0.005/h for *every* public IPv4 address — `InUseAddress` and `IdleAddress` are the same price. A publicly reachable box pays it whether or not you allocate an Elastic IP. Earlier revisions of this table omitted it and therefore understated the 24/7 baseline by ~$3.7/mo.

- **If images are built *on the box*** (`docker compose build`), `next build` can spike >1 GB and contend with running services on 4 GB → step up to **`t4g.large` (8 GB), ~$54/mo** ($0.0736/h × 730), or add 2–4 GB swap. **Preferred instead: build `arm64` images in GitHub Actions, push to ECR/GHCR, and only `pull` on the box** — then 4 GB is comfortable and prod/CI images are identical.
- **Cost levers**: `stop` (not `terminate`) the instance when idle → compute drops to $0, but you keep paying EBS (~$2.6/mo) and the public IPv4 (~$3.7/mo) — a stopped box still costs ~$6.3/mo. A 1-year Savings Plan cuts compute ~40% for 24/7 running. **A scheduled 08:00–20:00 window (stop overnight) cuts compute by ~65% — see "Scheduled Uptime" below for the real math and its one genuine gotcha.**

## Scheduled Uptime — Run Only 08:00–20:00, Stop Overnight

The single-box design makes an **idle-hours shutdown** cheap and safe to add, because `stop` (unlike `terminate`) preserves the EBS root+data volumes — Postgres data survives every night intact. Running the box only during a 12-hour daytime window roughly **halves the compute bill** and is a natural fit for a contract-review tool whose users work business hours.

**This is an availability decision first, a cost decision second.** Outside the window the app is *fully down* — there is no degraded mode, no request queue, no wake-on-request. Confirm no user (nor uptime monitor, nor inbound webhook) needs Falcon between 20:00 and 08:00 before enabling it. For a solo, Polish-business-hours MVP this is usually fine; for anything with off-hours users it is not.

### Mechanism — EventBridge Scheduler, universal target (recommended)

Two cron schedules that call the EC2 API directly — **no Lambda, no extra code, effectively free** at this volume (EventBridge Scheduler's free tier dwarfs two invocations a day):

- **Start** — `cron(45 7 ? * * *)` → target `arn:aws:scheduler:::aws-sdk:ec2:startInstances` with input `{ "InstanceIds": ["i-xxxx"] }`
- **Stop** — `cron(0 20 ? * * *)` → target `arn:aws:scheduler:::aws-sdk:ec2:stopInstances`
- Set `ScheduleExpressionTimezone: "Europe/Warsaw"` on **both** schedules so the cron is written in local time and **DST-correct** — no manual UTC math, and the window won't drift an hour in summer.
- Start at **07:45**, not 08:00, so the cold-start (OS boot → Docker daemon → Postgres recovery → JVM + Next.js warmup, ~2–4 min) finishes *before* the window opens and the first real request at 08:00 hits a warm app.
- The Scheduler needs a scoped IAM role allowing **only** `ec2:StartInstances` / `ec2:StopInstances` on **this instance's ARN** — never `TerminateInstances`. Creating or editing these schedules is a **human-only** action (it controls whether production exists at all); the agent may read them.

*Alternatives considered:* **SSM Quick Setup Scheduler** (managed, console-driven, same effect — fine if you prefer a UI over a schedule definition) and **Instance Scheduler on AWS** (tag-based, DynamoDB-defined, multi-instance/multi-account — but it runs a polling Lambda ~$10/mo and is built for fleets, so it's overkill for one box). For a single VM, EventBridge Scheduler's two universal-target schedules are the least-moving-parts option.

### Cost impact (eu-west-1, 12-hour window)

| Line item | 24/7 (current) | 08:00–20:00, 7 days | 08:00–20:00, Mon–Fri |
|---|---:|---:|---:|
| Compute `t4g.medium` ($0.0368/h) | ~$27 (730 h) | ~$13 (≈364 h) | ~$10 (≈260 h) |
| EBS 30 GB gp3 — **billed while stopped too** | ~$2.6 | ~$2.6 | ~$2.6 |
| EBS snapshots | ~$1–3 | ~$1–3 | ~$1–3 |
| Public IPv4 / Elastic IP — **billed 24/7 in every column** | ~$3.7 | ~$3.7 | ~$3.7 |
| **Total** | **~$34–36** | **~$20–22** | **~$17–19** |

Net saving ≈ **$14/mo (7 days)** or **$17/mo (Mon–Fri, ~69 zł)**. The saving comes **entirely from compute**; every other line is identical across the three columns.

> **Correction to an earlier revision of this table.** It listed the Elastic IP as `~$0 (in-use)` under 24/7 and concluded the EIP charge "eats the saving." That is wrong. AWS prices `EU-PublicIPv4:InUseAddress` and `EU-PublicIPv4:IdleAddress` identically at $0.005/h (verified against the Pricing API, 2026-07-10), so a 24/7 box with an auto-assigned public IP pays the same ~$3.7/mo as a stopped box holding an EIP. The EIP is **cost-neutral between the two scenarios** — it buys a stable DNS target for free, relative to the 24/7 baseline. Scheduling therefore saves *more* than previously stated, not less.

### The gotchas

1. **Public IP changes on every restart — you need an Elastic IP for a stable domain.** An auto-assigned public IPv4 is *released* when the instance stops and a *different* one is assigned on start, so your DNS `A` record (and Caddy's Let's Encrypt cert) would point at the wrong box every morning. The fix is an **Elastic IP**, allocated once and never released. Since Feb 2024 AWS bills **all** public IPv4 at **$0.005/h** — `InUseAddress` and `IdleAddress` at the same rate — so the EIP costs ~$3.7/mo charged 24/7 whether the box is up or not. **This is not an extra cost of scheduling:** the 24/7 alternative pays the identical $0.005/h for its auto-assigned address. Allocate the EIP and stop worrying about it. (Skip it only if you access the box by raw, changing IP and have no custom domain — rare for a real deployment.)
2. **Overnight jobs must move inside the window.** *(This is the gotcha that actually bites.)* Any `pg_dump` / snapshot cron or `unattended-upgrades` you scheduled for the quiet small hours (02:00–04:00) will **silently never run** once the box is off then. Move backups to e.g. **19:30** (inside the window, before the stop fires) and patching to a daytime slot — otherwise the single-box design's *only* data-safety net stops firing the day you enable the schedule.

Also verify **graceful shutdown**: `stopInstances` triggers an ACPI OS shutdown → Docker sends `SIGTERM` to each container. Give Postgres room to checkpoint cleanly — set `stop_grace_period: 60s` on the Postgres service in `compose.yaml` so a shutdown mid-write can't leave the volume needing crash recovery on the next boot.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Single VM outage with no auto-failover (crash / AZ event) | Devil's advocate / Pre-mortem | M | H | Accept for MVP; document the manual recovery runbook; keep ECS Fargate as the HA graduation path; EBS snapshots enable rebuild |
| Postgres data lost — no backups on the single EBS volume | Devil's advocate / Pre-mortem | M | **H** | AWS Backup/DLM snapshot schedule on the data volume **from day one**; nightly `pg_dump` to S3 as second copy; never `down -v` in prod |
| OS / Docker daemon left unpatched on a public box with sensitive data | Devil's advocate | H | H | Enable AL2023 auto-updates / `unattended-upgrades`; SSM Patch Manager; close port 22 (SSM only); minimal SG |
| `t4g` is ARM — x86 image deploys fail or run under slow qemu emulation | Unknown unknowns | M | M | Build with `docker buildx --platform linux/arm64`; verify `docker inspect` arch on the box; CI matrix targets arm64 |
| JVM + Node + Postgres contend for 4 GB → OOM-killer reaps Postgres | Pre-mortem / Research finding | M | H | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60`; cap Postgres `shared_buffers`; monitor; step to `t4g.large` if sustained |
| JVM-warmup gap on every `compose up` = user-visible downtime | Devil's advocate | H | M | Deploy in low-traffic windows; accept for MVP; graduate to ECS rolling deploy for zero-downtime |
| Deadlocked-but-alive JVM stays in rotation (compose can't health-gate traffic) | Unknown unknowns | M | M | `healthcheck:` on `/actuator/health` + `restart: unless-stopped`; external uptime ping; ALB if traffic health-gating becomes essential |
| Secrets in plaintext `.env` on an SSH-reachable box | Devil's advocate | M | H | SSM Parameter Store via instance role; `chmod 600` if file-based; SSM Session Manager (no open 22); rotate leaked keys |
| Liquibase forward migration not reverted on image rollback | Research finding | M | H | Reversible changesets; decouple schema changes from app deploys; test the rollback path |
| Building on the box exhausts 4 GB during `next build`/Maven | Research finding | M | M | Build `arm64` images in GitHub Actions → push → `pull` on the box (box only runs, never builds) |
| Overnight shutdown = 100% downtime 20:00–08:00 (no off-hours access) | Devil's advocate | H (by design) | M | Confirm no off-hours users / monitors / webhooks before enabling; document the window; widen or disable the schedule if usage patterns change |
| Cold-start unavailability at window open (~2–4 min JVM+DB warmup) | Research finding | H | L | Start at 07:45 (ahead of 08:00); `restart: unless-stopped`; external ping at ~07:55 to confirm warm |
| Elastic IP required for stable DNS — billed ~$3.7/mo 24/7 even while stopped | Unknown unknowns | H | L | Budget the EIP, but note it is **cost-neutral vs. the 24/7 baseline** (AWS bills in-use and idle public IPv4 identically); do **not** release it (defeats the stable-DNS purpose) |
| Overnight backup / patch cron never fires once box is off nightly | Unknown unknowns | M | **H** | Move `pg_dump`/snapshot to ~19:30 (inside window); move patching to a daytime slot; verify the job actually ran |
| Ungraceful stop mid-write → Postgres crash recovery / corruption | Pre-mortem | L | H | `stop_grace_period: 60s` on Postgres; ensure OS shutdown waits for Docker to drain; snapshot before first enabling the schedule |

## Getting Started

Validated against this project's stack (Java 25, Spring Boot 4.0.7, Maven wrapper, Next.js 16 / Node 24, PostgreSQL 18; `t4g.medium` on ARM confirmed for all three runtimes 2026-07-04). Build `arm64` images; do not copy x86 marketing commands verbatim.

1. **Provision the box.** Launch a `t4g.medium` in eu-west-1 (Amazon Linux 2023 or Ubuntu 24.04 **arm64**), 30 GB gp3. Attach an **IAM instance role** granting SSM (Session Manager), ECR pull, SSM Parameter Store read, and CloudWatch Logs write. Security group: inbound **443/80 only, no 22**.
2. **Install Docker + the compose plugin** and enable **SSM Session Manager** for shell access (then confirm port 22 is closed in the SG).
3. **Build `arm64` images in CI** (GitHub Actions, per the stack's stated infra order): `docker buildx build --platform linux/arm64` for the backend (multi-stage on `eclipse-temurin:25-jre`, `./mvnw clean package -DskipTests` → `java -jar`) and the frontend (`node:24`, `pnpm build` → `next start`). Push to ECR (or GHCR).
4. **Write the production `compose.yaml`** (on the box, not committed with secrets): `backend` + `frontend` + `postgres:18` + a `caddy` reverse proxy. Named volume for Postgres data; `healthcheck:` on `/actuator/health`; `restart: unless-stopped`; secrets from `.env` (`chmod 600`) or SSM. Set backend `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60`. **Do not publish Postgres to the host** — internal network only. Caddy routes `/` → frontend and `/api` → backend on **one origin** (first-party cookies for Spring Security) and auto-provisions Let's Encrypt TLS.
5. **Set up backups before real data exists.** AWS Backup or DLM snapshot schedule on the Postgres data volume, plus a nightly `pg_dump` to S3. This is your only line of defense — the single-box design has no other.
6. **Deploy**: from an SSM session, `docker compose pull && docker compose up -d`. Roll back by setting the previous image tag and repeating. Read logs with `docker compose logs -f` or `aws logs tail`.

> Next step in the chain: activate the host's **Plan Mode** and prompt *"Wykonajmy pierwsze wdrożenie w oparciu o `@infrastructure.md`, zgodnie ze stackiem z `@tech-stack.md`"* — review the read-only plan (it must specify the exact `aws ec2` provisioning, the `buildx --platform linux/arm64` build, the SSM-based deploy, the manual backup/secret/SG gates, and verification steps) before any mutation hits AWS. The approved plan persists at `context/deployment/deploy-plan.md`.

## Out of Scope

The following were not evaluated in this research:
- Docker image / `compose.yaml` authoring (sketched in "Getting Started", not built or optimized here)
- CI/CD pipeline setup (GitHub Actions build→test→`arm64` image push is the stack's stated next infra layer, above this decision)
- Production-scale architecture (multi-AZ/HA, managed RDS migration, autoscaling, WAF, observability via Langfuse/OTel — all explicitly above-MVP per the PRD)
