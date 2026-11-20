# Quarkus #58569 supplementary experiments

Use JDK 25 and Bash. The Maven wrapper needs access to Maven Central on the first run.
The direct Hibernate Reactive experiment also requires Docker Compose v2.

* Run `./mutiny.sh` for the standalone Mutiny 3.3.1 probe. Four probe cases should complete with exit 0. NEXT_WRITE marks entry into a callback. This probe runs entirely in memory.
* Run `./direct-hr.sh` for six normal commits and eighteen explicit subscription cancellations across Quarkus @Transactional, direct HR, and resource-managed Session with HR 5.1.0.Beta1, managed by the Quarkus 4.0.0.CR1 BOM. The script creates a disposable PostgreSQL 17 database on localhost port 19169 (override with REPRO_PG_PORT), saves its log, and removes the container and volume on exit. On the affected version Maven should exit 1 at the final data and cleanup assertion after all 24 observations. A startup failure does not reproduce the issue.
* Inspect evidence/direct-hr.log for P1_CONTROL and P1_COMPARE, then compare the reported backend PID with evidence/postgres.log. In HR_SESSION, Session.close runs after observing the data. In HR_RESOURCE, the deferred resource finalizer closes the Session. Both use HR Session.withTransaction. The probe's cancellation uses its subscription context and a PostgreSQL advisory lock in a trigger. The original HTTP issue uses a different lock fixture.
* reference/ contains the two independent experiments' observed outputs. Fresh runs write to evidence/.
