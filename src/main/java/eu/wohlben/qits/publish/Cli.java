package eu.wohlben.qits.publish;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * argv in, exit code out — the whole program, as a function.
 *
 * <p>Nothing here calls {@code System.exit} and nothing reads {@code System.getenv}: {@link Main}
 * supplies both and this class is what the suite drives. That is the same discipline qits-ci-daemon
 * holds itself to for the same reason — a flow that is constructible by hand is a flow a test can run
 * against a real server without a container.
 */
final class Cli {

  private static final String USAGE =
      """
      qits-publish — the platform's publish client for qits-artifacts.

        qits-publish sbom submit --type <npm|maven|docker|daemon> --name <n> --version <v> --file <path>
        qits-publish sbom from-dockerfile --root-name <n> --root-version <v> \\
                          [--dockerfile <path>]... [--build-arg NAME=value]... -o <out.json>
        qits-publish docs submit --site <name> --version <v> --archive <tgz> [--meta key=value]...
        qits-publish daemon submit --name <n> --version <v> --file <bin>
        qits-publish exists <daemon|docs|npm|sbom> <name> <version>
        qits-publish npm plan --package <n> --version <v>
        qits-publish npm dist-tag --package <n> --version <v> --tag <t>
        qits-publish npm rewrite-lockfile-origin [--lockfile package-lock.json]

      Environment:
        QITS_ARTIFACTS_URL      the artifacts store's origin (required by everything that talks to it)
        QITS_DOCS_URL           the docs root including its `docs` repository segment; derived when unset
        QITS_NPM_REGISTRY_URL   the hosted npm registry (the @qits scope)
        QITS_NPM_PROXY_URL      the npmjs pull-through cache

      Exit codes: 0 published or verified identical · 1 refused (bad arguments, 4xx, different bytes;
      `exists` also uses 1 for absent) · 2 could not ask (no store configured, I/O failure, 5xx).
      """;

  private final Env env;
  private final Console console;
  private final Http http;

  Cli(Env env, PrintStream out, PrintStream err) {
    this(env, out, err, new Http());
  }

  Cli(Env env, PrintStream out, PrintStream err, Http http) {
    this.env = env;
    this.console = new Console(out, err);
    this.http = http;
  }

  int run(String... argv) {
    try {
      return dispatch(new ArrayList<>(List.of(argv)));
    } catch (CliException e) {
      console.error(e.getMessage());
      return e.code();
    }
  }

  private int dispatch(List<String> argv) {
    if (argv.isEmpty()) {
      // Usage nobody asked for is a diagnostic, so it goes where diagnostics go.
      console.info(USAGE);
      return ExitCode.POLICY;
    }
    if (argv.get(0).equals("--help") || argv.get(0).equals("-h")) {
      // Usage somebody asked for is the answer to the question they asked, so it goes to stdout —
      // the one exception to `Console`'s rule, and the one every reader expects.
      console.answer(USAGE);
      return ExitCode.OK;
    }
    String group = argv.remove(0);
    return switch (group) {
      case "sbom" -> sbom(argv);
      case "docs" -> docs(argv);
      case "daemon" -> daemon(argv);
      case "npm" -> npm(argv);
      case "exists" -> exists(argv);
      default -> throw CliException.policy(
          "unknown command '" + group + "'; run qits-publish --help");
    };
  }

  // --- sbom --------------------------------------------------------------------------------------

  private int sbom(List<String> argv) {
    String verb = verb(argv, "sbom", "submit", "from-dockerfile");
    Args args = new Args(argv);
    return switch (verb) {
      case "submit" -> {
        String type = Store.normalizeType(args.required("--type"));
        String name = args.required("--name");
        String version = args.required("--version");
        Path file = Path.of(args.required("--file"));
        args.done(0);
        yield publisher().sbomSubmit(type, name, version, file);
      }
      case "from-dockerfile" -> {
        String rootName = args.required("--root-name");
        String rootVersion = args.required("--root-version");
        List<String> dockerfiles = args.all("--dockerfile");
        Map<String, String> buildArgs = DockerfileSbom.buildArgs(args.all("--build-arg"));
        List<String> outputs = args.all("-o", "--output");
        args.done(0);
        if (outputs.size() != 1) {
          throw CliException.policy(
              "-o is required exactly once — name the document to write");
        }
        String output = outputs.get(0);
        // No --dockerfile at all means the one every build defaults to, so the common case is
        // `--root-name X --root-version $QITS_VERSION -o sbom.json` and nothing more.
        List<Path> files = new ArrayList<>();
        for (String dockerfile : dockerfiles.isEmpty() ? List.of("Dockerfile") : dockerfiles) {
          files.add(Path.of(dockerfile));
        }
        List<DockerfileSbom.Base> bases = DockerfileSbom.bases(files, buildArgs);
        write(Path.of(output), DockerfileSbom.document(rootName, rootVersion, bases));
        console.info(
            "wrote "
                + output
                + ": "
                + bases.size()
                + " base image(s) from "
                + files.size()
                + " Dockerfile(s)");
        yield ExitCode.OK;
      }
      default -> throw unreachable(verb);
    };
  }

  // --- docs --------------------------------------------------------------------------------------

  private int docs(List<String> argv) {
    verb(argv, "docs", "submit");
    Args args = new Args(argv);
    String site = args.required("--site");
    String version = args.required("--version");
    Path archive = Path.of(args.required("--archive"));
    List<String> meta = args.all("--meta");
    args.done(0);
    return publisher().docsSubmit(site, version, archive, meta);
  }

  // --- daemon ------------------------------------------------------------------------------------

  private int daemon(List<String> argv) {
    verb(argv, "daemon", "submit");
    Args args = new Args(argv);
    String name = args.required("--name");
    String version = args.required("--version");
    Path file = Path.of(args.required("--file"));
    args.done(0);
    return publisher().daemonSubmit(name, version, file);
  }

  // --- npm ---------------------------------------------------------------------------------------

  private int npm(List<String> argv) {
    String verb = verb(argv, "npm", "plan", "dist-tag", "rewrite-lockfile-origin");
    Args args = new Args(argv);
    Npm npm = new Npm(http, store(), console);
    return switch (verb) {
      case "plan" -> {
        String packageName = args.required("--package");
        String version = args.required("--version");
        args.done(0);
        yield npm.plan(packageName, version);
      }
      case "dist-tag" -> {
        String packageName = args.required("--package");
        String version = args.required("--version");
        String tag = args.required("--tag");
        args.done(0);
        yield npm.distTag(packageName, version, tag);
      }
      case "rewrite-lockfile-origin" -> {
        String lockfile = args.optional("--lockfile", "package-lock.json");
        args.done(0);
        yield npm.rewriteLockfileOrigin(Path.of(lockfile));
      }
      default -> throw unreachable(verb);
    };
  }

  // --- exists ------------------------------------------------------------------------------------

  private int exists(List<String> argv) {
    Args args = new Args(argv);
    String type = Store.normalizeType(args.positional(0, "a type: daemon, docs, npm or sbom"));
    String name = args.positional(1, "a name");
    String version = args.positional(2, "a version");
    args.done(3);
    return publisher().exists(type, name, version);
  }

  // --- plumbing ----------------------------------------------------------------------------------

  private Publisher publisher() {
    return new Publisher(http, store(), console);
  }

  private Store store() {
    return Store.from(env, console);
  }

  /** The subcommand, refused by name rather than by a usage dump nobody reads. */
  private static String verb(List<String> argv, String group, String... allowed) {
    if (argv.isEmpty()) {
      throw CliException.policy(group + " needs a subcommand: " + String.join(", ", allowed));
    }
    String verb = argv.remove(0);
    for (String candidate : allowed) {
      if (candidate.equals(verb)) {
        return verb;
      }
    }
    throw CliException.policy(
        "unknown " + group + " subcommand '" + verb + "'; expected " + String.join(", ", allowed));
  }

  private static void write(Path path, String content) {
    try {
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw CliException.transport("cannot write " + path + ": " + e.getMessage(), e);
    }
  }

  private static IllegalStateException unreachable(String verb) {
    return new IllegalStateException("verb() admitted '" + verb + "' and the switch does not handle it");
  }
}
