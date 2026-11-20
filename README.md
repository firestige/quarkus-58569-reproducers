# Quarkus #58569 reproducers

An `@Transactional` method updates a counter and inserts a marker in the same transaction, so both writes should commit or roll back together. In the reported HTTP disconnect case, PostgreSQL rolled back the counter update, then executed the marker insert. An independent connection found the marker and the original counter value. [Quarkus issue #58569](https://github.com/quarkusio/quarkus/issues/58569) · [Follow-up observations](https://github.com/quarkusio/quarkus/issues/58569#issuecomment-7043683036)

**Upstream status (2026-11-21).** The issue is open. Yoann Rodière reports that the test passes with his proposed Quarkus change, which is still a draft. A separate Mutiny draft explores forwarding cancellation upstream before asynchronous cleanup starts. Both changes are under discussion. [Quarkus PR #58598](https://github.com/quarkusio/quarkus/pull/58598) · [Mutiny PR #2251](https://github.com/smallrye/smallrye-mutiny/pull/2251)

Both PostgreSQL tests use JDK 25 and the official Quarkus 4.0.0.CR1 BOM (Hibernate Reactive 5.1.0.Beta1, Hibernate ORM 8.0.0.Beta4, Vert.x 5.2.3 and Mutiny 3.3.1). The scripts require Bash and Maven Central access on the first run. The database tests use Docker Compose v2 and disposable PostgreSQL 17 on loopback.

| Run from the repository root | Observed result on the reported versions | Reference output |
| --- | --- | --- |
| `./http-disconnect/reproduce.sh` | One connected control commits both writes. Three HTTP disconnects during a row-lock wait roll back the UPDATE and leave the INSERT committed. The test exits 1 at its final atomicity assertion. | [HTTP SQL and test output](http-disconnect/VERIFICATION.md) |
| `REPRO_TIMING=true ./http-disconnect/reproduce.sh` | The same test with optional Byte Buddy tracing of cancellation, rollback and driver scheduling. | [Diagnostic trace and source anchors](http-disconnect/TIMING.md) |
| `./hr-mutiny/mutiny.sh` | Four in-memory probes complete with exit 0. The probe records when a pending asynchronous cancellation handler allows the next callback to run. | [Mutiny output](hr-mutiny/reference/mutiny.log) |
| `./hr-mutiny/direct-hr.sh` | Six normal controls commit. Across eighteen cancellations, Quarkus and HR sessions held open leave partial writes. The resource finalizer rolls back both writes and records HR000090 on session close. The test exits 1 at its final assertion. | [Three-path output](hr-mutiny/reference/three-paths.log) and [PostgreSQL trace](hr-mutiny/reference/verified-backend.log) |

Startup failures and timeouts do not reproduce the reported behavior. Check the specific observations above in the `evidence/` directory beside each script. Each database runner removes its Compose resources when it exits. The HTTP test uses loopback port 19082 and the HR comparison uses 19169 by default. Set `REPRO_PG_PORT` to an unused port if needed.

The two original Gists remain available at the links from the issue discussion. This repository contains their extracted source and reference output. [HTTP and diagnostics Gist](https://gist.github.com/firestige/ad29cda0400725da665335d13b8d228e) · [Hibernate Reactive and Mutiny Gist](https://gist.github.com/firestige/3adbae192c1e29aa3189ba4f4ba7e34a).
