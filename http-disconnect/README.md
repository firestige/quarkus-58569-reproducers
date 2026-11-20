# Real HTTP disconnect during an ordinary PostgreSQL row-lock wait

Standalone Quarkus / Hibernate Reactive reproducer for [Quarkus #58569](https://github.com/quarkusio/quarkus/issues/58569).
Two minimal entities, one transactional service, one POST endpoint, and one integration test.
No application repository dependency, triggers, advisory locks, manual reactive cancellation,
or custom request transaction manager.

## Run

Requires JDK 25, Docker with Compose v2 (`--wait`), and access to Maven Central.
The included Apache Maven wrapper selects Maven 3.9.11.

```bash
./reproduce.sh
```

If loopback port 19082 is occupied:

```bash
REPRO_PG_PORT=19083 ./reproduce.sh
```

The script starts disposable PostgreSQL, runs the real HTTP test, saves logs under `evidence/`,
and removes its Compose container, network and volumes even when the assertion fails.
Quarkus chooses a dynamic HTTP test port. Use a distinct PG port and checkout for each concurrent run.
The script generates a Compose project name per run. `REPRO_COMPOSE_PROJECT` can override it.

**Expected reproduction outcome: Maven exit 1**, with `HTTP cancellation violated atomicity/cleanup`
after one `HTTP_CONTROL` and three `HTTP_CANCEL` lines. Startup errors, timeouts, missing lock waits,
or no observed REST cancellation do not count as reproducing the reported bug.
The test asserts correct atomicity; it does not treat partial writes as correct behavior.

Only disposable credentials (`repro` / `repro-only`) and generated data are used. PG and HTTP
bind to loopback. Hibernate uses `drop-and-create`; use this project's disposable database.

## Scenario

Independent transaction A holds a normal row lock by updating Counter without changing its value.
HTTP request B calls an injected `@Transactional` service that first updates that same Counter,
then inserts a Marker. The test waits for PostgreSQL to confirm B is blocked by A, closes the
actual client TCP socket with a reset, observes REST cancellation, then commits A to release
its lock. It never calls `subscribe()` or `cancel()` on the business Uni.

A connected control commits both writes and returns HTTP 200. All three disconnected requests
instead leave Counter=0 but Marker count=1: the UPDATE rolls back, then the INSERT executes
outside the transaction. `VERIFICATION.md` contains actual results and server SQL excerpts.

The endpoint's cancellation counter is passive instrumentation. All blocking waits and admin
queries run on the test thread; the endpoint/service execute normally through Quarkus REST.
The lock-holder's transaction is test setup, not a transaction wrapper around the HTTP request.
The 500 ms settling interval is a bounded observation, not a long-term pool-leak test.

## Versions

Unmodified Quarkus **4.0.0.CR1** BOM / matching plugin:

| Component | Version |
| --- | --- |
| Hibernate Reactive | 5.1.0.Beta1 |
| Hibernate ORM | 8.0.0.Beta4 |
| Vert.x core / PG client | 5.2.3 |
| Mutiny | 3.3.1 |

Verified with JDK 25 and PostgreSQL 17.12 (`postgres:17-alpine`). This platform is pre-release.
Run `./mvnw -B -ntp dependency:list` to inspect resolved versions.

## Manual execution

```bash
docker compose -p hr-cancellation-reproducer up -d --wait
./mvnw -B -ntp test
docker compose -p hr-cancellation-reproducer logs --no-color > postgres.log
docker compose -p hr-cancellation-reproducer down -v
```

## Original report

`ISSUE-DRAFT.md` is the historical draft of the report filed as [Quarkus #58569](https://github.com/quarkusio/quarkus/issues/58569). It links Quarkus #48443 as potentially related.
Identical root cause has not been confirmed by maintainers. The earlier trigger/direct-cancellation
fixture is no longer included: the default test exclusively exercises real HTTP + ordinary locks.
Full generated logs/build outputs are excluded from the archive; actual relevant SQL excerpts
and test results are included in `VERIFICATION.md`. The Maven wrapper retains its license header.

## Optional lifecycle diagnostic

Run `REPRO_TIMING=true ./reproduce.sh` to trace Context/thread and framework cleanup boundaries.
See `TIMING.md`. The Byte Buddy agent is diagnostic-only and is not attached by default.
