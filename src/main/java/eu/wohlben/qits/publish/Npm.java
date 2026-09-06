package eu.wohlben.qits.publish;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The npm half: what to do before publishing, what to do after it, and the lockfile edit every
 * frontend repository copied.
 *
 * <p>Nothing here runs {@code npm}. {@code npm publish} stays in the archetype's script, where the
 * ecosystem tool belongs; what moves in here is the reasoning around it, which is the part that was
 * duplicated and the part that disagreed with itself.
 */
final class Npm {

  private final Http http;
  private final Store store;
  private final Console console;

  Npm(Http http, Store store, Console console) {
    this.http = http;
    this.store = store;
    this.console = console;
  }

  // --- plan --------------------------------------------------------------------------------------

  /**
   * Decide, in one word on stdout, what a release step should do with {@code package@version}:
   *
   * <ul>
   *   <li>{@code skip} — the registry already holds this exact version. Versions are immutable, so
   *       publishing again would be refused; a re-fired run must go green rather than fight.
   *   <li>{@code publish-replay} — this version is <em>below</em> the registry's {@code latest}. A
   *       bootstrap replays every version the estate still pins onto a registry that may already hold
   *       a newer one, and npm refuses to move {@code latest} backwards. Such a publish takes a
   *       throwaway tag ({@code npm publish --tag replay}) and leaves the real tags alone.
   *   <li>{@code publish} — the ordinary case.
   * </ul>
   *
   * <p>A package with nothing published answers 404 and that is {@code publish}, not a failure — npm
   * reads the same 404 the same way. <b>A transport failure is not that</b>, and this is the one
   * sharpening over the shell it replaces: {@code latest=$(npm view … || true)} turned an unreachable
   * registry into an empty {@code latest}, which reads as "publish", which on a replay would try to
   * claim {@code latest}. Here an unanswerable question exits 2 and the step stops.
   */
  int plan(String packageName, String version) {
    String url = store.npmPackument(packageName);
    Http.Response response = http.get(url);
    if (response.status() == 404) {
      console.info(packageName + " has no published version yet");
      console.answer("publish");
      return ExitCode.OK;
    }
    if (response.status() != 200) {
      throw refusal("the npm packument for " + packageName, url, response);
    }

    Map<String, Object> packument = Json.parseObject(response.body(), "the npm packument");
    Map<String, Object> versions = Json.object(packument, "versions");
    if (versions != null && versions.containsKey(version)) {
      console.info(packageName + "@" + version + " is already published");
      console.answer("skip");
      return ExitCode.OK;
    }

    Map<String, Object> tags = Json.object(packument, "dist-tags");
    String latest = Json.string(tags, "latest");
    VersionOrder order = VersionOrder.compare(version, latest);
    if (order == VersionOrder.BELOW) {
      console.info(
          version
              + " is below latest "
              + latest
              + " — a replay: it takes a throwaway tag and leaves the real ones alone");
      console.answer("publish-replay");
      return ExitCode.OK;
    }
    console.info(
        latest == null
            ? packageName + " has no latest tag; " + version + " is a release"
            : version + " is " + (order == VersionOrder.SAME ? "level with" : "above") + " latest " + latest);
    console.answer("publish");
    return ExitCode.OK;
  }

  // --- dist-tag ----------------------------------------------------------------------------------

  /**
   * Point a dist-tag at a version.
   *
   * <p><b>Always run, never guarded.</b> This is one of the policy's idempotent repairs: moving a tag
   * onto the version it already names costs one request and answers 200, whereas guarding it behind
   * "did the publish happen this time" is what left {@code @qits/ui-components@main} a release behind
   * with nothing saying so. The registry refuses only a <em>backwards</em> move of {@code latest},
   * and that refusal is a real answer worth failing on rather than swallowing.
   *
   * <p>The body is the version as a JSON string — {@code "1.2.3"}, quotes included — which is what
   * {@code npm dist-tag add} sends and what the route reads.
   */
  int distTag(String packageName, String version, String tag) {
    String url = store.npmDistTag(packageName, tag);
    Http.Response response = http.putText(url, Json.quote(version), "application/json", Map.of());
    if (response.status() == 200) {
      console.info("the " + tag + " dist-tag now names " + packageName + "@" + version);
      return ExitCode.OK;
    }
    throw refusal(
        "pointing the " + tag + " dist-tag at " + packageName + "@" + version, url, response);
  }

  // --- rewrite-lockfile-origin -------------------------------------------------------------------

  /** The {@code "resolved": "<url>"} pins npm writes into a lockfile, and nothing else in the file. */
  private static final Pattern RESOLVED =
      Pattern.compile("(\"resolved\"\\s*:\\s*\")(https?://[^\"]*)(\")");

  /**
   * Repoint every {@code resolved} URL in a lockfile at the registries this container can actually
   * reach, keeping the path — and therefore the integrity hash's meaning — exactly as it was.
   *
   * <p>npm pins a full resolved URL per package and the lockfile is generated on a deployment host,
   * so every entry names an address that does not exist in a step container: {@code npm ci} fetches
   * by that URL and never asks the configured registry, so the install dies on connection refused.
   * The paths are identical on every address, so replacing the origin and keeping the path is all it
   * takes, and the integrity hashes are what make that safe — bytes from another address must still
   * hash the same.
   *
   * <p><b>The byte plane is two services, which is why the path decides.</b> qits-platform-mirror
   * holds the npmjs pull-through cache; qits-artifacts hosts the {@code @qits} scope. No single
   * origin serves both, so an entry under the hosted registry's own pathname is an {@code @qits}
   * tarball and gets the hosted origin, and every other entry gets the proxy's. Flattening all of
   * them to one origin sends every {@code @qits} tarball to a mirror that has never held one.
   *
   * <p>This replaces a two-expression {@code sed} whose correctness rested on the order of its
   * {@code -e} flags — the broad swap first, the path-anchored one correcting it after — copied
   * verbatim into forty-five files. Deciding per URL rather than per substitution removes the
   * ordering hazard altogether, and rewriting only the origin inside the matched pin keeps the rest
   * of the file byte-identical, so running this twice is a no-op and a diff shows only what moved.
   */
  int rewriteLockfileOrigin(Path lockfile) {
    String hostedRoot = store.npmRegistry();
    String proxyRoot = store.npmProxy();
    URI hosted = URI.create(hostedRoot);
    URI proxy = URI.create(proxyRoot);
    String hostedOrigin = originOf(hosted, "QITS_NPM_REGISTRY_URL");
    String proxyOrigin = originOf(proxy, "QITS_NPM_PROXY_URL");
    String hostedPath = hosted.getPath() == null ? "" : stripTrailingSlash(hosted.getPath());

    String before;
    try {
      before = Files.readString(lockfile, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw CliException.transport("cannot read " + lockfile + ": " + e.getMessage(), e);
    }

    StringBuilder after = new StringBuilder(before.length());
    Matcher matcher = RESOLVED.matcher(before);
    int rewritten = 0;
    int hostedCount = 0;
    while (matcher.find()) {
      String pin = matcher.group(2);
      String replacement = pin;
      URI parsed = tryParse(pin);
      if (parsed != null && parsed.getPath() != null) {
        boolean isHosted = !hostedPath.isEmpty() && parsed.getPath().startsWith(hostedPath);
        String origin = isHosted ? hostedOrigin : proxyOrigin;
        replacement = origin + parsed.getRawPath() + suffixOf(parsed);
        if (isHosted) {
          hostedCount++;
        }
      }
      if (!replacement.equals(pin)) {
        rewritten++;
      }
      matcher.appendReplacement(
          after, Matcher.quoteReplacement(matcher.group(1) + replacement + matcher.group(3)));
    }
    matcher.appendTail(after);

    String result = after.toString();
    if (result.equals(before)) {
      console.info(lockfile + " already resolves through this deployment's registries — unchanged");
      return ExitCode.OK;
    }
    try {
      Files.writeString(lockfile, result, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw CliException.transport("cannot write " + lockfile + ": " + e.getMessage(), e);
    }
    console.info(
        "repointed "
            + rewritten
            + " resolved URLs in "
            + lockfile
            + " ("
            + hostedCount
            + " to the hosted registry at "
            + hostedOrigin
            + ", the rest to the proxy at "
            + proxyOrigin
            + ")");
    return ExitCode.OK;
  }

  private static String originOf(URI uri, String variable) {
    if (uri.getScheme() == null || uri.getHost() == null) {
      throw CliException.transport(variable + " is not an absolute http(s) address: " + uri);
    }
    return uri.getScheme()
        + "://"
        + uri.getHost()
        + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
  }

  private static String suffixOf(URI uri) {
    String query = uri.getRawQuery();
    String fragment = uri.getRawFragment();
    return (query == null ? "" : "?" + query) + (fragment == null ? "" : "#" + fragment);
  }

  private static String stripTrailingSlash(String path) {
    String text = path;
    while (text.length() > 1 && text.endsWith("/")) {
      text = text.substring(0, text.length() - 1);
    }
    return text.equals("/") ? "" : text;
  }

  private static URI tryParse(String url) {
    try {
      URI uri = new URI(url);
      return uri.getHost() == null ? null : uri;
    } catch (Exception e) {
      return null;
    }
  }

  private static CliException refusal(String what, String url, Http.Response response) {
    String message =
        what
            + " answered HTTP "
            + response.status()
            + " ("
            + url
            + ")"
            + (response.excerpt().isEmpty() ? "" : ": " + response.excerpt());
    return response.status() >= 500
        ? CliException.transport(message)
        : CliException.policy(message);
  }
}
