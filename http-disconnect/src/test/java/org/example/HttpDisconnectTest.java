package org.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Real HTTP disconnect while an ordinary database transaction holds a conflicting row lock. */
@QuarkusTest
class HttpDisconnectTest {
  @TestHTTPResource URI uri;

  static void sql(Pool p, String s) {
    p.query(s).execute().await().atMost(Duration.ofSeconds(10));
  }

  static long value(Pool p, String s) {
    return p.query(s).execute().await().atMost(Duration.ofSeconds(10)).iterator().next().getLong(0);
  }

  @Test
  void disconnectWhileWaitingForOrdinaryRowLock() throws Exception {
    var p =
        Pool.newInstance(
            PgBuilder.pool()
                .connectingTo(
                    new PgConnectOptions()
                        .setHost("127.0.0.1")
                        .setPort(Integer.getInteger("repro.pg.port", 19082))
                        .setDatabase("repro")
                        .setUser("repro")
                        .setPassword("repro-only"))
                .with(new PoolOptions().setMaxSize(4))
                .build());
    var failures = new ArrayList<String>();
    try {
      assertEquals(
          0,
          value(
              p,
              "select count(*) from pg_trigger where not tgisinternal and tgrelid in"
                  + " ('repro_counter'::regclass,'repro_marker'::regclass)"));
      for (int iteration = -1; iteration < 3; iteration++) {
        sql(
            p,
            "truncate repro_counter,repro_marker; insert into repro_counter(id,value) values(1,0)");
        var errors = new CopyOnWriteArrayList<Throwable>();
        Infrastructure.setDroppedExceptionHandler(errors::add);
        WriteEndpoint.cancellations.set(0);
        var blocker = p.getConnection().await().atMost(Duration.ofSeconds(10));
        var tx = blocker.begin().await().atMost(Duration.ofSeconds(10));
        boolean released = false;
        try (var socket = new Socket("127.0.0.1", uri.getPort())) {
          socket.setSoTimeout(10000);
          blocker
              .query("update repro_counter set value=value where id=1")
              .execute()
              .await()
              .atMost(Duration.ofSeconds(10));
          long blockerPid =
              blocker
                  .query("select pg_backend_pid()::bigint")
                  .execute()
                  .await()
                  .atMost(Duration.ofSeconds(10))
                  .iterator()
                  .next()
                  .getLong(0);
          socket
              .getOutputStream()
              .write(
                  ("POST /write HTTP/1.1\r\n"
                          + "Host: localhost\r\n"
                          + "Content-Length: 0\r\n"
                          + "Connection: close\r\n\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
          String gate =
              "select count(*) from pg_stat_activity where datname=current_database() and"
                  + " wait_event_type='Lock' and "
                  + blockerPid
                  + "=any(pg_blocking_pids(pid))";
          while (value(p, gate) == 0) {
            if (System.nanoTime() > deadline)
              throw new AssertionError("HTTP request did not reach row lock");
            Thread.sleep(10);
          }
          long pid =
              value(
                  p,
                  "select pid::bigint from pg_stat_activity where datname=current_database() and"
                      + " wait_event_type='Lock' and "
                      + blockerPid
                      + "=any(pg_blocking_pids(pid)) limit 1");
          if (iteration >= 0) {
            // Close the actual HTTP transport with RST. No reactive subscription is accessed.
            Timing.log("CLIENT_RESET iteration=" + iteration);
            socket.setSoLinger(true, 0);
            socket.close();
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (WriteEndpoint.cancellations.get() == 0) {
              if (System.nanoTime() > deadline)
                throw new AssertionError("REST cancellation was not observed");
              Thread.sleep(10);
            }
          }
          Timing.log("BLOCKER_RELEASE iteration=" + iteration);
          tx.commit().await().atMost(Duration.ofSeconds(10));
          released = true;
          if (iteration < 0) {
            String response =
                new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200"), response);
            assertEquals(1, value(p, "select value from repro_counter"));
            assertEquals(1, value(p, "select count(*) from repro_marker"));
            assertEquals(0, WriteEndpoint.cancellations.get());
            assertTrue(errors.isEmpty(), errors.toString());
            System.out.println(
                "HTTP_CONTROL pid=" + pid + " counter=1 markers=1 cancellation=0 status=200");
            continue;
          }
          deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (value(
                  p,
                  "select count(*) from pg_stat_activity where pid="
                      + pid
                      + " and (state<>'idle' or xact_start is not null)")
              != 0) {
            if (System.nanoTime() > deadline) throw new AssertionError("Backend did not settle");
            Thread.sleep(10);
          }
          Thread.sleep(500);
          long counter = value(p, "select value from repro_counter");
          long markers = value(p, "select count(*) from repro_marker");
          System.out.println(
              "HTTP_CANCEL iteration="
                  + iteration
                  + " pid="
                  + pid
                  + " counter="
                  + counter
                  + " markers="
                  + markers
                  + " cancellation="
                  + WriteEndpoint.cancellations.get()
                  + " dropped="
                  + errors);
          if (counter != 0 || markers != 0 || !errors.isEmpty())
            failures.add(
                "iteration="
                    + iteration
                    + " counter="
                    + counter
                    + " markers="
                    + markers
                    + " dropped="
                    + errors);
        } finally {
          if (!released) tx.rollback().await().atMost(Duration.ofSeconds(10));
          blocker.close().await().atMost(Duration.ofSeconds(10));
          Infrastructure.resetDroppedExceptionHandler();
        }
      }
      assertTrue(failures.isEmpty(), "HTTP cancellation violated atomicity/cleanup: " + failures);
    } finally {
      p.close().await().atMost(Duration.ofSeconds(10));
    }
  }
}
