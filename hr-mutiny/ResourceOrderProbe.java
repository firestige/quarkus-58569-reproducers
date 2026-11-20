import io.smallrye.mutiny.*;
import io.smallrye.mutiny.subscription.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ResourceOrderProbe {
  static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
  static void probe(boolean resource, boolean hot, boolean intermediate) {
    List<String> events = new ArrayList<>();
    AtomicReference<UniEmitter<? super Integer>> sql = new AtomicReference<>();
    AtomicReference<UniEmitter<? super Void>> cleanup = new AtomicReference<>();
    CompletableFuture<Integer> first = new CompletableFuture<>();
    Uni<Integer> work;
    if (hot) {
      CompletionStage<Integer> tail = first.thenApply(x -> { events.add("NEXT_WRITE"); return 2; });
      if (intermediate) tail = tail.thenApply(x -> x);
      work = Uni.createFrom().completionStage(tail);
    } else {
      work = Uni.createFrom().<Integer>emitter(sql::set)
        .onCancellation().invoke(() -> events.add("UPSTREAM_CANCEL"))
        .chain(x -> { events.add("NEXT_WRITE"); return Uni.createFrom().item(2); });
    }
    java.util.function.Supplier<Uni<Void>> finalizer = () -> Uni.createFrom().deferred(() -> {
      events.add("CLEANUP_SUBSCRIBED");
      return Uni.createFrom().<Void>emitter(cleanup::set);
    });
    Cancellable sub;
    if (resource) {
      sub = Multi.createFrom().resource(() -> "resource", r -> work.toMulti())
        .withFinalizer(r -> Uni.createFrom().voidItem(), (r,e) -> Uni.createFrom().voidItem(), r -> finalizer.get())
        .subscribe().with(x -> events.add("DELIVERED"));
    } else {
      sub = work.onCancellation().call(() -> finalizer.get()).subscribe().with(x -> events.add("DELIVERED"));
    }
    sub.cancel();
    if (hot) first.complete(1); else sql.get().complete(1);
    boolean nextBeforeCleanup = events.contains("NEXT_WRITE");
    cleanup.get().complete(null);
    check(nextBeforeCleanup == (!resource || (hot && intermediate)), "unexpected continuation: " + events);
    check(!events.contains("DELIVERED"), "late delivery");
    System.out.println((resource ? "RESOURCE" : "CANCELLATION_CALL") + (hot ? (intermediate ? " HOT_STAGE_INTERMEDIATE" : " HOT_STAGE_DIRECT") : " UNI_CHAIN") + " " + events);
  }
  public static void main(String[] args) {
    probe(false, false, false);
    probe(true, false, false);
    probe(true, true, false);
    probe(true, true, true);
  }
}
