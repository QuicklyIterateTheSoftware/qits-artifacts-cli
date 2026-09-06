package eu.wohlben.qits.publish;

/**
 * A refusal carrying the exit code it must produce.
 *
 * <p>Everything that ends a command unhappily throws one of these rather than calling {@code
 * System.exit}, so the whole surface stays drivable from a test: {@link Cli#run} is a function from
 * argv and environment to an integer, and the suite asserts on that integer and on the two streams.
 * Nothing below {@code Main} knows the JVM can be exited.
 */
final class CliException extends RuntimeException {

  private final int code;

  CliException(int code, String message) {
    super(message);
    this.code = code;
  }

  CliException(int code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  int code() {
    return code;
  }

  /** The caller asked for something that cannot be done; asking again will not change it. */
  static CliException policy(String message) {
    return new CliException(ExitCode.POLICY, message);
  }

  /** The question could not be put, or could not be answered. */
  static CliException transport(String message) {
    return new CliException(ExitCode.TRANSPORT, message);
  }

  static CliException transport(String message, Throwable cause) {
    return new CliException(ExitCode.TRANSPORT, message, cause);
  }
}
