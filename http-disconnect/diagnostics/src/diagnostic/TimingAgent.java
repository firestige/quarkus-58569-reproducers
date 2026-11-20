package diagnostic;

import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.none;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/** Read-only method tracing of published framework bytecode. */
public class TimingAgent {
  public static void premain(String args, Instrumentation instrumentation) {
    new AgentBuilder.Default(new ByteBuddy().ignore(none()))
        .ignore(nameStartsWith("net.bytebuddy.").or(nameStartsWith("diagnostic.")))
        .with(AgentBuilder.Listener.StreamWriting.toSystemError().withErrorsOnly())
        .type(named("io.vertx.sqlclient.impl.TransactionImpl"))
        .transform(
            (b, t, c, m, p) ->
                b.visit(
                    Advice.to(Events.class)
                        .on(
                            named("rollback")
                                .and(takesArguments(0))
                                .or(named("lambda$checkEnd$1"))
                                .or(named("lambda$wrap$0"))
                                .or(named("execute")))))
        .type(named("io.quarkus.reactive.transaction.runtime.TransactionalInterceptorBase"))
        .transform(
            (b, t, c, m, p) ->
                b.visit(
                    Advice.to(Events.class)
                        .on(named("rollbackOnCancel").or(named("closeConnection")))))
        .type(
            named(
                "io.smallrye.mutiny.operators.uni.UniOnCancellationCall$UniOnCancellationCallProcessor"))
        .transform(
            (b, t, c, m, p) ->
                b.visit(
                    Advice.to(Events.class)
                        .on(named("cancel").or(nameStartsWith("lambda$cancel$")))))
        .type(
            named(
                "io.smallrye.mutiny.operators.uni.UniOnTerminationCall$UniOnTerminationCallProcessor"))
        .transform(
            (b, t, c, m, p) ->
                b.visit(
                    Advice.to(Events.class)
                        .on(named("cancel").or(nameStartsWith("lambda$cancel$")))))
        .type(named("io.vertx.sqlclient.internal.SqlConnectionBase"))
        .transform(
            (b, t, c, m, p) ->
                b.visit(
                    Advice.to(ConnectionEvents.class)
                        .on(
                            named("schedule")
                                .or(nameStartsWith("lambda$actualClose$"))
                                .or(nameStartsWith("lambda$begin$")))))
        .installOn(instrumentation);
  }

  public static class Events {
    @Advice.OnMethodEnter
    public static void enter(@Advice.Origin String method, @Advice.AllArguments Object[] args) {
      TimingAgent.log("ENTER " + method, args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Origin String method, @Advice.Thrown Throwable failure) {
      TimingAgent.log("EXIT " + method + " failure=" + failure, new Object[0]);
    }
  }

  public static class ConnectionEvents {
    @Advice.OnMethodEnter
    public static void enter(
        @Advice.Origin String method,
        @Advice.This Object self,
        @Advice.FieldValue("tx") Object tx,
        @Advice.AllArguments Object[] args) {
      TimingAgent.log(
          "ENTER "
              + method
              + " connection="
              + Integer.toHexString(System.identityHashCode(self))
              + " tx="
              + tx,
          args);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(
        @Advice.Origin String method,
        @Advice.This Object self,
        @Advice.FieldValue("tx") Object tx) {
      TimingAgent.log(
          "EXIT "
              + method
              + " connection="
              + Integer.toHexString(System.identityHashCode(self))
              + " tx="
              + tx,
          new Object[0]);
    }
  }

  public static void log(String event, Object[] args) {
    String ctx = "unavailable";
    try {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      Class<?> vertx = Class.forName("io.vertx.core.Vertx", false, loader);
      Object context = vertx.getMethod("currentContext").invoke(null);
      ctx =
          context == null
              ? "null"
              : context.getClass().getSimpleName()
                  + "@"
                  + Integer.toHexString(System.identityHashCode(context));
    } catch (ReflectiveOperationException e) {
      ctx = e.getClass().getSimpleName();
    }
    System.out.println(
        "TIMING ns="
            + System.nanoTime()
            + " thread="
            + Thread.currentThread().getName()
            + " ctx="
            + ctx
            + " "
            + event
            + " args="
            + java.util.Arrays.toString(args));
  }
}
