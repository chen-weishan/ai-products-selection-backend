# AGENTS.md

## Quick start

```bash
cp .env.example .env          # fill SSDS_DB_PASSWORD
./gradlew :ssds-api:bootRun   # starts on localhost:8080, profile dev
```

Tests require Docker Desktop (Testcontainers). See below for single-module runs.

## Build & test commands

```bash
./gradlew build                          # compile + test all modules
./gradlew :ssds-infra:test               # migration verification (needs Docker)
./gradlew :ssds-api:test                 # API layer tests
./gradlew :ssds-core:test                # domain tests (no DB)
```

No lint, typecheck, or formatter is configured beyond the Java compiler. There is no Makefile, no npm, no scripts directory.

## Module structure

All modules live under `com.example.ssds.*`. Dependency direction is strict and one-way:

```
ssds-api → ssds-infra, ssds-ai, ssds-ingest, ssds-calibration, ssds-core
ssds-infra → ssds-core (api configuration — exposes core types transitively)
ssds-ai → ssds-core
ssds-ingest → ssds-core
ssds-calibration → ssds-core
ssds-core → (nothing — pure domain, no JPA)
```

- `ssds-api` — Controllers, DTOs, Security config, Spring Boot entry point. Produces `ssds.jar`.
- `ssds-core` — Domain enums only (30+). No service classes yet. No JPA dependency.
- `ssds-infra` — JPA entities, repositories, DAOs, Flyway migrations. 20 prod + 8 dev seed migrations.
- `ssds-ai`, `ssds-ingest`, `ssds-calibration` — Empty (build.gradle only, no source code yet).

## Key conventions (read CONTEXT.md §5 before writing Controllers)

| Rule | Detail |
|------|--------|
| **Context path** | `/api/v1` is set globally. Controllers write only resource paths (`/products`). |
| **Date types** | Use `OffsetDateTime` (with time) or `LocalDate` (date-only). Never `Instant` or `LocalDateTime`. |
| **Pagination** | Controllers accept `Pageable`, return `ApiResponse.success(PageResponse.from(page))`. |
| **Error handling** | Throw `BusinessException(ErrorCode.XXX)`. Only one `@RestControllerAdvice` exists (`GlobalExceptionHandler`). |
| **Error classes** | Use `ErrorCode` + `BusinessException`. The legacy `ApiException`/`ApiErrorCode`/`ApiErrorResponse` trio was removed — do not recreate it. |
| **Response wrapper** | Always `ApiResponse<T>`. Never return raw domain objects. |
| **Package layout** | `com.example.ssds.{module}` — no split packages across modules. |

## Database

- **Engine**: Supabase PostgreSQL (project `aozddonvsfdrtwpuxnqi`).
- **Role**: Daily dev uses restricted `ssds_app` role — can read/write but not DDL.
- **Shared-DB drift**: the dev DB accumulates rows written by running apps (e.g. `scene_classification_log` from scoring runs), which can supersede `V900+` seed data. When seed-driven behavior "doesn't show", query the actual table before assuming a code bug.
- **Variables**: `.env` uses `SSDS_*` prefix (old `SUPABASE_*` names are dead).
- **Flyway**: Disabled by default. Only one person applies migrations to the shared DB.
- **Migration ports**: App connects via port 6543 (transaction pooler). Flyway uses port 5432 (session mode, needed for advisory locks).
- **ENUMs**: Stored as `VARCHAR + CHECK`, not PostgreSQL native enums.
- **Schema rule**: If spec §7.2 disagrees with the database, follow the spec and write a migration.

## Flyway migration rules

- Never modify, delete, or rename an applied migration — not even whitespace. Flyway uses CRC32 checksums.
- `V1`–`V899` = production schema (`db/migration`). `V900`–`V999` = dev seeds (`db/dev`, loaded only by dev profile).
- New migration: `git fetch origin`, check `origin/dev` for used version numbers, pick a higher number.
- Verify locally: `./gradlew :ssds-infra:test` (runs 6 Testcontainers cases from V1 to latest).
- New tables must include `ALTER TABLE ... ENABLE ROW LEVEL SECURITY;` in the same file.
- PR first, apply to shared DB only after merge to `dev` by the designated person.

## Spring Boot 4 / Gradle quirks

- Spring Boot **4.1.0**, Java **21**, Gradle **9.5.1**.
- Starter names differ from Boot 3: `spring-boot-starter-webmvc` (not `-web`), `-aspectj` (not `-aop`), Flyway has its own starter (`spring-boot-starter-flyway`).
- Jackson 3 is default in Boot 4. Do not add `spring.jackson.serialization.write-dates-as-timestamps=false` — it will crash on startup.
- `bootRun` sets `workingDir = rootProject.projectDir` so `.env` is found. Tests do the same.
- Lombok is configured globally (compile-only + annotation processor in all subprojects).

## Tests

- JUnit 5 (Jupiter) via JUnit Platform. Test JVM timezone forced to UTC.
- `MigrationVerificationTest` in `ssds-infra` runs 6 Testcontainers cases against `postgres:17.6-alpine`. Requires Docker.
- Dev profile provides seed data (V900–V907) and HTTP Basic auth (`dev-buyer` / `dev-buyer-password`).
- `spring.flyway.ignore-migration-patterns=*:missing,*:future` in dev — tolerates missing migrations on a shared DB.

## Domain rules from the spec (開發規格書 v3.0)

The spec (`開發規格書_v3.0.md`, in Traditional Chinese) is the source of truth for feature behavior; when it disagrees with code or DB, follow it and write a migration. UI mockups: `畫面功能示意圖_v3.0.html`.

- **product_score granularity**: one row per (product_id, period, scene_type) — the same product legitimately appears on multiple boards per period. `is_primary` marks the main scene; `is_active = true` marks the latest valid snapshot per key. **Every ranking/KPI query must filter `is_active = true`; all dashboard item counts dedup by `product_id`.**
- **B-track products are never scored** (AC-16-2): `product_score` holds A-track rows only.
- **period format**: ISO week determined in Asia/Taipei, `char(7)` like `2026W30`, enforced by DB CHECK `ck_score_period_format`. Compute with `WeekFields.ISO` + `ZoneId.of("Asia/Taipei")` — never `WeekFields.of(Locale.TAIWAN)` (Sunday-first) or system-default zone.
- **Penalty sign convention**: `penalty_subtotal` is stored positive 0–40; `final_score = max(0, bonus_subtotal − penalty_subtotal)`; minus signs are UI-only.
- **Hard rule**: penalty ≥ 20 ⇒ grade capped at B and a mandatory risk alert.
- **API contract**: §8.2 is the complete endpoint list. Dashboard = 5 endpoints (`summary` / `rankings` / `sourcing-summary` / `todos` / `heat-sources`). `GET /dashboard/top-products` is abolished — there is no single overall board, only the four scene boards (VIRAL/FESTIVAL/REPLENISHMENT/SEASONAL).
- **Risk alerts**: types and default severities are fixed by FR-10-1; "高風險" KPI counts OPEN alerts with severity HIGH.
- **Manual scene override**: recorded in `scene_classification_log` (`overridden_by` non-null); overridden scene becomes primary, old primary demoted not deleted.

## Date serialization

- DTOs expose `OffsetDateTime` (or `LocalDate`); entities/projections may keep `Instant` internally.
- Convert at the service→DTO boundary with `instant.atZone(ZoneId.of("Asia/Taipei")).toOffsetDateTime()` so JSON shows `+08:00` per spec §8.1. See `ProductQueryService.toDisplayTime`.
- `spring.jackson.time-zone` does NOT affect `Instant` — changing DTO types is the only fix.

## Reference files

- `開發規格書_v3.0.md` — full dev spec: FR-01–FR-18 (§4), scoring algorithm (§5), schema (§7.2), complete API contract (§8.2). Section refs in code comments point here.
- `畫面功能示意圖_v3.0.html` — screen mockups (S-01…), each tagged with its FR number; shows required columns/buttons per screen.
- `CONTEXT.md` — naming decisions, API conventions, database alignment rules, dev environment notes.
- `ssds-infra/src/main/resources/db/migration/README.md` — migration versioning and workflow rules.
- `.env.example` — all required environment variables with explanations.
