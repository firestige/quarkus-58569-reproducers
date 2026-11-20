package org.example;

import io.vertx.core.Vertx;

/** Optional diagnostic observations; does not alter subscription or transaction management. */
public final class Timing {
  private Timing() {}

  public static void log(String event) {
    if (!Boolean.getBoolean("repro.timing")) return;
    var context = Vertx.currentContext();
    String ctx =
        context == null
            ? "null"
            : context.getClass().getSimpleName()
                + "@"
                + Integer.toHexString(System.identityHashCode(context));
    System.out.println(
        "TIMING ns="
            + System.nanoTime()
            + " thread="
            + Thread.currentThread().getName()
            + " ctx="
            + ctx
            + " "
            + event);
  }
}
