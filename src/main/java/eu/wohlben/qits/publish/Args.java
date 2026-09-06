package eu.wohlben.qits.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The argument parser, deliberately about forty lines of it.
 *
 * <p>Every flag takes a value — there is no boolean flag in this program's surface and none should
 * be added, because {@code --force} is exactly the shape of thing that turns one idempotency policy
 * into two. {@code --flag value} and {@code --flag=value} are both accepted; a flag may repeat and
 * {@link #all} returns every occurrence, which is what {@code --dockerfile} and {@code --meta} need.
 *
 * <p><b>An unknown flag is an error, not an ignored token.</b> A publish invocation is written once
 * and then runs unattended for a year; a typo that silently drops {@code --version} would publish to
 * a coordinate nobody meant. {@link #done()} is what enforces that, and every command calls it.
 */
final class Args {

  private final Map<String, List<String>> flags = new LinkedHashMap<>();
  private final List<String> positionals = new ArrayList<>();
  private final Set<String> read = new LinkedHashSet<>();

  Args(List<String> argv) {
    for (int i = 0; i < argv.size(); i++) {
      String token = argv.get(i);
      if (!token.startsWith("-") || token.equals("-")) {
        positionals.add(token);
        continue;
      }
      String name = token;
      String value = null;
      int eq = token.indexOf('=');
      if (eq > 0) {
        name = token.substring(0, eq);
        value = token.substring(eq + 1);
      } else {
        if (i + 1 >= argv.size()) {
          throw CliException.policy(token + " needs a value");
        }
        value = argv.get(++i);
      }
      flags.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
    }
  }

  /** The value of a flag that must be there, refusing an empty one as loudly as an absent one. */
  String required(String name) {
    String value = optional(name, null);
    if (value == null || value.isEmpty()) {
      throw CliException.policy(name + " is required");
    }
    return value;
  }

  /** The value of a flag, or {@code fallback}. Refuses a flag given twice with different values. */
  String optional(String name, String fallback) {
    List<String> values = all(name);
    if (values.isEmpty()) {
      return fallback;
    }
    if (values.size() > 1) {
      throw CliException.policy(name + " was given more than once");
    }
    return values.get(0);
  }

  /**
   * Every occurrence of a repeatable flag, in the order they were written. {@code --dockerfile}
   * relies on that order: a multi-file document lists its components in the order the files were
   * named, so a regenerated document diffs empty.
   */
  List<String> all(String... names) {
    List<String> values = new ArrayList<>();
    for (String name : names) {
      read.add(name);
      values.addAll(flags.getOrDefault(name, List.of()));
    }
    return values;
  }

  /** The n-th bare token, or a refusal naming what was expected there. */
  String positional(int index, String what) {
    if (index >= positionals.size()) {
      throw CliException.policy("expected " + what);
    }
    return positionals.get(index);
  }

  List<String> positionals() {
    return List.copyOf(positionals);
  }

  /**
   * Refuses anything the command did not ask for. Called last, so the flags a command reads are the
   * flags it accepts and the two can never drift.
   */
  void done(int expectedPositionals) {
    for (String name : flags.keySet()) {
      if (!read.contains(name)) {
        throw CliException.policy("unknown option " + name);
      }
    }
    if (positionals.size() > expectedPositionals) {
      throw CliException.policy(
          "unexpected argument " + positionals.get(expectedPositionals));
    }
  }
}
