package eu.wohlben.qits.publish;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Running the program the way a step does: an environment, an argv, and three things to assert on —
 * the exit code, what went to stdout, and what went to stderr.
 */
final class Harness {

  private final Map<String, String> env = new LinkedHashMap<>();

  /** What one invocation produced. */
  record Run(int code, String out, String err) {

    boolean errContains(String fragment) {
      return err.contains(fragment);
    }
  }

  Harness with(String name, String value) {
    env.put(name, value);
    return this;
  }

  /** The two variables almost every command needs, pointed at a stub. */
  Harness store(StubStore store) {
    return with("QITS_ARTIFACTS_URL", store.url());
  }

  Run run(String... argv) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code;
    try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
      code = new Cli(new Env(Map.copyOf(env)), outStream, errStream).run(argv);
    }
    return new Run(
        code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }
}
