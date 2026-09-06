package eu.wohlben.qits.publish;

import java.io.PrintStream;

/**
 * Where words go, and the one rule that decides which stream: <b>stdout is for answers a script
 * reads, stderr is for sentences a human reads.</b>
 *
 * <p>{@code npm plan} is why. Its whole output is one word that a pipeline captures in a command
 * substitution, so a single progress line printed beside it would be part of the answer. Rather than
 * give that one command a quiet mode — a flag nobody would remember to pass — every log line in this
 * program goes to stderr and only {@link #answer} writes to stdout. A CI step interleaves both, so
 * nothing is hidden from the log by this.
 */
final class Console {

  private final PrintStream out;
  private final PrintStream err;

  Console(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  /** A fact about what happened. */
  void info(String message) {
    err.println(message);
  }

  /**
   * A degradation, named. Reserved for the case the policy calls out: a coordinate was occupied and
   * the store offers nothing to compare the bytes against, so this run skipped rather than verified.
   * A WARN is how that stays visible instead of reading as a clean success.
   */
  void warn(String message) {
    err.println("WARN: " + message);
  }

  /** Why the command failed. Printed by {@link Cli} from the thrown refusal, never from a command. */
  void error(String message) {
    err.println("qits-publish: " + message);
  }

  /** The machine-readable result, and the only thing this program writes to stdout. */
  void answer(String value) {
    out.println(value);
  }
}
