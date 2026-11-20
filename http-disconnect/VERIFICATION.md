# Ordinary row lock + real HTTP disconnect: local verification

Verified 2026-11-20 using the official Quarkus 4.0.0.CR1 BOM (HR 5.1.0.Beta1,
ORM 8.0.0.Beta4, Vert.x 5.2.3, Mutiny 3.3.1), JDK 25, Maven 3.9.11 and PostgreSQL 17.12.

The operation models a state update and its associated operation record, using synthetic data.
The blocker is another ordinary transaction updating the same row without committing yet.
The test checks `pg_blocking_pids` to establish the lock dependency. There are no user triggers
(the test asserts this), advisory locks, or calls to cancel a business subscription.
The client closes a real TCP socket using SO_LINGER=0; REST receives and propagates cancellation.

The first local execution and a subsequent portable-directory execution each completed one
connected control and three disconnect cases with identical data outcomes. The portable run
used only this project's files and `./reproduce.sh`. Its final result: 1 test method, 1 failure,
0 errors, 0 skipped; exit 1 from the intended atomicity assertion. Docker cleanup succeeded.

## Actual portable-run output

```text
HTTP_CONTROL pid=126 counter=1 markers=1 cancellation=0 status=200
HTTP_CANCEL iteration=0 pid=133 counter=0 markers=1 cancellation=1 dropped=[]
HTTP_CANCEL iteration=1 pid=135 counter=0 markers=1 cancellation=1 dropped=[]
HTTP_CANCEL iteration=2 pid=133 counter=0 markers=1 cancellation=1 dropped=[]
```

Connected request: both writes committed, HTTP 200. Disconnected requests: the state update
rolled back but the operation record persisted. No dropped exception was captured.

## Actual PG statement excerpts (UTC)

The PID identifies each request backend. ROLLBACK is followed by INSERT without an intervening
BEGIN. PostgreSQL statement logs establish ordering; independent reads establish persistence.

```text
2026-11-20 03:16:38.898 UTC [133] LOG:  statement: BEGIN
2026-11-20 03:16:38.900 UTC [133] LOG:  execute 0000000: update repro_counter c1_0 set value=1 where c1_0.id=1
2026-11-20 03:16:38.916 UTC [133] LOG:  statement: ROLLBACK
2026-11-20 03:16:38.918 UTC [133] LOG:  execute 0000001: insert into repro_marker(id) values (gen_random_uuid())
2026-11-20 03:16:39.448 UTC [135] LOG:  statement: BEGIN
2026-11-20 03:16:39.449 UTC [135] LOG:  execute 0000000: update repro_counter c1_0 set value=1 where c1_0.id=1
2026-11-20 03:16:39.469 UTC [135] LOG:  statement: ROLLBACK
2026-11-20 03:16:39.470 UTC [135] LOG:  execute 0000001: insert into repro_marker(id) values (gen_random_uuid())
2026-11-20 03:16:39.982 UTC [133] LOG:  statement: BEGIN
2026-11-20 03:16:39.982 UTC [133] LOG:  execute 0000000: update repro_counter c1_0 set value=1 where c1_0.id=1
2026-11-20 03:16:40.007 UTC [133] LOG:  statement: ROLLBACK
2026-11-20 03:16:40.008 UTC [133] LOG:  execute 0000001: insert into repro_marker(id) values (gen_random_uuid())
```

## Scope

This confirms a normal REST entry point can encounter the defect during ordinary lock contention
and a client TCP reset. It does not prove identical behavior for FIN, HTTP/2, all browsers,
proxy timeouts, or Native mode. The test only observes a bounded settling interval (500 ms after
the backend is idle with no active transaction); it is not a long-term connection-pool test.
The exact internal root cause remains for maintainers to confirm. No conclusion depends on the
previous trigger/manual-subscription fixture, which is excluded from this project.
