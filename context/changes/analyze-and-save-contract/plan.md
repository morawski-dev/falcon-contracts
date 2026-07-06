# Analyze & Save a Pasted Contract (S-01) Implementation Plan

## Overview

S-01 is Falcon's north star: a logged-in user pastes contract text + a title, Falcon splits it into clauses via an LLM, classifies each (risk level/type + plain-Polish rationale), generates negotiation points for the risky ones, saves the result **owner-scoped**, and shows it as an annotated breakdown with a "not legal advice" disclaimer and continuous progress feedback during the ~15s wait. It proves the product's core hypothesis and is the PRD's Primary Success Criterion. The LLM is mocked in tests so the load-bearing e2e is deterministic and free.

## Current State Analysis

F-01 shipped the auth gate and the isolation machinery this slice builds on; the backend is otherwise domain-free beyond `users`. Full grounding is in `context/changes/analyze-and-save-contract/research.md` — key facts:

- **Spring AI is 2.0.0, not the reference doc's 1.1.x.** The verified 2.0.0 API is `chatClient.prompt().system(..).user(..).call().entity(ClauseAnalysisResult.class)` — it internally builds a `BeanOutputConverter`, **auto-injects the JSON-schema format instruction** (so we do NOT add `{format}` to the prompt), and runs the real converter on the reply. `docs/clause-classification.md` §4's explicit `BeanOutputConverter` + `converter.convert(raw)` is obsolete.
- **The deterministic test mock is a `@Bean @Primary ChatModel` lambda** returning a fixed `ChatResponse` — keeping the real `ChatClient` + converter + our service in the test path, never calling OpenRouter (research §2).
- **F-01 gives us**: `SecurityUtils.currentUserId()` (`backend/.../auth/SecurityUtils.java:18-23`, throws `IllegalStateException` if anonymous); auto-gating via `.anyRequest().authenticated()` (`SecurityConfig.java:38-42`); CORS already allows POST + GET (`SecurityConfig.java:75`); the `User`/entity/Liquibase patterns; and the test patterns + gotchas (`@SpringBootTest @Import(TestcontainersConfiguration.class)`, not `@DataJpaTest`; `@AutoConfigureMockMvc` from `org.springframework.boot.webmvc.test.autoconfigure`; `.with(csrf())` + `.with(user(appUserDetails))`; `new ObjectMapper()`).
- **Frontend** (F-01): `apiFetch` (`frontend/src/lib/api.ts`) already injects `credentials:'include'` + the `X-XSRF-TOKEN` echo on mutations; `auth.ts` is the thin-client shape to mirror; `proxy.ts` gates routes; login/dashboard pages are the client-component patterns to copy. Only `button/input/label/card` shadcn components exist; the theme's only chromatic token is `destructive` (red).

### Key Discoveries:

- `docs/clause-classification.md` §1-2 (Polish system + user prompts), §3 (the transient records + enums), §6 (the fixed-JSON mock) — the LLM contract, reused as-is (minus the 1.1.x call form).
- `docs/PRD.md:83-91` — the persisted data model (`Analysis`/`Clause`/`NegotiationPoint` fields + status enums); §8 (~line 124) — the required e2e test.
- `backend/.../user/User.java:12-53` + `db/changelog/changes/001-create-users.yaml` — the entity + migration templates to mirror.
- The LLM correlates a negotiation point to its clause only by a `clauseText` **string echo** — the persisted `NegotiationPoint.clauseId` must be resolved by matching that text to a saved `Clause` (decision below).

## Desired End State

A logged-in user visits `/analyses/new`, pastes a contract + title, and submits; the form shows a spinner + reassurance + indeterminate bar during the wait; on success they land on `/analyses/{id}` showing the disclaimer, each clause in document order with a color-coded risk badge + Polish risk-type label + rationale, and each negotiation point inline under its clause. The analysis is persisted `ANALYZED` and owner-scoped — another user gets 404. Empty input is blocked with an explanatory message; over-limit (>20k chars) input is rejected with a readable message; an LLM failure returns a controlled error and saves nothing. A deterministic MockMvc e2e (LLM mocked) proves the paste→save→flagged-clause+linked-point flow **and** cross-user isolation.

**Verify:** `cd backend && ./mvnw clean package` green (incl. the e2e + isolation + failure tests); `cd frontend && pnpm build && pnpm lint` pass; a manual browser walk of paste→progress→result→disclaimer succeeds against a locally-running backend.

## What We're NOT Doing

- **No per-clause decision status UI/endpoint** — `Clause.userDecision` defaults to `PENDING`; mutating it is S-02.
- **No history list** — `/analyses/{id}` is reachable by direct link; a list view is S-03. (`AnalysisRepository` gets `findByIdAndOwnerId` only, not `findAllByOwnerId`.)
- **No delete** — S-04.
- **No `DRAFT`/`REVIEWED` transitions** — S-01 persists directly as `ANALYZED`. The enum defines all three values (per PRD), but only `ANALYZED` is used.
- **No FAILED-analysis records** — an LLM failure is atomic: nothing is saved.
- **No PATCH/DELETE** — so `SecurityConfig.java:75`'s CORS method list (GET/POST) needs no change here; S-02/S-04 will extend it.
- **No contract chunking, re-analysis, streaming, or observability** — all above-MVP.
- **No live LLM call in automated tests** — the `ChatModel` is mocked.

## Implementation Approach

Vertical, bottom-up build (mirroring F-01): **persistence → LLM service → analyze-and-save API → frontend**. Each backend phase is independently green via `@SpringBootTest` + Testcontainers. The load-bearing e2e + cross-user isolation tests land in Phase 3, where the API + persistence + mocked LLM first coexist. Isolation is enforced by the F-01 convention: every owned-data read is `findByIdAndOwnerId(id, SecurityUtils.currentUserId())` → 404 when empty (never leak existence). New `/api/analyses/**` endpoints are auto-gated.

## Critical Implementation Details

- **`ddl-auto=validate` is strict — align long-text columns.** Long `String` fields must be **sized varchars** matching the migration (e.g. `@Column(length = 20000)` `rawText` ↔ `varchar(20000)`), exactly as F-01 did with `varchar(255)`. Do NOT use Postgres `text` — Hibernate's validator can reject the `text`↔`varchar` type mismatch and fail context load. Phase 1's context-load test catches any drift immediately.
- **Persist clauses before matching negotiation points.** `Clause` rows must have generated ids before a `NegotiationPoint.clauseId` can reference them. Order: build `Analysis` + its `Clause` children → `save` (cascade assigns clause ids) → for each LLM `NegotiationPoint`, match its `clauseText` to a saved `Clause.text` (exact, then a prefix/contains fallback) to set `clauseId` (nullable — leave null if no match) → save the negotiation points.
- **Run the LLM call outside the DB transaction.** `ContractAnalysisService.analyze()` (the ~15s OpenRouter round-trip) must NOT run inside `@Transactional` — only the persist is transactional (see Phase 3). Holding a Hikari connection + open transaction across the LLM call can exhaust the pool while doing no DB work.
- **Validate LLM field completeness before persisting.** A record with a null/blank required field maps to a `@Column(nullable=false)` entity and throws `DataIntegrityViolationException` on save — which F-01's *global* `AuthExceptionHandler` (`AuthExceptionHandler.java:17,30`) maps to `409 "Email already in use"`, a nonsensical message. Validate completeness in `ContractAnalysisService.analyze()` → `AnalysisFailedException` (→ 502) so malformed-but-parseable output never reaches a DB constraint.
- **`.entity()` auto-injects the schema.** Do NOT put `{format}` in the user prompt. Keep `response-format.type=json_object` in `application.properties` — it's complementary (forces valid JSON; the injected schema text carries the word "JSON" that OpenAI requires alongside `json_object`).
- **The mock `ChatModel` must be a real lambda, not a bare Mockito mock.** `DefaultChatClientUtils` calls `chatModel.getOptions().mutate()` with no null-check; a `@MockitoBean` returns `null` from the un-stubbed default `getOptions()` → NPE before the fixed JSON is used. The `@Bean @Primary ChatModel` lambda runs the real default `getOptions()` and avoids this (research §2). Use the lambda variant.
- **CSRF on POST:** `/api/analyses` POST needs the `X-XSRF-TOKEN` echo. `apiFetch` already sends it on non-GET, but the cookie must exist first — `lib/analyses.ts` calls `getCsrf()` before the POST (mirroring `auth.ts`).

## Phase 1: Persistence & migration

### Overview

The owner-scoped domain model (`Analysis`/`Clause`/`NegotiationPoint` entities + enums), the repository, and the Liquibase migration — the data foundation.

### Changes Required:

#### 1. Domain enums

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/{RiskLevel,RiskType,AnalysisStatus,ClauseDecision}.java`

**Intent**: The shared domain enums, persisted as strings and (for `RiskLevel`/`RiskType`) also the target of LLM deserialization in Phase 2.

**Contract**: `RiskLevel { LOW, MEDIUM, HIGH }`; `RiskType { PENALTY, AUTO_RENEWAL, NO_TERMINATION, UNILATERAL_CHANGE, LIABILITY, CONFIDENTIALITY, IP_RIGHTS, PAYMENT, OTHER }` (exactly `docs/clause-classification.md` §3); `AnalysisStatus { DRAFT, ANALYZED, REVIEWED }` (only `ANALYZED` used in S-01); `ClauseDecision { PENDING, ACCEPTED, TO_NEGOTIATE, REJECTED }`.

#### 2. JPA entities

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/{Analysis,Clause,NegotiationPoint}.java`

**Intent**: The persisted, owner-scoped aggregate. Mirror `User.java`'s style (`@Entity @Table`, `@Id @GeneratedValue(IDENTITY) Long id`, `@Column`, `Instant createdAt`, `protected` no-arg + all-args constructors). Schema is Liquibase-owned.

**Contract**:
- `Analysis` (`@Table(name="analyses")`): `Long id`; `Long ownerId` (`@Column(name="owner_id", nullable=false)` — plain FK column to `users.id`, no JPA relationship); `String title` (`varchar(200)`); `String rawText` (`@Column(name="raw_text", length=20000, nullable=false)`); `AnalysisStatus status` (`@Enumerated(STRING)`); `Instant createdAt`; and `@OneToMany(cascade=ALL, orphanRemoval=true)` collections of `Clause` and `NegotiationPoint` (via `analysis_id`).
- `Clause` (`@Table(name="clauses")`): `Long id`; link to `Analysis` (`analysis_id`); `String text` (`length=10000`); `RiskLevel riskLevel`; `RiskType riskType`; `String rationale` (`length=2000`); `ClauseDecision userDecision` (default `PENDING`). All enums `@Enumerated(STRING)`.
- `NegotiationPoint` (`@Table(name="negotiation_points")`): `Long id`; link to `Analysis` (`analysis_id`); `Long clauseId` (**nullable** — the matched clause); `String recommendation` (`length=2000`); `RiskLevel priority`.

#### 3. Repository

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java`

**Intent**: Owner-scoped access. Children are saved/loaded via the `Analysis` cascade, so only the aggregate root needs a repository in S-01.

**Contract**: `interface AnalysisRepository extends JpaRepository<Analysis, Long>` with `Optional<Analysis> findByIdAndOwnerId(Long id, Long ownerId)`. No bare `findById` is used for reads (the F-01 isolation convention).

#### 4. Liquibase migration

**File**: `backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml` (new) + edit `db/changelog/db.changelog-master.yaml`

**Intent**: Create `analyses`, `clauses`, `negotiation_points` with FKs; wire it in after `001` via a second `include`.

**Contract**: Follow the `001-create-users.yaml` changeset shape (`changeSet` id/author, `createTable`, `bigint autoIncrement` PK). Columns sized to match the entity `@Column(length=...)` values (`varchar(200/2000/10000/20000)`, `varchar` for enum strings, `timestamptz`). FKs: `analyses.owner_id → users(id)`; `clauses.analysis_id → analyses(id)` ON DELETE CASCADE; `negotiation_points.analysis_id → analyses(id)` ON DELETE CASCADE; `negotiation_points.clause_id → clauses(id)` (nullable). Master changelog gets `- include: { file: db/changelog/changes/002-create-analyses.yaml }`.

### Success Criteria:

#### Automated Verification:

- Context loads with 002 applied + entities validate against it: `cd backend && ./mvnw test -Dtest=FalconApplicationTests`
- `AnalysisRepositoryTest` passes: saving an `Analysis` with clauses + negotiation points cascades; `findByIdAndOwnerId` returns for the owner and is empty for a different owner id

#### Manual Verification:

- After `./mvnw spring-boot:run` (see Local-run prerequisites in Testing Strategy), the `analyses`/`clauses`/`negotiation_points` tables + FKs exist (Liquibase log or psql)

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: LLM analysis service

### Overview

The transient LLM records and `ContractAnalysisService` (the `.entity()` call + prompts + error mapping), covered by the deterministic mocked-`ChatModel` test.

### Changes Required:

#### 1. Transient LLM records

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/llm/{ClauseAnalysisResult,AnalyzedClause,NegotiationPoint}.java`

**Intent**: The structured-output target — the records `.entity()` maps the model's JSON into. In an `llm` sub-package to resolve the name collision with the `analysis.NegotiationPoint` entity (no rename).

**Contract**: Exactly `docs/clause-classification.md` §3: `ClauseAnalysisResult(List<AnalyzedClause> clauses, List<NegotiationPoint> negotiationPoints)`; `AnalyzedClause(String text, RiskLevel riskLevel, RiskType riskType, String rationale)`; `NegotiationPoint(String clauseText, String recommendation, RiskLevel priority)`. Reuse the `analysis` enums.

#### 2. Prompts

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/ContractAnalysisService.java` (constants) — source text: `docs/clause-classification.md` §1-2

**Intent**: The Polish system prompt (verbatim from §1) and the user-prompt template (§2) — **without** the `{format}` placeholder (`.entity()` injects the schema).

**Contract**: `SYSTEM_PROMPT` = §1 verbatim; `USER_PROMPT` = §2's "Przeanalizuj poniższą umowę…" with the contract text interpolated, no `{format}` line.

#### 3. Analysis service

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/ContractAnalysisService.java`, `AnalysisFailedException.java`

**Intent**: Call the LLM via Spring AI 2.0's structured output and return the records; map model/parse failures to a controlled domain exception.

**Contract**: constructor takes `ChatClient.Builder` and `.build()`s it. `ClauseAnalysisResult analyze(String contractText)`:

```java
try {
    ClauseAnalysisResult result = chatClient.prompt()
        .system(SYSTEM_PROMPT)
        .user(u -> u.text(USER_PROMPT).param("contractText", contractText))
        .call()
        .entity(ClauseAnalysisResult.class);          // real BeanOutputConverter runs; NO {format}
    if (result == null || result.clauses() == null || result.clauses().isEmpty())
        throw new AnalysisFailedException("Model returned no clauses");
    validateFieldsPresent(result);   // non-blank text/rationale/recommendation + non-null enums, else AnalysisFailedException
    return result;
} catch (IllegalStateException | JacksonException e) {  // IllegalStateException: out-of-enum/schema
                                                          // mismatch (BeanOutputConverter-wrapped).
                                                          // JacksonException (tools.jackson.core):
                                                          // raw parse failures, e.g. non-JSON content,
                                                          // surface unwrapped — verified empirically.
    throw new AnalysisFailedException("Failed to analyze contract", e);
}
```

`AnalysisFailedException extends RuntimeException`. `validateFieldsPresent` rejects any clause with a null/blank `text`/`rationale` or null `riskLevel`/`riskType`, and any point with a null/blank `recommendation` or null `priority` — records permit nulls, so this keeps malformed-but-parseable output on the controlled-error path instead of a DB constraint violation (see Critical Implementation Details). (The 20k length cap is enforced upstream as DTO validation in Phase 3, not here.)

#### 4. Deterministic mock test

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/ContractAnalysisServiceTest.java`

**Intent**: Prove `analyze()` maps a fixed JSON reply into the records — without calling OpenRouter — and that failures raise `AnalysisFailedException`.

**Contract**: A plain JUnit test — no Spring context needed, since `ContractAnalysisService` depends only on `ChatClient.Builder`, which `ChatClient.builder(ChatModel)` (a verified static factory on 2.0.0) can construct directly around a stub lambda, one per test case (each test needs a distinct mock response):

```java
private ContractAnalysisService serviceReturning(String content) {
    ChatClient.Builder builder = ChatClient.builder(
            prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    return new ContractAnalysisService(builder);
}
```

This still keeps the real `ChatClient` + `BeanOutputConverter`/`.entity()` + service logic in the test path (never calls OpenRouter). `FIXED_JSON` = `docs/clause-classification.md` §6 (auto-renewal → HIGH + linked point). Tests: (a) `analyze()` returns a `ClauseAnalysisResult` whose clause is `HIGH`/`AUTO_RENEWAL` with a negotiation point; (b) non-JSON content → `AnalysisFailedException` (surfaces as `tools.jackson.core.JacksonException`, not `IllegalStateException` — verified empirically, see Critical Implementation Details); (c) an empty-clauses mock → `AnalysisFailedException`; (d) an incomplete-field mock (blank `text`) → `AnalysisFailedException`.

### Success Criteria:

#### Automated Verification:

- Full build + suite green: `cd backend && ./mvnw clean package`
- `ContractAnalysisServiceTest` mapping case: mocked fixed JSON → `ClauseAnalysisResult` with a `HIGH`/`AUTO_RENEWAL` clause + linked negotiation point
- Failure cases: malformed JSON → `AnalysisFailedException`; empty clauses → `AnalysisFailedException`; a clause with a null/blank required field → `AnalysisFailedException`

#### Manual Verification:

- (Optional) With a real `OPENROUTER_API_KEY` set, a one-off live `analyze()` on a short sample contract returns a well-formed `ClauseAnalysisResult` — the live model+converter sanity-check the static research deferred

**Implementation Note**: Pause for manual confirmation before Phase 3.

---

## Phase 3: Analyze-and-save API

### Overview

The orchestration (validate → analyze → map records to entities, resolving `clauseId` by text match → persist `ANALYZED` → return), the two endpoints, DTOs, and error mapping — carrying the load-bearing e2e + cross-user isolation tests.

### Changes Required:

#### 1. Request/response DTOs

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/dto/{CreateAnalysisRequest,AnalysisResponse,ClauseResponse,NegotiationPointResponse}.java`

**Intent**: Typed, validated request + the full analysis response the frontend renders.

**Contract**: `CreateAnalysisRequest(@NotBlank @Size(max=200) String title, @NotBlank @Size(max=20000) String rawText)` — the `@Size(max=20000)` **is** the input cap (→ 400 automatically, like F-01's validation). `AnalysisResponse(Long id, String title, AnalysisStatus status, Instant createdAt, List<ClauseResponse> clauses, List<NegotiationPointResponse> negotiationPoints)`; `ClauseResponse(Long id, String text, RiskLevel riskLevel, RiskType riskType, String rationale, ClauseDecision userDecision)`; `NegotiationPointResponse(Long id, Long clauseId, String recommendation, RiskLevel priority)`. All records.

#### 2. Orchestration service

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java`

**Intent**: Tie analysis to persistence, owner-scoped, with the clause-matching link.

**Contract**: `AnalysisResponse createAnalysis(String title, String rawText, Long ownerId)` — call `ContractAnalysisService.analyze(rawText)`; build `Analysis(ownerId, title, rawText, ANALYZED)`; map each `AnalyzedClause` → `Clause` (userDecision `PENDING`); persist (cascade); then match each LLM `NegotiationPoint.clauseText` → saved `Clause` (exact, then prefix/contains) to set `clauseId` (null if no match) and persist the `NegotiationPoint` entities (see Critical Implementation Details for ordering); return the mapped `AnalysisResponse`. `AnalysisResponse getAnalysis(Long id, Long ownerId)` — `findByIdAndOwnerId(...)` → map, or throw `ResponseStatusException(NOT_FOUND)` (no existence leak). **The LLM call runs outside any transaction**: `createAnalysis` calls `analyze()` first (no tx), then delegates the map+save (clauses cascade, then match+save points) to a short `@Transactional` persist method — so the persist stays atomic ("save nothing on failure") without holding a DB connection across the ~15s LLM call.

#### 3. Controller

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java`

**Intent**: The REST surface, owner from the authenticated principal. Mirror `AuthController` conventions.

**Contract**: `@RestController @RequestMapping("/api/analyses")`. `POST` (`@Valid CreateAnalysisRequest`, `@AuthenticationPrincipal AppUserDetails principal`) → `201` `AnalysisResponse` (owner = `principal.getId()` or `SecurityUtils.currentUserId()`). `GET /{id}` → `200` `AnalysisResponse` or `404`. Both auto-gated by F-01's `.anyRequest().authenticated()`.

#### 4. Error mapping

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisExceptionHandler.java` (`@RestControllerAdvice`)

**Intent**: Map the LLM-failure exception to a controlled status (validation 400 and `ResponseStatusException` 404 are already handled by Spring / F-01's conventions).

**Contract**: `@ExceptionHandler(AnalysisFailedException.class)` → `502 Bad Gateway` with a small `{ "error": "..." }` body (a readable "analysis failed, try again" message; do not echo model internals).

### Success Criteria:

#### Automated Verification:

- Load-bearing e2e (`AnalysisFlowTest`, mocked `ChatModel`): authenticated `POST /api/analyses` with a risky contract → `201`; the response **and** a follow-up `GET /api/analyses/{id}` show a `HIGH`/`MEDIUM` clause with a linked `NegotiationPoint`
- Cross-user isolation: user B's `GET /api/analyses/{A's id}` → `404`
- Error/validation: LLM failure → `502`; blank title or `rawText` > 20000 chars → `400`; unknown id → `404`
- Whole suite green: `cd backend && ./mvnw clean package`

#### Manual Verification:

- (Optional) A REST-client `POST` → `GET` walk with a real session cookie + `X-XSRF-TOKEN` header returns the saved analysis

**Implementation Note**: Pause for manual confirmation before Phase 4.

---

## Phase 4: Frontend analyze flow

### Overview

The browser flow: the paste form with progress feedback, the result view with color-coded badges + inline negotiation points + disclaimer, error/empty states, and route gating. Polish copy.

### Changes Required:

#### 1. shadcn components

**File**: `frontend/src/components/ui/*` (via CLI)

**Intent**: The primitives S-01 needs beyond F-01's four.

**Contract**: `cd frontend && pnpm dlx shadcn@latest add textarea badge alert separator skeleton`. Spinner = `lucide-react`'s `Loader2` + `animate-spin` (already installed).

#### 2. Analyses data client

**File**: `frontend/src/lib/analyses.ts`

**Intent**: Typed create/get calls mirroring `auth.ts`, reusing `apiFetch`.

**Contract**: TS types mirroring `AnalysisResponse`/`ClauseResponse`/`NegotiationPointResponse` + the enums. `createAnalysis(title, rawText)` → `getCsrf()` then `POST /api/analyses` → `AnalysisResponse`; `getAnalysis(id)` → `GET /api/analyses/{id}`.

#### 3. Paste form

**File**: `frontend/src/app/analyses/new/page.tsx`

**Intent**: Title + contract textarea, submit with progress feedback and inline errors. Mirror the login-page client-component pattern.

**Contract**: `"use client"`; `Input` (title) + `Textarea` (contract) + submit `Button`. On submit: client-side empty guard (`required` + trimmed non-empty, else inline `text-destructive` "Wklej treść umowy…"); `createAnalysis` → `router.push('/analyses/'+id)`. While `submitting`: disable the form, show `Loader2` spinner + "Analizuję umowę…" + "To zwykle trwa kilkanaście sekund. Nie zamykaj tej strony." + an indeterminate animated progress bar. Surface `ApiError` inline (400 over-limit → "Umowa jest zbyt długa…"; 502 → "Nie udało się przeanalizować…"). A short disclaimer reminder near the button.

#### 4. Result view

**File**: `frontend/src/app/analyses/[id]/page.tsx`, `frontend/src/lib/risk.ts` (helpers)

**Intent**: The annotated breakdown: disclaimer, clauses in document order with color-coded risk + Polish risk-type + rationale, negotiation points inline under their clause. Mirror the dashboard fetch-on-mount pattern.

**Contract**: `"use client"`; fetch `getAnalysis(id)` on mount (`.catch` → `401`→`/login`, `404`→ a "not found" `Alert`; `loading` guard). Render: a persistent `Alert` at top — title "To nie jest porada prawna", body explaining supporting-analysis-not-legal-advice. Then each clause (document order) as a `Card`: a risk `Badge` colored via `risk.ts` (HIGH→red/`destructive`, MEDIUM→amber, LOW→emerald, with dark variants), a risk-type `Badge` (Polish label from a `RiskType`→PL map in `risk.ts`), and the rationale; its negotiation point(s) — grouped by `clauseId` — rendered directly beneath, with any `clauseId===null` points in a trailing "Pozostałe uwagi" section after a `Separator`.

#### 5. Route gating + entry point

**File**: `frontend/src/proxy.ts`, `frontend/src/app/dashboard/page.tsx`

**Intent**: Gate the new routes and provide an entry link.

**Contract**: `proxy.ts` matcher → `['/dashboard/:path*', '/analyses/:path*']`. `dashboard/page.tsx` gains a "Nowa analiza" link to `/analyses/new`.

### Success Criteria:

#### Automated Verification:

- Frontend builds: `cd frontend && pnpm build`
- Lint passes: `cd frontend && pnpm lint`

#### Manual Verification:

- Paste a contract containing an auto-renewal clause + a title → progress feedback shows during the wait → lands on `/analyses/{id}` with a HIGH badge + a linked negotiation point + the disclaimer
- Empty submit → inline explanatory error; a >20k-char paste → readable "too long" message; an induced failure → "could not analyze" message
- While logged out, direct-navigating to `/analyses/new` or `/analyses/{id}` → redirected to `/login`
- Risk badges are color-coded (red/amber/green) and risk types display Polish labels

**Implementation Note**: Final phase — after manual confirmation, S-01 (the north star) is delivered.

---

## Testing Strategy

### Unit / slice tests (backend, deterministic):

- `AnalysisRepositoryTest` — `@SpringBootTest @Import(TestcontainersConfiguration.class)` (NOT `@DataJpaTest`); cascade save of `Analysis`+clauses+points; `findByIdAndOwnerId` returns for owner, empty for a different owner; `@AfterEach deleteAll()`.
- `ContractAnalysisServiceTest` — a plain unit test (no Spring context) using `ChatClient.builder(ChatModel)` around a per-test stub lambda: fixed JSON → records mapping; malformed/empty/incomplete-field → `AnalysisFailedException`.
- `AnalysisFlowTest` — the load-bearing e2e (MockMvc + mocked `ChatModel` + `.with(csrf())` + `.with(user(appUserDetails))` for two persisted users): paste→save→flagged clause + linked point; cross-user 404; validation→400; unknown id→404. Uses `new ObjectMapper()` (no injectable bean). The `LLM failure→502` case uses a **separate** test config / `@MockitoBean ChatModel` override returning malformed content — distinct from the happy-path good-JSON `@Bean`.

### Integration:

- `FalconApplicationTests.contextLoads` stays green with the new entities + 002 migration (dummy `OPENROUTER_API_KEY=test` keeps the AI autoconfig booting; the mocked `ChatModel` only applies where imported).

### Manual Testing Steps:

1. **Local-run prerequisites**: Postgres up (`docker compose up` from repo root / the compose integration) and a dummy `OPENROUTER_API_KEY` exported (for the automated flow) OR a **real** `OPENROUTER_API_KEY` (to exercise the live LLM in the browser). Start `./mvnw spring-boot:run` + `pnpm dev`.
2. Register/log in, go to `/analyses/new`, paste a contract with an auto-renewal clause + a title, submit; confirm the progress UX and the saved result with a HIGH badge + linked point + disclaimer.
3. Try an empty submit and a >20k paste; confirm the explanatory messages.
4. Log out; direct-navigate to `/analyses/new`; confirm redirect to `/login`.

## Performance Considerations

The single ~15s p95 cost is the synchronous LLM call (`POST /api/analyses`); the frontend keeps the form disabled with continuous feedback to prevent double-submit and convey progress. Persistence is trivial at MVP scale. No caching/streaming (above-MVP).

## Migration Notes

`002-create-analyses.yaml` adds three tables + FKs to the existing (users-only) schema; no data backfill. `ddl-auto=validate` keeps entities and migration a matched pair — a mismatch fails startup (Phase 1's context-load test surfaces it).

## References

- Research: `context/changes/analyze-and-save-contract/research.md` (Spring AI 2.0 API + mock recipe, F-01 reuse surface, frontend plan)
- LLM contract: `docs/clause-classification.md` §1-2 (prompts), §3 (records/enums), §6 (fixed-JSON mock)
- Data model: `docs/PRD.md:83-91`; required e2e: `docs/PRD.md` §8
- F-01 reuse: `backend/.../auth/SecurityUtils.java:18-23`, `.../auth/SecurityConfig.java:38-42,75`, `.../user/User.java:12-53`, `db/changelog/changes/001-create-users.yaml`, `.../auth/AuthController.java` + `AuthControllerTest.java`
- Frontend reuse: `frontend/src/lib/{api,auth}.ts`, `frontend/src/proxy.ts`, `frontend/src/app/(auth)/login/page.tsx`, `frontend/src/app/dashboard/page.tsx`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Persistence & migration

#### Automated

- [x] 1.1 Context loads with 002 applied + entities validate (`./mvnw test -Dtest=FalconApplicationTests`) — 31d35b2
- [x] 1.2 `AnalysisRepositoryTest`: cascade save + `findByIdAndOwnerId` owner-scoping (owner vs non-owner) — 31d35b2

#### Manual

- [x] 1.3 `analyses`/`clauses`/`negotiation_points` tables + FKs exist after `spring-boot:run` — 31d35b2

### Phase 2: LLM analysis service

#### Automated

- [x] 2.1 Full build + suite green (`./mvnw clean package`) — 7599aa1
- [x] 2.2 `ContractAnalysisServiceTest` mapping: mocked fixed JSON → `ClauseAnalysisResult` with a HIGH/AUTO_RENEWAL clause + linked point — 7599aa1
- [x] 2.3 Failure cases: malformed JSON, empty clauses, and null/blank required field → `AnalysisFailedException` — 7599aa1

#### Manual

- [ ] 2.4 (Optional) Live `analyze()` with a real `OPENROUTER_API_KEY` returns a well-formed result — skipped by user decision, no `OPENROUTER_API_KEY` available

### Phase 3: Analyze-and-save API

#### Automated

- [x] 3.1 Load-bearing e2e: `POST` risky contract → 201 saved; response + `GET` show a HIGH/MEDIUM clause with a linked negotiation point
- [x] 3.2 Cross-user isolation: user B `GET` user A's analysis → 404
- [x] 3.3 Error/validation: LLM failure → 502; blank title / >20k rawText → 400; unknown id → 404
- [x] 3.4 Whole suite green (`./mvnw clean package`)

#### Manual

- [ ] 3.5 (Optional) curl/REST-client POST→GET walk with a real session cookie + XSRF header — skipped by user decision; automated e2e already proves the endpoint contract

### Phase 4: Frontend analyze flow

#### Automated

- [ ] 4.1 Frontend builds (`pnpm build`)
- [ ] 4.2 Lint passes (`pnpm lint`)

#### Manual

- [ ] 4.3 Paste auto-renewal contract + title → progress feedback → result with HIGH badge + linked point + disclaimer
- [ ] 4.4 Empty submit → inline error; >20k paste → "too long"; induced failure → "could not analyze"
- [ ] 4.5 Direct-navigate to `/analyses/new` or `/analyses/{id}` while logged out → redirected to `/login`
- [ ] 4.6 Risk badges are color-coded (red/amber/green); risk types show Polish labels
