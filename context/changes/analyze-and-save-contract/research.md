---
date: 2026-07-06T12:06:51+02:00
researcher: Mateusz Morawski
git_commit: 906d4d092c435dae7b66567caabf178c080953b4
branch: main
repository: falcon-contracts
topic: "S-01: Analyze & save a pasted contract — LLM structured output, persistence, and the paste→result flow"
tags: [research, codebase, spring-ai, structured-output, contract-analysis, persistence, owner-scoping, frontend, next16]
status: complete
last_updated: 2026-07-06
last_updated_by: Mateusz Morawski
---

# Research: S-01 — Analyze & save a pasted contract

**Date**: 2026-07-06T12:06:51+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: 906d4d0 (`906d4d092c435dae7b66567caabf178c080953b4`)
**Branch**: main
**Repository**: falcon-contracts

## Research Question

What must the plan for S-01 (`analyze-and-save-contract`, the roadmap's north star) know to be implementable — across the Spring AI 2.0.0 structured-output integration (the load-bearing unknown), the persisted domain model, the F-01 reuse surface, and the Next.js paste→result flow — verified against the actual resolved dependencies rather than the (1.1.x-era) reference doc?

## Summary

S-01 is implementable with **no unresolved framework unknowns**. The riskiest assumption — that Spring AI 2.0.0's structured-output path reliably maps the model's JSON into records and can be deterministically mocked — is resolved:

- **The exact 2.0.0 API is `chatClient.prompt().system(..).user(..).call().entity(ClauseAnalysisResult.class)`.** It internally constructs a `BeanOutputConverter`, auto-injects the JSON-schema format instruction (so we do **not** inject `{format}` ourselves — the 1.1.x reference's explicit converter is obsolete), and runs the real converter on the model's reply. Verified against the resolved jars/bytecode, not docs.
- **The deterministic mock is a one-liner `ChatModel` replacement** that lets the *real* `ChatClient` + `BeanOutputConverter` + our service run against a fixed JSON string — so the load-bearing e2e test genuinely covers our prompt-building and JSON→record mapping, never touches OpenRouter, and stays free/stable in CI.
- **The persistence + owner-scoping layer is a clean extension of F-01**: mirror the `User`/`UserRepository`/Liquibase patterns, key `Analysis.ownerId` off `SecurityUtils.currentUserId()`, and scope every read with a `findByIdAndOwnerId`-style query. New `/api/analyses/**` endpoints are auto-gated by F-01's `.anyRequest().authenticated()`.
- **The frontend is a straightforward extension** of F-01's page/lib patterns: add 5 shadcn components + a `lib/analyses.ts`, a `/analyses/new` paste form and `/analyses/[id]` result view, and extend `proxy.ts`'s matcher.

The remaining decisions are **domain-design choices for the plan**, not unknowns: the transient-record ↔ persisted-entity naming collision, how negotiation points link to clauses (the LLM correlates them only by a `clauseText` string), the `Analysis.status` lifecycle for a synchronous flow, the input char-limit, and MEDIUM/LOW risk-badge colors (only `destructive`/red is a chromatic theme token today).

## Detailed Findings

### 1. Spring AI 2.0.0 structured output — the exact API (differs from the 1.1.x reference)

The reference `docs/clause-classification.md` §4 shows the **1.1.x** form (`new BeanOutputConverter<>(...)` + `.call().content()` + `converter.convert(raw)`). This repo resolves **Spring AI 2.0.0** (`backend/pom.xml:31` `spring-ai.version=2.0.0`, BOM at `:140-145`), whose module split and API differ. Verified by resolving the actual jars and reading bytecode:

- **Use `.call().entity(Class)`** — it exists on `ChatClient$CallResponseSpec` in 2.0.0, and its `DefaultCallResponseSpec.entity(Class)` implementation internally does `new BeanOutputConverter<>(clazz)`, injects `getFormat()` (the JSON-schema instruction) into the request via the advisor chain, calls the model, then runs `BeanOutputConverter.convert(...)`. **We do not inject `{format}` into the user prompt ourselves** — the reference's explicit converter is obsolete for 2.0.0.
- **`BeanOutputConverter` still exists** at `org.springframework.ai.converter.BeanOutputConverter` (methods `getFormat()`, `convert(String)`, `getJsonSchema()`), kept only as a manual fallback. Prefer `.entity(Class)`.
- **`response-format.type=json_object`** (`application.properties:7`) is complementary, not conflicting: `.entity()`'s default path augments the *prompt* (client-side), while `json_object` is sent to OpenRouter to force syntactically-valid JSON. The injected schema text contains the word "JSON", satisfying OpenAI's rule that `json_object` requires "JSON" in the prompt. **Keep both.** Do **not** enable `entity(Class, spec -> spec.useProviderStructuredOutput())` (provider-native `json_schema`) unless OpenRouter+gpt-4o is confirmed to pass it through — the default client-side path is the safe choice.
- **Bean wiring**: `spring-ai-starter-model-openai` autoconfigures an `OpenAiChatModel` (implements `ChatModel`) via `OpenAiChatAutoConfiguration`, and a **prototype, `@ConditionalOnMissingBean`** `ChatClient.Builder` via `ChatClientAutoConfiguration`. The service injects `ChatClient.Builder` and calls `.build()`.

**Recommended service shape** (package TBD — see Open Questions):

```java
@Service
public class ContractAnalysisService {
    private final ChatClient chatClient;
    public ContractAnalysisService(ChatClient.Builder builder) { this.chatClient = builder.build(); }

    public ClauseAnalysisResult analyze(String contractText) {
        return chatClient.prompt()
            .system(SYSTEM_PROMPT)                 // docs/clause-classification.md §1
            .user(u -> u.text(USER_PROMPT).param("contractText", contractText))  // NO {format}
            .call()
            .entity(ClauseAnalysisResult.class);   // real BeanOutputConverter runs here
    }
}
```

Exact 2.0.0 package map (verified): `org.springframework.ai.chat.client.ChatClient` (+ `$Builder`, `$CallResponseSpec`), `org.springframework.ai.chat.model.{ChatModel,ChatResponse,Generation}`, `org.springframework.ai.openai.OpenAiChatModel`, `org.springframework.ai.chat.messages.AssistantMessage`, `org.springframework.ai.chat.prompt.{Prompt,ChatOptions}`, `org.springframework.ai.converter.BeanOutputConverter`. Note 2.0.0 uses **Jackson 3** internally (`tools.jackson.*`).

### 2. The deterministic ChatClient mocking recipe (the load-bearing e2e test)

**Mock the `ChatModel` bean, not the `ChatClient`.** This is the only approach that keeps the *real* `ChatClient.Builder`, the *real* `BeanOutputConverter`, and our service's prompt-building + `.entity()` mapping in the test path — so the test actually covers the code that matters. Mocking the fluent `ChatClient` chain or wrapping it behind our own interface both mock away the code under test — rejected.

**Recommended variant — a `@TestConfiguration` lambda (gotcha-free):**

```java
@TestConfiguration(proxyBeanMethods = false)
static class MockChatModelConfig {
    @Bean @Primary
    ChatModel mockChatModel() {
        var response = new ChatResponse(List.of(new Generation(new AssistantMessage(FIXED_JSON))));
        return prompt -> response;   // ChatModel is effectively functional: ChatResponse call(Prompt)
    }
}
```

Import with `@Import({TestcontainersConfiguration.class, MockChatModelConfig.class})`. `@Primary` wins over the real `openAiChatModel` (which still constructs fine because the test profile supplies dummy `api-key=test` at `src/test/resources/application.properties:3`; it is never called). Because it is a real lambda implementation (not a Mockito proxy), the `default ChatModel.getOptions()` body runs and returns non-null — avoiding the NPE trap below.

**Alternative — `@MockitoBean ChatModel` (idiomatic, but one required extra stub):** `@MockitoBean` is available (`org.springframework.test.context.bean.override.mockito.MockitoBean` in `spring-test-7.0.8`; `mockito-core:5.20.0` on the test classpath). **Gotcha (verified in bytecode):** `DefaultChatClientUtils.toChatClientRequest(...)` calls `chatModel.getOptions().mutate()` with **no null-check**; a Mockito mock does not run the `default getOptions()` body, so it returns `null` → NPE *before* the fixed JSON is used. With `@MockitoBean` you MUST also stub `when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build())` alongside `when(chatModel.call(any(Prompt.class))).thenReturn(response)`.

Constructors (all public, verified): `new AssistantMessage(String)`, `new Generation(AssistantMessage)`, `new ChatResponse(List<Generation>)`. `FIXED_JSON` is the schema-shaped string from `docs/clause-classification.md` §6 (auto-renewal clause → HIGH + linked negotiation point).

This mock plugs directly into the existing `@SpringBootTest @Import(TestcontainersConfiguration.class)` pattern (`backend/src/test/java/.../FalconApplicationTests.java`).

### 3. Domain model to persist + the transient-vs-persisted split

Two parallel object families — keep them distinct:

**Transient LLM records** (`docs/clause-classification.md` §3; already specified) — the structured-output target, never persisted directly:
- `ClauseAnalysisResult(List<AnalyzedClause> clauses, List<NegotiationPoint> negotiationPoints)`
- `AnalyzedClause(String text, RiskLevel riskLevel, RiskType riskType, String rationale)`
- `NegotiationPoint(String clauseText, String recommendation, RiskLevel priority)` ⚠️ **name collides with the entity below**
- `enum RiskLevel { LOW, MEDIUM, HIGH }`, `enum RiskType { PENALTY, AUTO_RENEWAL, NO_TERMINATION, UNILATERAL_CHANGE, LIABILITY, CONFIDENTIALITY, IP_RIGHTS, PAYMENT, OTHER }`

**Persisted JPA entities** (PRD §5 / `docs/PRD.md:83-91` / CLAUDE.md) — owner-scoped, mirror the `User` entity style (`backend/src/main/java/.../user/User.java`: `@Entity @Table`, `@Id @GeneratedValue(IDENTITY) Long id`, `@Column`, `Instant createdAt`, `protected` no-arg ctor + all-args ctor):
- `Analysis` — `id`, `ownerId` (Long → `User.id`), `title`, `rawText`, `status` (`DRAFT`→`ANALYZED`→`REVIEWED`), `createdAt`
- `Clause` — `id`, `analysisId`, `text`, `riskLevel`, `riskType`, `rationale`, `userDecision` (`PENDING`/`ACCEPTED`/`TO_NEGOTIATE`/`REJECTED`, default `PENDING`)
- `NegotiationPoint` — `id`, `analysisId`, `clauseId`, `recommendation`, `priority` (RiskLevel)
- enums: `RiskLevel`, `RiskType` (shared with the records), `AnalysisStatus`, `ClauseDecision`

**Mapping AnalyzedClause → Clause** is direct + defaults (`userDecision=PENDING`, `analysisId` set on save). **Mapping the LLM's NegotiationPoint → the entity's `clauseId` is the one real wrinkle**: the LLM correlates a negotiation point to its clause only by a `clauseText` *string echo*, not an id. The plan must decide how to resolve the FK (e.g., match `clauseText` to the persisted `Clause.text` by exact/prefix match; fall back to analysis-level linkage if no match). See Open Questions.

**Migration**: add `backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml` (tables `analyses`, `clauses`, `negotiation_points` with FKs) and wire it into `db.changelog-master.yaml` via a second `- include:` after the existing `001` entry. Follow the `001-create-users.yaml` changeset shape exactly (`changeSet` id/author, `createTable`, `bigint autoIncrement` PK, `varchar`/`text`/`timestamptz` columns, named constraints). `ddl-auto=validate` (`application.properties`) means entities MUST match the migration or startup fails — a feature, not a bug.

### 4. F-01 reuse surface — what S-01 inherits and must extend

Grounded in the F-01 files implemented this session (all committed, all tests green):

**Inherit directly:**
- **Owner-scoping primitive** — `SecurityUtils.currentUserId()` (`backend/src/main/java/.../auth/SecurityUtils.java:18-23`): `static Long`, throws `IllegalStateException` if unauthenticated. Every owned-data query takes an `ownerId`; there is no bare `findById` for owned entities (the documented convention, `SecurityUtils.java:7-12`). `AnalysisRepository.findByIdAndOwnerId(id, SecurityUtils.currentUserId())` → `Optional`, return 404 when empty (don't leak existence).
- **Auto-gating** — `SecurityConfig.java:38-42` ends with `.anyRequest().authenticated()`, so new `/api/analyses/**` endpoints require a session with **no config change**. CSRF `spa()` (`:36`) means POSTs need the `X-XSRF-TOKEN` echo — the frontend `apiFetch` already does this for non-GET.
- **Controller conventions** — mirror `AuthController`: `@RestController`, `@AuthenticationPrincipal AppUserDetails principal` (or `SecurityUtils.currentUserId()` in the service), record DTOs, `ResponseStatusException` for status codes, and the `@RestControllerAdvice AuthExceptionHandler` pattern for mapping domain exceptions (extend it, or add an analysis-specific advice, for a controlled "analysis failed" → 422/502).
- **Frontend data layer** — `frontend/src/lib/api.ts` (`apiFetch` + `ApiError`; injects `credentials:'include'` + XSRF header on mutations) and the `frontend/src/lib/auth.ts` thin-client shape. Add `frontend/src/lib/analyses.ts` following `auth.ts`.
- **Test infrastructure** — `@SpringBootTest @Import(TestcontainersConfiguration.class)` (NOT `@DataJpaTest` — no embedded DB on classpath; it fails at startup); `@AfterEach` `deleteAll()` cleanup (`@SpringBootTest` isn't rolled back); MockMvc via `@AutoConfigureMockMvc` from **`org.springframework.boot.webmvc.test.autoconfigure`** (Boot 4 package, not the old one); `.with(csrf())` and `.with(user(appUserDetails))` from `SecurityMockMvcRequestPostProcessors`; **`new ObjectMapper()`** in tests (no injectable bean — the split `spring-boot-starter-webmvc` doesn't pull `spring-boot-starter-json`).

**Must EXTEND (not just reuse):**
- **CORS methods** — `SecurityConfig.java:75` allows only `GET`, `POST`. S-01's create (POST) + read (GET) are covered, but **S-02 (per-clause status, likely PATCH/PUT) and S-04 (DELETE) will need this list extended.** Note it for the plan; S-01 itself needs no change.
- **Frontend route gate** — `frontend/src/proxy.ts` matcher is `['/dashboard/:path*']`. New analysis routes are NOT gated until added: `matcher: ['/dashboard/:path*', '/analyses/:path*']`.
- **New shadcn components** (see §5) and the **new Liquibase migration** (§3).

### 5. Frontend paste → progress → result flow

Current state: Next.js 16.2.10 / React 19 / Tailwind 4 CSS-first; shadcn `radix-nova`, lucide icons; only `button/input/label/card` exist. `frontend/src/app/page.tsx` is still the untouched Next starter (repurpose or ignore); `(auth)/login`, `(auth)/register`, `dashboard` follow a consistent client-component pattern.

**Add components:** `cd frontend && pnpm dlx shadcn@latest add textarea badge alert separator skeleton` (default-registry slugs; `radix-nova` only changes generated markup). Spinner = `lucide-react`'s `Loader2` + `animate-spin` (already installed; `tw-animate-css` imported in `globals.css`) — no extra dep.

**Proposed routes** (split the transient action from the durable owner-scoped result):
```
frontend/src/app/
  analyses/new/page.tsx     # "use client" — title <Input> + contract <Textarea> + submit; POST /api/analyses → router.push(`/analyses/${id}`)
  analyses/[id]/page.tsx     # "use client" — fetch-on-mount (mirror dashboard's me() pattern) → clauses + negotiation points + disclaimer
  dashboard/page.tsx         # keep as landing; add a "Nowa analiza" link (becomes the S-03 history list later)
```
This is what S-01's "saved" requirement and the e2e test need, and what S-02 (status on the saved view) and S-03 (history → `/analyses/[id]`) build on. Making `dashboard` the result screen would force a rework at S-03.

**Progress feedback (~15s p95 NFR):** keep F-01's plain `async handleSubmit` + `submitting` boolean (the cross-origin `apiFetch` wrapper doesn't fit `useActionState`/server actions). While submitting: disable the form, swap the button to `<Loader2 className="animate-spin" /> Analizuję umowę…`, and show an **indeterminate** animated bar + reassurance copy ("To zwykle trwa kilkanaście sekund. Nie zamykaj tej strony."). Do not fake a determinate percentage; no streaming/websockets (out of scope). Keep the form disabled the entire time to prevent double-submit.

**Disclaimer (guardrail):** persistent `Alert` at the top of every `/analyses/[id]` result — title "To nie jest porada prawna", body explaining supporting-analysis-not-legal-advice. Also a short reminder near the submit button.

**Empty/unparseable states (never a blank screen):** (a) client-side empty guard before submit → inline `text-destructive` error ("Wklej treść umowy, aby rozpocząć analizę."); (b) backend controlled error / zero-clause result → `Alert` (destructive for errors) with a Polish explanation, and a length-limit message when the char cap is hit.

**Risk-badge colors:** the `neutral` base theme makes `destructive` (red) the **only chromatic token** — HIGH→`destructive` maps cleanly (there's even a `destructive` button variant to visually mirror). MEDIUM (amber) / LOW (green) have **no token** — decide between (a) adding `--risk-*` OKLCH tokens to `globals.css` (both `:root` and `.dark`) or (b) Tailwind's built-in `amber-*`/`emerald-*` utilities. Also render a Polish label map for the English `riskType` enum (e.g. `AUTO_RENEWAL → "Automatyczne przedłużenie"`; the §1 system prompt supplies each gloss).

### 6. Validation, error handling & edge cases

- **Out-of-enum / malformed JSON**: `.entity()` cannot return an out-of-enum record — Jackson 3 throws inside `BeanOutputConverter.convert()`, rethrown as **`IllegalStateException`**. So the reference's "validate enums after conversion" advice is moot; instead **catch `IllegalStateException` around `service.analyze()`** and map to a controlled domain error (e.g. `AnalysisFailedException` → 422/502 with a readable message). Empty model reply → `.entity()` returns `null` — guard before persisting.
- **Length limit**: enforce a max-length check on `rawText` *before* the LLM call (MVP: a simple char cap + readable Polish message; chunking is above-MVP). Plan decides the cap.
- **Determinism**: `temperature=0.2` + `json_object` stay in `application.properties`; the mocked `ChatModel` bypasses them in tests. Keep dummy `api-key=test` in the test profile so `openAiChatModel` still instantiates.

## Code References

- `backend/pom.xml:31,59-61,140-145` — `spring-ai.version=2.0.0`, `spring-ai-starter-model-openai`, BOM import
- `backend/src/main/resources/application.properties:3-7` — OpenRouter base-url/key, `model=openai/gpt-4o`, `temperature=0.2`, `response-format.type=json_object`; `ddl-auto=validate`, `open-in-view=false`
- `backend/src/test/resources/application.properties:3` — dummy `api-key=test`
- `docs/clause-classification.md` §1-2 (prompts), §3 (records), §4 (1.1.x call — replace with `.entity()`), §6 (fixed-JSON mock), §7 (validation/length advice)
- `docs/PRD.md:83-91` — the persisted data model (Analysis/Clause/NegotiationPoint fields + status enums); §8 (line ~124) — the required e2e test
- `backend/src/main/java/com/morawski/dev/falcon/user/User.java:12-53` — JPA entity template to mirror
- `backend/src/main/resources/db/changelog/changes/001-create-users.yaml` + `db/changelog/db.changelog-master.yaml` — migration template + `include` wiring
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityUtils.java:18-23` — `currentUserId()` (throws `IllegalStateException`)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:38-42` (`.anyRequest().authenticated()`), `:36` (`csrf.spa()`), `:75` (CORS methods `GET`,`POST` — extend for S-02/S-04)
- `backend/src/main/java/com/morawski/dev/falcon/auth/AuthController.java`, `AuthExceptionHandler.java` — controller + error-advice conventions
- `backend/src/test/java/com/morawski/dev/falcon/{FalconApplicationTests,TestcontainersConfiguration,UserRepositoryTest}.java`, `auth/{AuthControllerTest,AuthFlowTest}.java` — test patterns + gotchas
- `frontend/src/lib/{api,auth}.ts` — data-layer to mirror (add `analyses.ts`)
- `frontend/src/proxy.ts` — matcher to extend with `/analyses/:path*`
- `frontend/src/app/{(auth)/login,(auth)/register,dashboard}/page.tsx` — client-component patterns to mirror
- `frontend/src/app/globals.css` — theme tokens (`destructive` = only chromatic); `frontend/components.json` — shadcn config

## Architecture Insights

- **Structured output is a client-side prompt-augmentation in 2.0.0's default path**, not a provider feature — which is exactly why the deterministic mock works: replacing the `ChatModel` with a lambda returning a fixed JSON string exercises the *entire* real conversion pipeline (schema injection is inert on a stubbed model, but `BeanOutputConverter.convert()` runs for real). The test proves our mapping, not a mock's.
- **The isolation invariant is now a reusable contract, not a per-feature concern** — F-01 shipped `SecurityUtils.currentUserId()` precisely so S-01 can enforce ownership at the query layer with a one-liner. S-01 is where the *cross-user data* isolation test (deferred from F-01) finally lands: user B must get 404 on user A's analysis.
- **Two `NegotiationPoint` types is a deliberate transient-vs-persisted split**, echoing F-01's transient-DTO / persisted-entity separation. The plan should resolve the collision by package (e.g. an `llm`/`ai` sub-package for the records) rather than renaming domain concepts.
- **`ddl-auto=validate` makes the migration and the entity a matched pair** — the same fail-fast contract F-01 relied on; a schema/entity drift stops startup.

## Historical Context (from prior changes)

- `context/changes/identity-and-isolation/plan.md` — F-01's plan; its "Critical Implementation Details" and the owner-filter convention (`findByIdAndOwnerId`, `SecurityUtils.currentUserId()`) are the direct antecedents of S-01's persistence design. Its "What We're NOT Doing" explicitly deferred the `Analysis` entity and the cross-user data isolation test **to S-01**.
- `context/changes/identity-and-isolation/reviews/plan-review.md` — established the "verify the framework against the resolved version, not the docs" discipline (Spring Security 7.0.6), reapplied here for Spring AI 2.0.0.
- `context/changes/identity-and-isolation/reviews/impl-review.md` — F-01's impl review; the `@SpringBootTest`-not-`@DataJpaTest`, `AutoConfigureMockMvc` package, and `new ObjectMapper()` gotchas it surfaced all apply to S-01's tests.

## Related Research

None — this is the first `research.md` in `context/changes/`. (No `context/foundation/lessons.md` exists yet, so there are no accepted-rule priors to fold in.)

## Open Questions

These are **design decisions for `/10x-plan`**, not blocking unknowns:

1. **Package layout & the `NegotiationPoint` collision** — one `analysis` package with an `llm`/`ai` sub-package for the transient records (`ClauseAnalysisResult`, `AnalyzedClause`, `NegotiationPoint` record) vs the entity `NegotiationPoint`? Recommended: sub-package split, no rename.
2. **Negotiation-point → clause FK resolution** — the LLM links a negotiation point to its clause only by a `clauseText` string. Match to the persisted `Clause.text` (exact/prefix) to set `clauseId`, with a fallback for no-match? Or restructure the prompt/schema to return an index? (Recommended: match on text at persist time; tolerate no-match by leaving `clauseId` null / analysis-level.)
3. **`Analysis.status` for a synchronous flow** — persist directly as `ANALYZED` (no `DRAFT` intermediate, since S-01 analyzes on submit), leaving `DRAFT`/`REVIEWED` transitions for S-02? (Recommended: yes.)
4. **Input char-limit value** — what max length triggers the "too long" message before the LLM call?
5. **MEDIUM/LOW risk-badge colors** — add `--risk-*` OKLCH theme tokens, or use Tailwind's default `amber-*`/`emerald-*` utilities?
6. **REST shape** — `POST /api/analyses` (create+analyze, returns the saved analysis) and `GET /api/analyses/{id}` (owner-scoped) is the natural contract; confirm and record it (the `docs/reference/contract-surfaces.md` registry doesn't exist yet).
7. **e2e test scope** — should S-01's load-bearing test also assert cross-user isolation (user B → 404 on user A's analysis) here, closing F-01's deferred gap? (Recommended: yes — it's the natural home.)
