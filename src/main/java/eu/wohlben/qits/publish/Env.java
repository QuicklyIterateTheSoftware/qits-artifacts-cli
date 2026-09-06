package eu.wohlben.qits.publish;

import java.util.Map;

/**
 * The environment, as a value.
 *
 * <p>A wrapper this thin exists for one reason: the suite drives the whole program with a map, so
 * every "which variable was missing" message is a test rather than something discovered in a step
 * container. Nothing below {@link Cli} calls {@code System.getenv}.
 *
 * <p><b>Empty is absent.</b> qits-ci spells "off" as empty-never-absent for several of the variables
 * that reach a step container — the buildkit kill switch and the maven mirror both do — so a program
 * that distinguished the two would branch on a difference the platform does not make.
 */
record Env(Map<String, String> values) {

  static Env ofSystem() {
    return new Env(System.getenv());
  }

  /** The value, or {@code null} when it is unset or empty. */
  String get(String name) {
    String value = values.get(name);
    return value == null || value.isEmpty() ? null : value;
  }

  /**
   * The value, or a refusal naming the variable and what it is for. {@link ExitCode#TRANSPORT}
   * rather than {@code POLICY}: a missing address means the question could not be put, and a step
   * that retries after its deployment is configured is doing the right thing.
   */
  String require(String name, String what) {
    String value = get(name);
    if (value == null) {
      throw CliException.transport(name + " is not set — " + what);
    }
    return value;
  }
}
