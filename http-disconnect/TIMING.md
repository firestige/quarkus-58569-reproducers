# Cancellation lifecycle tracing (local diagnostic, not a fix)

2026-11-20; Quarkus 4.0.0.CR1 / HR 5.1.0.Beta1 / Mutiny 3.3.1 / Vert.x 5.2.3.
The real HTTP/reset + ordinary row-lock scenario is unchanged. Optional Byte Buddy advice
observes published framework methods at entry/exit; it does not replace transactions, change
return values, suppress cancellation, or modify production dependencies. Source jars of the
exact framework versions were compared with the traced methods. Logging may perturb timings;
this run is used to establish ordering, not latency or relative probabilities.

## Run

```bash
REPRO_TIMING=true ./reproduce.sh
```

This downloads Byte Buddy 1.17.7 as a diagnostic-only tool, compiles a small Java agent, and
attaches it to the test JVM. Normal `./reproduce.sh` does not attach the agent. The test continues
to fail the intended atomicity assertion. Framework lambda names in the diagnostic are tied to
the stated versions; do not assume the agent works unchanged on other versions.

`TIMING` lines contain JVM monotonic nanoseconds, current thread and identity of current Vert.x
Context. Method EXIT means the Java method returned, **not** its returned Future completed.
`TransactionImpl.lambda$checkEnd$1(... ROLLBACK, null, null)` is the successful rollback command
response callback; it is distinct from entering `rollback()` or scheduling its command.

## Observed order

1. Actual client socket reset; REST observes cancellation.
2. Quarkus's outer `.eventually(this::closeConnection)` runs its cleanup before forwarding
   cancellation upstream. `UniOnTerminationCall.cancel()` awaits the cleanup Uni.
3. Connection close calls Vert.x `TransactionImpl.rollback()` while UPDATE still waits for A's
   lock. This requests transaction end; it does not mean ROLLBACK has already executed.
4. `SqlConnectionBase.actualClose()` clears its `tx` reference immediately after requesting
   rollback. The pending business work has not yet received cancellation.
5. A releases its row lock. The driver receives the successful UPDATE response.
6. `TransactionImpl.wrap()` first calls `checkEnd()` (which schedules the pending ROLLBACK),
   then delivers the UPDATE result. On the request Context, `APP_UPDATE_ITEM` and
   `APP_INSERT_CALLBACK` execute. INSERT scheduling observes the same connection with `tx=null`.
7. The database processes ROLLBACK before INSERT. The rollback-response callback can be observed
   either before or after the application INSERT callback; these are separate callback boundaries.
8. Connection cleanup completes; only then does cancellation reach Quarkus `rollbackOnCancel()`
   and finally the application `APP_WORK_CANCEL`. INSERT has already been scheduled.

Consequently the first rollback is initiated by connection close, not by the later Quarkus
rollback-on-cancel callback. The later callback finds no connection after cleanup removes it
from Context (source path); tracing shows no second driver rollback from that callback.

## Exact sample from the review run

Relative milliseconds from REST_CANCEL, iteration 0. Thread names abbreviated as EL1/EL2.

| Event | Time (ms) | Thread / Context |
| --- | ---: | --- |
| REST_CANCEL | 0.000 | EL2 / DuplicatedContext@54e38848 |
| Quarkus closeConnection entered | 0.261 | same request Context |
| Vert.x rollback() entered | 0.564 | same request Context |
| A release requested | 8.845 | test main / no Context |
| APP_UPDATE_ITEM | 12.335 | same request Context |
| Successful rollback response callback entered | 12.349 | EL1 / ContextImpl@442cf8c2 |
| APP_INSERT_CALLBACK | 12.433 | EL2 / original request Context |
| Quarkus rollbackOnCancel entered | 13.550 | original request Context |
| APP_WORK_CANCEL | 14.267 | original request Context |

For iterations 1 and 2, APP_INSERT_CALLBACK occurred **before** the rollback-response callback,
but still after ROLLBACK was scheduled, and before APP_WORK_CANCEL. PG logged ROLLBACK before
INSERT in all three cases. All three ended Counter=0 / Marker count=1, with no captured dropped
exception. The connected control committed both writes. Surefire: 1 failure, 0 errors, 0 skips.

## Context and responsibility

REST_CANCEL, UPDATE item, INSERT supplier, late rollbackOnCancel, and APP_WORK_CANCEL share the
same DuplicatedContext identity and event-loop thread within each request. There is no business
fork or Context loss. Driver protocol completion uses the connection ContextImpl; it may run on
a different event-loop thread (iteration 0) or the same thread under a different Context
(iterations 1/2). Same thread does not imply same Context.

The directly observed failure mechanism for this **Quarkus declaration-based path** is lifecycle
composition: asynchronous outer connection cleanup delays cancellation propagation into the
business chain, while successful in-flight work is still allowed to advance. Vert.x connection
close clears the transaction association before that work has stopped, so the later command
is scheduled outside the original transaction.

Source anchors in the published source jars:

- Quarkus `TransactionalInterceptorBase.defineReactiveTransactionalChain`: lines 89–100;
  `closeConnection`: 131–140; `rollbackOnCancel`: 179–191.
- Quarkus `pool.TransactionalContextPool.closeAndClearCurrentConnection`: 78–94.
- Mutiny `UniOnTerminationCall.UniOnTerminationCallProcessor.cancel`: cleanup Uni before
  `super.cancel()`; `UniOnCancellationCall.cancel` similarly waits before upstream cancellation.
- Vert.x `SqlConnectionBase.actualClose`: 81–90 (rollback request followed by tx=null);
  `schedule`: 157–175 (tx=null uses direct connection scheduling).
- Vert.x `TransactionImpl.wrap`: 61–68 (checkEnd before result delivery);
  `checkEnd`: 88–107 (wait pending queries, then schedule end command).

This identifies the failing interaction and the cancellation delay point; it is not an upstream
maintainer verdict on which project should own the final patch. It does not establish that the
previous HR-native path uses the identical outer cleanup implementation. No production fix or
custom transaction manager has been introduced.
