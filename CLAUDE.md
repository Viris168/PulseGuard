# CLAUDE.md

This file gives Claude Code the context and rules for working in this repository. Read it before making changes.

## Project

**PulseGuard** is an API monitoring and incident SaaS. Users register API endpoints; the app polls them on a schedule, opens incidents when they fail, sends alerts, and charges subscriptions through Stripe.

Reference docs (read when relevant, do not duplicate their content here):
- `docs/prd.md`: product requirements, features, pricing tiers
- `docs/architecture.md`: full architecture, SQL schema, flows, API list
- `docs/architecture-essentials.md`: one-page summary

## Stack

- Java 21, Spring Boot 3, Maven
- PostgreSQL with Flyway migrations
- Quartz scheduler (JDBC job store, clustered)
- Spring WebClient for HTTP checks
- Spring Security + BCrypt + JWT
- Spring Mail (Resend/SendGrid SMTP) for email alerts
- Stripe Java SDK (Checkout, Customer Portal, Webhooks)
- Redis (via spring-boot-starter-data-redis) — used intentionally for learning purposes, even though the MVP doesn't strictly require it. Current uses: (1) caching dashboard/stats reads with @Cacheable, (2) a Redis-backed job queue for dispatching checks from Quartz to a worker. Keep Redis usage isolated behind a small abstraction (e.g. a CheckQueue interface) so it can be removed or swapped without touching business logic.
- JUnit 5, Mockito, Testcontainers (PostgreSQL)
- Docker + docker-compose for local development

## Commands

```bash
# Start local PostgreSQL (and Redis if enabled)
docker compose up -d

# Run the app (dev profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=IncidentEngineTest

# Build jar
./mvnw clean package

# Build Docker image
docker build -t pulseguard .
```

Always run `./mvnw test` after making changes and make sure it passes before saying a task is done.

## Project Structure

Package by feature for services/controllers; entities, enums and repositories are centralized:

```
src/main/java/com/viris/PulseGuard/
├── model/         # all JPA entities (User, Monitor, Check, Incident, ...)
├── enumeration/   # all enums (Plan, MonitorState, CheckResult, IncidentStatus, ChannelType, ...)
├── repository/    # all Spring Data repositories, one per entity
├── auth/          # SecurityConfig, JWT, AuthController
├── monitor/       # monitor service, controller
├── scheduling/    # Quartz config, CheckJob, SchedulerService
├── check/         # CheckExecutor (WebClient)
├── incident/      # IncidentEngine (state machine), events
├── notification/  # NotificationService, channels/ (Email, Slack, Telegram)
├── billing/       # StripeService, StripeWebhookController, PlanLimits
├── stats/         # rollup and retention jobs, StatsController
├── statuspage/    # public status page
└── common/        # exceptions, GlobalExceptionHandler, config properties

src/main/resources/
├── application.yml
├── application-dev.yml
├── application-prod.yml
└── db/migration/  # Flyway: V1__init.sql, V2__..., etc.
```

Entities live in `model/`, enums in `enumeration/`, and repositories in `repository/` (shared across features). Each feature package contains its own `Service`, `Controller`, and `dto/` as needed.

## Coding Conventions

- Use constructor injection (with `final` fields). Never use field `@Autowired`.
- Controllers stay thin: validate input, call a service, return a DTO. Business logic lives in services.
- Never return JPA entities from controllers. Use DTOs (Java `record`s preferred).
- Validate request DTOs with Jakarta Validation (`@NotBlank`, `@Min`, etc.) and `@Valid`.
- Put `@Transactional` on service methods, not controllers.
- Throw custom exceptions (e.g. `MonitorNotFoundException`, `PlanLimitExceededException`) and map them in `GlobalExceptionHandler` to consistent JSON errors.
- Use enums for states and types (`MonitorState`, `CheckResult`, `IncidentStatus`, `ChannelType`, `Plan`). Store them as `@Enumerated(EnumType.STRING)`.
- Use `Instant` / `TIMESTAMPTZ` for all timestamps, in UTC.
- Configuration values go in `application.yml` bound with `@ConfigurationProperties`, not hardcoded.
- Use SLF4J logging. Include `monitorId` / `incidentId` in log messages for checks and incidents.

## Database Rules

- **Never modify an existing Flyway migration.** Always add a new `V{n}__description.sql` file.
- Set `spring.jpa.hibernate.ddl-auto=validate`. Flyway owns the schema, not Hibernate.
- Every query for user data must be scoped by `user_id` (tenant isolation). Never fetch a monitor or incident by ID alone in a user-facing path.
- Keep the index on `checks (monitor_id, checked_at DESC)`.
- Keep the partial unique index guaranteeing one open incident per monitor.

## Critical Domain Rules (Do Not Break)

1. **Timeouts on every check.** WebClient calls must have connect and response timeouts from the monitor's `timeout_ms`. One slow endpoint must never block others.
2. **Incident state machine** lives only in `IncidentEngine`:
   - `UP → SUSPICIOUS` on a failed check
   - `SUSPICIOUS → DOWN` after 3 consecutive failures (configurable) → open incident
   - `DOWN → RECOVERING` on a passing check
   - `RECOVERING → UP` after 2 consecutive passes (configurable) → resolve incident
   - Never open an incident from a single failed check.
3. **Concurrency safety.** Monitor state updates use optimistic locking (`@Version`). Incident creation must be idempotent.
4. **Alerts after commit.** Notifications use `@TransactionalEventListener(phase = AFTER_COMMIT)` and run `@Async`. Log every sent alert in `notifications` with a unique `(incident_id, channel_id, event_type)` to prevent duplicates.
5. **Plan limits enforced server-side** in services via `PlanLimits` (monitor count, minimum interval, channels, retention). Never rely on the frontend.
6. **SSRF protection.** Before saving a monitor and before each check, resolve the host and reject private/internal addresses: loopback, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, IPv6 local ranges. Check after DNS resolution, not just the URL string.
7. **Stripe webhooks** must verify the signature and deduplicate by `stripe_event_id` (stored in `stripe_events`) before processing.

## Security and Secrets

- Never commit secrets. All keys (DB, JWT secret, Stripe keys, SMTP key) come from environment variables.
- Never log passwords, tokens, API keys, or full webhook URLs.
- `/api/stripe/webhook` and `/status/**` are the only unauthenticated endpoints besides auth routes and `/actuator/health`.

## Testing

- Unit test all business logic, especially `IncidentEngine` state transitions (every transition, plus thresholds and edge cases).
- Use Testcontainers PostgreSQL for repository and integration tests. Do not use H2.
- Mock external services (Stripe, SMTP, target URLs). Use `MockWebServer` (OkHttp) to simulate target APIs: success, 500s, timeouts, slow responses.
- Test SSRF validation with private IPs and hostnames that resolve to them.
- Test names describe behavior, e.g. `opensIncidentAfterThreeConsecutiveFailures()`.

## Workflow for Claude

- Before a non-trivial change, briefly state the plan and which files will change.
- Make small, focused changes. Do not refactor unrelated code.
- Follow the build order in `docs/architecture-essentials.md`; don't jump ahead to later features unless asked.
- When adding a feature, add or update its tests in the same change.
- If a requirement is unclear or conflicts with these rules, ask instead of guessing.
- Do not add new dependencies without explaining why.
- Redis is included intentionally (see Stack above) for learning — do not remove it. Do not introduce RabbitMQ, Kafka, or a split into microservices unless explicitly asked. The MVP stays a modular monolith with Redis used for caching and/or the check job queue.
