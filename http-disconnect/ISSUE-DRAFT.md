<!-- Local review draft, not submitted. Target: quarkusio/quarkus.
Fields follow .github/ISSUE_TEMPLATE/bug_report.yml, fetched 2026-11-20,
blob SHA 1d2207844dcc1049f41389c14737135df2cec05c.
The archive is local and has not been uploaded. -->

# HTTP disconnect during a PostgreSQL row-lock wait causes INSERT after ROLLBACK with @Transactional

### Describe the bug

With the default HTTP cancellation behavior, we observed an UPDATE being rolled back while
its subsequent INSERT still executed and persisted in an `@Transactional` method.
**Is this partial-write outcome expected behavior, or a bug in transaction cancellation handling?**

A Quarkus REST POST endpoint returns a Uni from an injected `@Transactional` service.
The service updates a state row and then inserts its associated operation record. Another
transaction holds the row lock; the HTTP client disconnects while the UPDATE waits.

Quarkus REST documents that an HTTP disconnect cancels the returned Uni pipeline:
https://quarkus.io/guides/rest/#request-cancellation
The endpoint uses this default behavior. Its cancellation counter records the received signal.

Related report: #48443. A self-contained reproducer and observations are provided below.

### Expected behavior

The connected control commits both writes (Counter=1, Marker count=1).
Once the cancelled request rolls back, neither write persists (Counter=0, Marker count=0).

### Actual behavior

| Case | Response / observed cancellation | Counter | Marker count |
| --- | --- | --- | --- |
| Connected control | HTTP 200 / 0 | 1 | 1 |
| Disconnect, repetition 1 | cancellation observed once | 0 | 1 |
| Disconnect, repetition 2 | cancellation observed once | 0 | 1 |
| Disconnect, repetition 3 | cancellation observed once | 0 | 1 |

PostgreSQL logs on the same request backend show the following normalized order:

```sql
BEGIN;
UPDATE repro_counter SET value=1 WHERE id=1; -- waits for another transaction's row lock
-- client resets TCP connection; REST cancellation observed; lock holder commits
ROLLBACK;
INSERT INTO repro_marker(id) VALUES (gen_random_uuid()); -- no intervening BEGIN
```

Independent reads confirm the INSERT persisted while the UPDATE rolled back. No dropped
exception was captured; the backend had no active transaction at observation time.
All four cases finish before the final assertion fails: 1 test method, 1 failure, 0 errors,
0 skips. Exact SQL excerpts are in `VERIFICATION.md`.

### How to Reproduce?

Reproducer: `hr-cancellation-reproducer.zip` (local review artifact; attach when submitting).
Self-contained Maven project; requirements: JDK 25, Docker Compose v2 and Maven Central access.

1. Extract the archive and enter its project directory.
2. Run `./reproduce.sh` (or `REPRO_PG_PORT=19083 ./reproduce.sh` if port 19082 is occupied).
3. Inspect `evidence/test.log`, `evidence/postgres.log` and `target/surefire-reports/`.

The script provisions and removes disposable PG resources, and returns Maven's exit code.
On the tested version, the script returns exit code 1 from the final assertion comparing the
database state with the expected rollback result.

The test models two ordinary overlapping transactions:

1. Initialize Counter(id=1,value=0) and an empty Marker table.
2. Transaction A runs `UPDATE repro_counter SET value=value WHERE id=1` and retains its row lock.
3. A real HTTP/1.1 POST to `/write` starts transaction B. Its first UPDATE waits for A.
4. Confirm the wait in `pg_stat_activity` and confirm A using `pg_blocking_pids(B)`.
5. Close the client socket with `SO_LINGER=0` (TCP reset); wait for REST to observe cancellation.
6. Commit A, releasing the lock. Wait until B's backend is idle with no active transaction,
   observe another 500 ms, and independently query both tables.

A connected control follows the same lock procedure but waits for HTTP 200. Disconnect is
repeated three times. The service's core operation is:

```java
@Transactional
public Uni<Void> write() {
  return session.createMutationQuery("update Counter set value=1 where id=1")
      .executeUpdate()
      .chain(() -> session.createNativeMutationQuery(
          "insert into repro_marker(id) values (gen_random_uuid())").executeUpdate())
      .replaceWithVoid();
}
```

### Output of `uname -a` or `ver`

Linux / WSL2, kernel 6.6.87.2-microsoft-standard-WSL2, x86_64 GNU/Linux.
(Platform summary from `uname -srmo`, rather than literal `uname -a` output.)

### Output of `java -version`

```text
openjdk version "25.0.4.1" 2026-08-18 LTS
OpenJDK Runtime Environment Zulu25.36+205-CA (build 25.0.4.1+1-LTS)
OpenJDK 64-Bit Server VM Zulu25.36+205-CA (build 25.0.4.1+1-LTS, mixed mode, sharing)
```

### Quarkus version or git rev

4.0.0.CR1 official platform BOM and matching Maven plugin (pre-release).
No component version overrides. Resolved dependencies:

| Component | Version |
| --- | --- |
| Hibernate Reactive | 5.1.0.Beta1 |
| Hibernate ORM | 8.0.0.Beta4 |
| Vert.x core / PostgreSQL client | 5.2.3 |
| Mutiny | 3.3.1 |

The HTTP reproducer was tested with this combination.

### Build tool (ie. output of `mvnw --version` or `gradlew --version`)

Apache Maven 3.9.11 (3e54c93a704957b63ee3494413a2b544fd3d825b), included wrapper.
Java 25.0.4.1, Azul Systems; UTF-8; Linux amd64.

### Additional information

Database: PostgreSQL 17.12 during verification (`postgres:17-alpine`).
Execution: JVM, HTTP/1.1, `@Transactional`, default request cancellation.

The scenario represents updating a state row and recording the associated operation atomically.
It uses synthetic data.
The ordinary lock wait is prolonged to make the disconnect timing repeatable.

Optional `REPRO_TIMING=true ./reproduce.sh` attaches diagnostic-only Byte Buddy method advice.
The default reproduction needs no agent. Observations and exact-version source anchors are in
`TIMING.md`; instrumentation records events, does not replace framework logic, and can affect
relative timing. The observed lifecycle is:

1. REST cancellation reaches Quarkus's outer `.eventually(this::closeConnection)` first.
2. Mutiny `UniOnTerminationCall.cancel()` awaits its cleanup Uni before `super.cancel()` propagates
   cancellation upstream. The business chain has not yet received cancellation.
3. Vert.x connection close requests rollback and clears the connection's transaction association
   while UPDATE is still in flight. This is the first rollback request.
4. After the lock is released, the driver schedules ROLLBACK and delivers the successful UPDATE
   result. The still-active business chain invokes the INSERT supplier. The same connection
   schedules INSERT with `tx=null`.
5. Only after cleanup does cancellation reach Quarkus's inner `rollbackOnCancel()` and then the
   business chain. INSERT has already been scheduled.

REST cancellation, UPDATE item, INSERT supplier and late business cancellation share the same
request DuplicatedContext and event-loop thread. Driver completion uses its connection Context.
INSERT's Java callback occurred before
the rollback-response callback twice and after it once; PG executed ROLLBACK before INSERT in
all three.
