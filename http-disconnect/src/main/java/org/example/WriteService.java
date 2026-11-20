package org.example;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.hibernate.reactive.mutiny.Mutiny;

/** Two writes in one framework-declared transaction, with no custom cancellation handling. */
@ApplicationScoped
public class WriteService {
  @Inject Mutiny.Session session;

  /** Updates a contested row, then inserts a marker; successful completion commits both writes. */
  @Transactional
  public Uni<Void> write() {
    return session
        .createMutationQuery("update Counter set value=1 where id=1")
        .executeUpdate()
        .onItem()
        .invoke(n -> Timing.log("APP_UPDATE_ITEM rows=" + n))
        .onCancellation()
        .invoke(() -> Timing.log("APP_UPDATE_CANCEL"))
        .chain(
            () -> {
              Timing.log("APP_INSERT_CALLBACK");
              return session
                  .createNativeMutationQuery(
                      "insert into repro_marker(id) values (gen_random_uuid())")
                  .executeUpdate()
                  .onItem()
                  .invoke(n -> Timing.log("APP_INSERT_ITEM rows=" + n));
            })
        .replaceWithVoid()
        .onCancellation()
        .invoke(() -> Timing.log("APP_WORK_CANCEL"));
  }
}
