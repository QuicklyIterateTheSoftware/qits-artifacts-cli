package eu.wohlben.qits.publish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A CycloneDX 1.6 document for an image, built from the {@code FROM} lines of the Dockerfiles that
 * produce it.
 *
 * <p><b>The SBOM for an image like these is its {@code FROM} line, and there is nothing else it
 * honestly could be.</b> The OCI repositories on this platform layer configuration and a few {@code
 * apk} packages onto an upstream image and declare no manifest a scanner could read; what a consumer
 * needs to know is which upstream is inherited and at which tag, and that is exactly one {@code FROM}
 * per stage.
 *
 * <p>This replaces about a hundred lines of {@code sed}, {@code case} and {@code printf} that lived
 * twice — once in qits-database-oci's release pipeline and once in qits-build-images-oci's, the
 * second with the loop the first had collapsed. Those two copies were written against {@code
 * docker:28-dind}, an upstream image with no {@code jq}, no python and no {@code curl}, which is why
 * they were shell at all. That constraint belongs to the image the pipeline runs in, and this binary
 * is static and needs nothing from it — so the constraint is gone and the parser can be a parser.
 *
 * <p><b>Not buildkit attestations, and not syft.</b> Either would produce a richer document and both
 * would change the build's inputs, which would break the rule that the QA build and the release build
 * are byte-identical LLB and therefore that a release following a green fold is a stack of cache
 * hits. Reading the Dockerfile costs the build nothing.
 *
 * <h2>What is kept from the shell, deliberately</h2>
 *
 * <ul>
 *   <li><b>Stage aliases are not upstream images.</b> A multi-stage file names its own earlier stages
 *       in later {@code FROM}s; those are excluded.
 *   <li><b>Distinct bases only.</b> Two stages built from one upstream are one dependency.
 *   <li><b>A digest pins harder than a tag, so the digest is the version.</b>
 *   <li><b>The colon is not proof of a tag</b> — a registry host may carry a port, and in {@code
 *       host:5000/image} the colon is the port's. An untagged {@code FROM} means {@code latest} by
 *       definition.
 *   <li><b>The charset guard.</b> A reference reaches a JSON document and a purl, and it comes out of
 *       a repository's own tree, so it is checked against the registry's charset and nothing else.
 * </ul>
 *
 * <h2>What is new</h2>
 *
 * <p><b>{@code ARG} in a {@code FROM} resolves instead of failing.</b> The shell's charset guard
 * refused {@code FROM ${BUILDER_IMAGE}} outright — the braces are not registry characters — so
 * qits-ci-daemon's own Dockerfile could not be described by it at all. Here the file's own {@code
 * ARG} defaults are substituted, {@code --build-arg} overrides them exactly as the build's do, and a
 * reference that is still unresolved after that is a refusal naming the variable. Emitting a
 * component whose version reads {@code ${BUILDER_IMAGE}} would be a document that lies.
 */
final class DockerfileSbom {

  private static final Pattern FROM_LINE = Pattern.compile("^\\s*FROM\\s+(.*)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ARG_LINE =
      Pattern.compile("^\\s*ARG\\s+([A-Za-z_][A-Za-z0-9_]*)(?:=(.*))?$", Pattern.CASE_INSENSITIVE);

  private DockerfileSbom() {}

  /** One upstream image, as it will appear in the document. */
  record Base(String reference, String name, String version) {

    String purl() {
      return "pkg:docker/" + name + "@" + version;
    }

    String component() {
      String purl = purl();
      return "{\"type\":\"container\",\"bom-ref\":"
          + Json.quote(purl)
          + ",\"name\":"
          + Json.quote(name)
          + ",\"version\":"
          + Json.quote(version)
          + ",\"purl\":"
          + Json.quote(purl)
          + "}";
    }
  }

  /**
   * The document, ready to be written and PUT.
   *
   * <p>The root is the <b>declared</b> artifact identity, unqualified — a repository states no
   * deployment's addresses, and {@code (packageType, packageName, version)} is the coordinate a
   * {@code SoftwareRelease} consumer asks for. The components keep the references the build really
   * resolved, registry host and all, because that is what the image is actually made of.
   */
  static String document(
      String rootName, String rootVersion, List<Path> dockerfiles, Map<String, String> buildArgs) {
    return document(rootName, rootVersion, bases(dockerfiles, buildArgs));
  }

  /** The same document from bases already read, so a caller that wants to count them reads once. */
  static String document(String rootName, String rootVersion, List<Base> bases) {
    String root = "pkg:docker/" + guard("--root-name", rootName) + "@" + guard("--root-version", rootVersion);

    StringBuilder components = new StringBuilder();
    StringBuilder dependsOn = new StringBuilder();
    for (Base base : bases) {
      if (!components.isEmpty()) {
        components.append(',');
        dependsOn.append(',');
      }
      components.append(base.component());
      dependsOn.append(Json.quote(base.purl()));
    }

    return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
        + "\"metadata\":{\"component\":{\"type\":\"container\",\"bom-ref\":"
        + Json.quote(root)
        + ",\"name\":"
        + Json.quote(rootName)
        + ",\"version\":"
        + Json.quote(rootVersion)
        + ",\"purl\":"
        + Json.quote(root)
        + "}},\"components\":["
        + components
        + "],\"dependencies\":[{\"ref\":"
        + Json.quote(root)
        + ",\"dependsOn\":["
        + dependsOn
        + "]}]}\n";
  }

  /**
   * Every distinct upstream across the named files, in the order they are declared — never empty.
   *
   * <p>A file set that names no upstream at all is a refusal here rather than at each caller: a
   * document with no components would describe an image made of nothing, and the shell this replaces
   * stopped at the same point for the same reason.
   */
  static List<Base> bases(List<Path> dockerfiles, Map<String, String> buildArgs) {
    Set<String> seen = new LinkedHashSet<>();
    List<Base> bases = new ArrayList<>();
    for (Path dockerfile : dockerfiles) {
      for (String reference : references(dockerfile, buildArgs)) {
        if (seen.add(reference)) {
          bases.add(split(reference));
        }
      }
    }
    if (bases.isEmpty()) {
      throw CliException.policy(
          dockerfiles.size() == 1
              ? dockerfiles.get(0) + " declares no base image"
              : "none of " + dockerfiles + " declares a base image");
    }
    return bases;
  }

  /**
   * The upstream references one Dockerfile names: every {@code FROM} that is not one of this file's
   * own stages, with variables resolved and flags dropped.
   *
   * <p>Stage aliases are collected from the whole file before any {@code FROM} is judged, which is
   * what the shell did — and scoped to the file rather than shared across files, so an alias in one
   * Dockerfile cannot silently swallow a real image named the same in another.
   */
  private static List<String> references(Path dockerfile, Map<String, String> buildArgs) {
    List<String> lines = read(dockerfile);
    Set<String> stages = new LinkedHashSet<>();
    for (String line : lines) {
      Matcher from = FROM_LINE.matcher(line);
      if (from.matches()) {
        String[] words = words(from.group(1));
        if (words.length >= 3 && words[words.length - 2].equalsIgnoreCase("AS")) {
          stages.add(words[words.length - 1]);
        }
      }
    }

    Map<String, String> args = new LinkedHashMap<>();
    List<String> references = new ArrayList<>();
    for (String line : lines) {
      Matcher arg = ARG_LINE.matcher(line);
      if (arg.matches()) {
        String name = arg.group(1);
        String value = arg.group(2);
        args.put(name, value == null ? "" : unquote(value.trim()));
        continue;
      }
      Matcher from = FROM_LINE.matcher(line);
      if (!from.matches()) {
        continue;
      }
      String[] words = words(from.group(1));
      if (words.length == 0) {
        throw CliException.policy(dockerfile + ": a FROM line names no image");
      }
      String reference = expand(words[0], args, buildArgs, dockerfile, line);
      if (stages.contains(reference)) {
        continue; // this file's own earlier stage; not an upstream image
      }
      if (reference.equals("scratch")) {
        // The empty base. It resolves to no image, has no digest and cannot be pulled, so a
        // `pkg:docker/scratch@latest` component would be a dependency on nothing. Neither OCI
        // repository's Dockerfile uses it, so the shell this replaces never met the case — but the
        // daemon-shaped builds do, twice per file, and a document naming it would be wrong.
        continue;
      }
      guardReference(dockerfile, reference);
      references.add(reference);
    }
    return references;
  }

  /**
   * A {@code FROM}'s arguments as words, with {@code --platform=…}-style flags dropped wherever they
   * appear — the same thing the shell's global substitution did, and the reason the first remaining
   * word is the reference.
   */
  private static String[] words(String rest) {
    List<String> words = new ArrayList<>();
    for (String word : rest.trim().split("\\s+")) {
      if (word.isEmpty() || word.startsWith("--")) {
        continue;
      }
      words.add(word);
    }
    return words.toArray(new String[0]);
  }

  /**
   * {@code $NAME}, {@code ${NAME}} and {@code ${NAME:-default}} against {@code --build-arg} first and
   * the file's own {@code ARG} defaults second — which is the precedence a real build has.
   */
  private static String expand(
      String reference,
      Map<String, String> args,
      Map<String, String> buildArgs,
      Path dockerfile,
      String line) {
    StringBuilder out = new StringBuilder();
    int i = 0;
    while (i < reference.length()) {
      char c = reference.charAt(i);
      if (c != '$') {
        out.append(c);
        i++;
        continue;
      }
      if (i + 1 >= reference.length()) {
        out.append(c);
        break;
      }
      String name;
      String fallback = null;
      if (reference.charAt(i + 1) == '{') {
        int close = reference.indexOf('}', i + 2);
        if (close < 0) {
          throw CliException.policy(
              dockerfile + ": unterminated ${…} in FROM: " + line.trim());
        }
        String body = reference.substring(i + 2, close);
        int dash = body.indexOf(":-");
        if (dash >= 0) {
          name = body.substring(0, dash);
          fallback = body.substring(dash + 2);
        } else if ((dash = body.indexOf('-')) > 0) {
          name = body.substring(0, dash);
          fallback = body.substring(dash + 1);
        } else {
          name = body;
        }
        i = close + 1;
      } else {
        int end = i + 1;
        while (end < reference.length() && (Character.isLetterOrDigit(reference.charAt(end)) || reference.charAt(end) == '_')) {
          end++;
        }
        name = reference.substring(i + 1, end);
        i = end;
      }
      String value = buildArgs.get(name);
      if (value == null || value.isEmpty()) {
        value = args.get(name);
      }
      if (value == null || value.isEmpty()) {
        value = fallback;
      }
      if (value == null) {
        throw CliException.policy(
            dockerfile
                + ": FROM names $"
                + name
                + ", which no ARG in the file defaults and no --build-arg supplies — pass"
                + " --build-arg "
                + name
                + "=<value>. Line: "
                + line.trim());
      }
      out.append(value);
    }
    return out.toString();
  }

  private static String unquote(String value) {
    if (value.length() >= 2
        && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }

  /** {@code name:tag}, {@code name@sha256:…}, or a bare name meaning {@code latest}. */
  static Base split(String reference) {
    int at = reference.indexOf('@');
    if (at > 0) {
      // A digest ref pins harder than a tag, so the digest IS the version.
      return new Base(reference, reference.substring(0, at), reference.substring(at + 1));
    }
    int colon = reference.lastIndexOf(':');
    if (colon > 0) {
      String candidate = reference.substring(colon + 1);
      // The colon is not proof of a tag: in `host:5000/image` it belongs to the port, and a tag
      // never contains a slash.
      if (!candidate.isEmpty() && candidate.indexOf('/') < 0) {
        return new Base(reference, reference.substring(0, colon), candidate);
      }
    }
    return new Base(reference, reference, "latest");
  }

  private static void guardReference(Path dockerfile, String reference) {
    if (reference.isEmpty()) {
      throw CliException.policy(dockerfile + ": unusable FROM ''");
    }
    for (int i = 0; i < reference.length(); i++) {
      if (!isReferenceCharacter(reference.charAt(i))) {
        throw CliException.policy(dockerfile + ": unusable FROM '" + reference + "'");
      }
    }
  }

  private static String guard(String what, String value) {
    if (value == null || value.isEmpty()) {
      throw CliException.policy(what + " is required");
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isReferenceCharacter(value.charAt(i))) {
        throw CliException.policy(
            what + " '" + value + "' carries '" + value.charAt(i) + "', which is not a reference character");
      }
    }
    return value;
  }

  private static boolean isReferenceCharacter(char c) {
    char lower = Character.toLowerCase(c);
    return (lower >= 'a' && lower <= 'z')
        || (c >= '0' && c <= '9')
        || c == '.'
        || c == '_'
        || c == ':'
        || c == '/'
        || c == '@'
        || c == '-';
  }

  private static List<String> read(Path dockerfile) {
    try {
      return Files.readAllLines(dockerfile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw CliException.policy("cannot read " + dockerfile + ": " + e.getMessage());
    }
  }

  /** {@code --build-arg NAME=value} pairs, as a map. */
  static Map<String, String> buildArgs(List<String> pairs) {
    Map<String, String> args = new LinkedHashMap<>();
    for (String pair : pairs) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        throw CliException.policy("--build-arg must be written NAME=value, not '" + pair + "'");
      }
      args.put(pair.substring(0, eq), pair.substring(eq + 1));
    }
    return args;
  }
}
