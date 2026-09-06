package eu.wohlben.qits.publish;

import java.util.Locale;
import java.util.Set;

/**
 * Where qits-artifacts is, and how a coordinate becomes a URL.
 *
 * <p><b>No address is spelled in a repository.</b> Every root arrives as environment, and this class
 * is the one place that turns those roots plus a declared coordinate into a path — which is exactly
 * the consolidation the release-slots plan is for: the fleet derived the SBOM base by chopping three
 * different variables three different ways, and the three spellings disagreed about the trailing
 * slash.
 *
 * <h2>The roots</h2>
 *
 * <ul>
 *   <li>{@code QITS_ARTIFACTS_URL} — the store's origin, and the only variable this program wants.
 *       qits-ci injects it into every step container.
 *   <li>{@code QITS_DOCS_URL} — the docs root <em>including</em> its {@code docs} repository
 *       segment, because the docs wire has one and the sbom and daemon wires do not. Derived from
 *       the origin when absent.
 *   <li>{@code QITS_NPM_REGISTRY_URL} / {@code QITS_NPM_PROXY_URL} — the hosted npm registry the
 *       {@code @qits} scope lives in, and the pull-through cache everything else resolves through.
 *       Two services since the byte plane was split, so neither can be derived from the other.
 * </ul>
 *
 * <h2>The one derivation, and why it is still here</h2>
 *
 * <p>A deployment whose qits-ci has not yet been taught {@code QITS_ARTIFACTS_URL} still injects the
 * npm and maven registry roots, and the store is a sibling path inside the same service — which is
 * precisely the {@code ${QITS_NPM_REGISTRY_URL%%/artifacts/*}} chop the fleet hand-wrote thirty
 * times. It survives here, once, behind a WARN: one derivation in one binary is the plan's answer,
 * and deleting it before every deployment carries the variable would make this binary useless in the
 * containers that need it first — including the ones that publish this binary.
 */
final class Store {

  /** The package types the sbom store serves. Its own whitelist, refused client-side first. */
  static final Set<String> SBOM_PACKAGE_TYPES = Set.of("npm", "maven", "docker", "daemon");

  private final String origin;
  private final String docsRoot;
  private final Env env;
  private final Console console;

  private Store(String origin, String docsRoot, Env env, Console console) {
    this.origin = origin;
    this.docsRoot = docsRoot;
    this.env = env;
    this.console = console;
  }

  static Store from(Env env, Console console) {
    String origin = env.get("QITS_ARTIFACTS_URL");
    if (origin == null) {
      origin = derive(env, console);
    }
    String docs = env.get("QITS_DOCS_URL");
    return new Store(trim(origin), trim(docs), env, console);
  }

  private static String derive(Env env, Console console) {
    for (String name : new String[] {"QITS_NPM_REGISTRY_URL", "QITS_MAVEN_REGISTRY_URL"}) {
      String value = env.get(name);
      int at = value == null ? -1 : value.indexOf("/artifacts/");
      if (at > 0) {
        String derived = value.substring(0, at);
        console.warn(
            "QITS_ARTIFACTS_URL is not set; deriving the store root "
                + derived
                + " from "
                + name
                + ". That derivation is a transition shim for a deployment whose qits-ci does not"
                + " inject the variable yet — it lives here once rather than in every pipeline, and"
                + " it goes when every deployment carries it.");
        return derived;
      }
    }
    return null;
  }

  private static String trim(String url) {
    if (url == null) {
      return null;
    }
    String text = url;
    while (text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return text;
  }

  private String origin() {
    if (origin == null) {
      throw CliException.transport(
          "QITS_ARTIFACTS_URL is not set — this command talks to the artifacts store and no address"
              + " could be derived from QITS_NPM_REGISTRY_URL or QITS_MAVEN_REGISTRY_URL either");
    }
    return origin;
  }

  /** {@code /artifacts/sboms/<packageType>/<packageName>/-/<version>}. */
  String sbomDocument(String packageType, String packageName, String version) {
    requirePackageType(packageType);
    return origin()
        + "/artifacts/sboms/"
        + packageType
        + "/"
        + safe("--name", packageName)
        + "/-/"
        + version(version);
  }

  /** {@code /artifacts/daemons/<name>/<version>}. The daemon wire has no repository segment. */
  String daemonBinary(String name, String version) {
    return origin() + "/artifacts/daemons/" + safe("--name", name) + "/" + version(version);
  }

  /**
   * {@code <docs root>/<site>/-/<version>}. The docs wire <em>does</em> carry a repository segment
   * ({@code docs}), which is why {@code QITS_DOCS_URL} is a root of its own rather than a suffix.
   */
  String docsBundle(String site, String version) {
    String root = docsRoot != null ? docsRoot : origin() + "/artifacts/docs/docs";
    return root + "/" + safe("--site", site) + "/-/" + version(version);
  }

  /** The hosted npm registry root — {@code QITS_NPM_REGISTRY_URL}, which carries its own path. */
  String npmRegistry() {
    return trim(
        env.require(
            "QITS_NPM_REGISTRY_URL",
            "the hosted npm registry is where an @qits package is published and read"));
  }

  String npmProxy() {
    return trim(
        env.require(
            "QITS_NPM_PROXY_URL",
            "the npmjs pull-through cache is where every other package is resolved"));
  }

  /** The packument, which is how "is this version published" and "what is latest" are both asked. */
  String npmPackument(String packageName) {
    return npmRegistry() + "/" + safe("--package", packageName);
  }

  /**
   * {@code <registry>/-/package/<pkg>/dist-tags/<tag>}. Note the order: the repository segment is
   * already inside {@code QITS_NPM_REGISTRY_URL}, and the {@code /-/} namespace comes after it.
   */
  String npmDistTag(String packageName, String tag) {
    return npmRegistry() + "/-/package/" + safe("--package", packageName) + "/dist-tags/" + safe("--tag", tag);
  }

  private static void requirePackageType(String packageType) {
    if (!SBOM_PACKAGE_TYPES.contains(packageType)) {
      throw CliException.policy(
          "unknown package type '"
              + packageType
              + "'; the sbom store serves "
              + String.join(", ", SBOM_PACKAGE_TYPES.stream().sorted().toList()));
    }
  }

  /**
   * A value on its way into a URL path, checked against the store's own charset.
   *
   * <p>Everything here arrives from a repository's pipeline configuration, which is not this
   * program's to trust: a name carrying {@code ..} or a {@code ?} would address a coordinate nobody
   * declared. The guard is the same one the shell it replaces spelled as a {@code case} pattern, and
   * it is cheap to keep for exactly the reason that one was.
   */
  private static String safe(String what, String value) {
    if (value == null || value.isEmpty()) {
      throw CliException.policy(what + " is required");
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '.'
              || c == '_'
              || c == '~'
              || c == '-'
              || c == '+'
              || c == ':'
              || c == '@'
              || c == '/';
      if (!ok) {
        throw CliException.policy(
            what + " '" + value + "' carries '" + c + "', which is not a coordinate character");
      }
    }
    if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) {
      throw CliException.policy(what + " '" + value + "' has an empty path segment");
    }
    for (String segment : value.split("/")) {
      if (segment.equals(".") || segment.equals("..")) {
        throw CliException.policy(what + " '" + value + "' traverses");
      }
    }
    return value;
  }

  /** A version, which is one segment and never a path. */
  private static String version(String value) {
    String checked = safe("--version", value);
    if (checked.contains("/")) {
      throw CliException.policy("--version '" + value + "' is not a single path segment");
    }
    return checked;
  }

  /** Lower-cased once, so a `--type DOCKER` fails the whitelist rather than the route. */
  static String normalizeType(String type) {
    return type == null ? null : type.toLowerCase(Locale.ROOT);
  }
}
