package xyz.firestige.investmentadviser.infrastructure.persistence.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.mutiny.sqlclient.Pool;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import xyz.firestige.investmentadviser.infrastructure.persistence.postgres.investment.ledger.pojo.LedgerCommandPO;
import xyz.firestige.investmentadviser.infrastructure.persistence.postgres.investment.ledger.pojo.LedgerMetaPO;

/** Opt-in real PG comparison; any unsafe outcome fails the final acceptance assertion. */
@QuarkusTest

@EnabledIfEnvironmentVariable(named = "P1_PATH_COMPARISON", matches = "true")
class P1TransactionPathComparisonTest {
  @Inject Owner owner;
  @Inject Mutiny.SessionFactory factory;

  @Singleton
  public static class Owner {
    @Inject Mutiny.Session session;

    @Transactional
    public Uni<Void> execute(boolean flush, CompletableFuture<Context> context) {
      context.complete(Vertx.currentContext());
      return work(session, flush);
    }
  }

  static Uni<Void> work(Mutiny.Session session, boolean flush) {
    if (!flush) {
      return session.createMutationQuery("update LedgerMetaPO set version=1 where singleton=true")
          .executeUpdate()
          .chain(() -> session.createMutationQuery("update LedgerMetaPO set version=2 where singleton=true")
              .executeUpdate()).replaceWithVoid();
    }
    return session.createSelectionQuery("from LedgerMetaPO", LedgerMetaPO.class).getSingleResult()
        .chain(meta -> {
          meta.version = 1;
          var command = new LedgerCommandPO();
          command.commandId = UUID.randomUUID();
          command.generation = meta.generation;
          command.businessId = UUID.randomUUID();
          command.canonicalHash = "a".repeat(64);
          command.receipt = "{}";
          return session.persist(command);
        }); // Intentionally rely on framework auto-flush.
  }

  static void sql(Pool pool, String text) {
    pool.query(text).execute().await().atMost(Duration.ofSeconds(15));
  }

  static long value(Pool pool, String text) {
    return pool.query(text).execute().await().atMost(Duration.ofSeconds(15)).iterator().next().getLong(0);
  }

  @Test
  void compareBothFrameworksWithoutBusinessOrCustomTransactionManagement() throws Exception {
    var pool = Pool.newInstance(PgBuilder.pool().connectingTo(PgConnectOptions.fromUri(System.getenv("INVESTMENT_TEST_RUNTIME_PG_URL"))
        .setUser("investment").setPassword(System.getenv("INVESTMENT_TEST_RUNTIME_PG_PASSWORD")))
        .with(new PoolOptions().setMaxSize(4)).build());
    var failures = new ArrayList<String>();
    try {
      for (String mode : List.of("QUARKUS", "HR_SESSION", "HR_RESOURCE")) {
        for (boolean flush : List.of(false, true)) {
          for (int iteration = -1; iteration < 3; iteration++) {
            String label = mode + (flush ? "_FLUSH" : "_DML") + "_" + iteration;
            sql(pool, "TRUNCATE ledger_commands,ledger_meta CASCADE; INSERT INTO ledger_meta(ledger_id,singleton,generation,version,initialized,confirmations,report_currency,policy_version) VALUES(gen_random_uuid(),true,gen_random_uuid(),0,false,'{}','CNY',0)");
            sql(pool, "CREATE FUNCTION p1_compare_hold() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN PERFORM pg_advisory_xact_lock(97821); RETURN NEW; END$$; CREATE TRIGGER p1_compare_hold AFTER " + (flush ? "INSERT ON ledger_commands" : "UPDATE ON ledger_meta") + " FOR EACH ROW EXECUTE FUNCTION p1_compare_hold()");
            var blocker = pool.getConnection().await().atMost(Duration.ofSeconds(10));
            if (iteration >= 0) {
              blocker.query("SELECT pg_advisory_lock(97821)").execute().await().atMost(Duration.ofSeconds(10));
            }
            var dropped = new CopyOnWriteArrayList<Throwable>();
            Infrastructure.setDroppedExceptionHandler(dropped::add);
            var origin = new CompletableFuture<Context>();
            var manual = new AtomicReference<Mutiny.Session>();
            var subscribed = new CompletableFuture<Cancellable>();
            var terminal = new CompletableFuture<Void>();
            try {
              VertxContextSupport.subscribe(() -> {
                origin.complete(Vertx.currentContext());
                Uni<Void> execution = switch (mode) {
                  case "QUARKUS" -> owner.execute(flush, origin);
                  case "HR_SESSION" -> factory.openSession().chain(session -> {
                    manual.set(session);
                    return session.withTransaction(tx -> work(session, flush));
                  });
                  case "HR_RESOURCE" -> Multi.createFrom().resourceFromUni(factory::openSession,
                      session -> session.withTransaction(tx -> work(session, flush)).replaceWith(true).toMulti())
                      .withFinalizer(session -> { return Uni.createFrom().deferred(session::close); })
                      .collect().asList().replaceWithVoid();
                  default -> throw new IllegalArgumentException(mode);
                };
                return execution.replaceWith(true).toMulti();
              }, subscriber -> subscribed.complete(subscriber.with(x -> terminal.complete(null), terminal::completeExceptionally)));
              var subscription = subscribed.get(15, TimeUnit.SECONDS);
              if (iteration < 0) {
                terminal.get(15, TimeUnit.SECONDS);
                assertEquals(flush ? 1 : 2, value(pool, "SELECT version FROM ledger_meta"));
                assertEquals(flush ? 1 : 0, value(pool, "SELECT count(*) FROM ledger_commands"));
                assertTrue(dropped.isEmpty(), dropped.toString());
                System.out.println("P1_CONTROL " + label + " committed=true dropped=[]");
                continue;
              }
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
              while (value(pool, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory'") == 0) {
                if (terminal.isDone()) { terminal.get(); throw new AssertionError("Completed before gate: " + label); }
                if (System.nanoTime() > deadline) { throw new AssertionError("No SQL gate: " + label); }
                Thread.sleep(10);
              }
              long pid = value(pool, "SELECT pid::bigint FROM pg_stat_activity WHERE datname=current_database() AND wait_event='advisory' LIMIT 1");
              var cancelled = new CompletableFuture<Void>();
              origin.get().runOnContext(ignored -> { subscription.cancel(); cancelled.complete(null); });
              cancelled.get(10, TimeUnit.SECONDS);
              blocker.query("SELECT pg_advisory_unlock(97821)").execute().await().atMost(Duration.ofSeconds(10));
              // Wait for the exact backend to become idle; bounded stabilization also catches late DML.
              deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
              while (value(pool, "SELECT count(*) FROM pg_stat_activity WHERE pid=" + pid + " AND (state<>'idle' OR xact_start IS NOT NULL)") != 0) {
                if (System.nanoTime() > deadline) { throw new AssertionError("Backend not released: " + label); }
                Thread.sleep(10);
              }
              Thread.sleep(200);
              long version = value(pool, "SELECT version FROM ledger_meta");
              long commands = value(pool, "SELECT count(*) FROM ledger_commands");
              long active = value(pool, "SELECT count(*) FROM pg_stat_activity WHERE pid=" + pid + " AND xact_start IS NOT NULL");
              System.out.println("P1_COMPARE " + label + " pid=" + pid + " version=" + version + " commands=" + commands + " active=" + active + " terminal=" + terminal.isDone() + " dropped=" + dropped);
              if (version != 0 || commands != 0 || active != 0 || terminal.isDone() || !dropped.isEmpty()) { failures.add(label); }
              // Follow-up query through the same framework API, not only the admin pool.
              var followup = new CompletableFuture<Long>();
              VertxContextSupport.subscribe(() -> factory.withSession(s -> s.createSelectionQuery("select version from LedgerMetaPO", Long.class).getSingleResult()).toMulti(),
                  subscriber -> subscriber.with(followup::complete, followup::completeExceptionally));
              assertEquals(version, followup.get(10, TimeUnit.SECONDS));
            } finally {
              if (manual.get() != null) {
                var closed = new CompletableFuture<Void>();
                origin.get().runOnContext(ignored -> manual.get().close().subscribe().with(
                    x -> closed.complete(null), closed::completeExceptionally));
                closed.get(10, TimeUnit.SECONDS);
              }
              blocker.close().await().atMost(Duration.ofSeconds(10));
              sql(pool, "DROP TRIGGER p1_compare_hold ON " + (flush ? "ledger_commands" : "ledger_meta") + "; DROP FUNCTION p1_compare_hold()");
              Infrastructure.resetDroppedExceptionHandler();
            }
          }
        }
      }
      assertTrue(failures.isEmpty(), "Unsafe framework paths: " + failures);
    } finally {
      pool.close().await().atMost(Duration.ofSeconds(10));
    }
  }
}
