package org.example;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import java.util.concurrent.atomic.AtomicInteger;

/** Normal REST entry point; the counter only observes framework cancellation. */
@Path("/write")
@ApplicationScoped
public class WriteEndpoint {
  public static final AtomicInteger cancellations = new AtomicInteger();
  @Inject WriteService service;

  /** Returns the transaction Uni to Quarkus REST without subscribing or cancelling it manually. */
  @POST
  public Uni<String> write() {
    return service
        .write()
        .replaceWith("ok")
        .onCancellation()
        .invoke(
            () -> {
              Timing.log("REST_CANCEL");
              cancellations.incrementAndGet();
            });
  }
}
