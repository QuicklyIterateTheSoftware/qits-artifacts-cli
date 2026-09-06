package eu.wohlben.qits.publish;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three publishes and the existence probe — and, in one place, <b>the one idempotency policy</b>
 * this repository exists to hold.
 *
 * <h2>The policy</h2>
 *
 * <p><b>Publish if absent, verify if occupied, and never let a coordinate come to mean different
 * bytes.</b> Concretely, for every publish below:
 *
 * <ol>
 *   <li><b>Absent</b> — PUT, and say what landed.
 *   <li><b>Occupied with the same bytes</b> — success, with a line saying so. A release run
 *       re-fires, a rebootstrap replays a tag, a step is retried after a later failure: all three
 *       must go green, because the coordinate holds exactly what this run was asked to put there.
 *   <li><b>Occupied with different bytes</b> — hard failure naming both digests. This is the rule the
 *       whole policy is for. Publishing is allowed to add a coordinate and never to redefine one, so
 *       a run that would need to redefine one has found a real disagreement — two builds of one
 *       version — and the only honest thing to do is stop and show the two digests.
 *   <li><b>Occupied and the bytes cannot be compared</b> — skip, with a WARN naming the degradation.
 *       Exactly one surface is in this state today (docs: the store explodes a bundle into per-file
 *       blobs and keeps no archive digest), and the WARN is what stops that being mistaken for case
 *       2.
 * </ol>
 *
 * <p>Anything else — a 4xx that is not the store's "already there", a 5xx, an unreadable body — fails
 * with the status and an excerpt of what came back. A 4xx is {@link ExitCode#POLICY} because the
 * request was wrong; a 5xx is {@link ExitCode#TRANSPORT} because the question was fine and the answer
 * was not.
 *
 * <h2>Why the three surfaces need three shapes</h2>
 *
 * <p>The store answers an occupied coordinate three different ways on purpose, and the policy is the
 * same across all three because it is expressed here rather than in each caller:
 *
 * <ul>
 *   <li><b>sboms</b> answer <b>200</b> with {@code alreadyPublished} and the <em>stored</em> digest.
 *       One request settles it; no probe is needed.
 *   <li><b>daemons</b> answer <b>409</b>. A HEAD then supplies {@code Docker-Content-Digest}, and the
 *       comparison happens on that.
 *   <li><b>docs</b> answer <b>409</b> and expose no bundle digest at all, at any route. That is case
 *       4.
 * </ul>
 */
final class Publisher {

  private final Http http;
  private final Store store;
  private final Console console;

  Publisher(Http http, Store store, Console console) {
    this.http = http;
    this.store = store;
    this.console = console;
  }

  // --- sbom submit -------------------------------------------------------------------------------

  /**
   * PUT a CycloneDX document at {@code (packageType, packageName, version)}.
   *
   * <p>The sbom store is first-write-wins by design — the PUT sits inside a replayable release step —
   * so an occupied coordinate answers 200 rather than 409, describing the row that is already there.
   * That makes this the cheapest verification on the platform: the response to the PUT we were
   * making anyway carries the stored digest, so case 2 and case 3 are settled without a second
   * request.
   */
  int sbomSubmit(String packageType, String packageName, String version, Path file) {
    String url = store.sbomDocument(packageType, packageName, version);
    String local = Sha256.ofFile(file);
    String coordinate = packageType + " " + packageName + "@" + version;

    Http.Response response =
        http.putFile(url, file, "application/vnd.cyclonedx+json", Map.of());
    switch (response.status()) {
      case 201 -> {
        Map<String, Object> body = Json.parseObject(response.body(), "the sbom publish receipt");
        console.info(
            "published the sbom for "
                + coordinate
                + " ("
                + orUnknown(Json.number(body, "sizeBytes"))
                + " bytes, "
                + orUnknown(Json.string(body, "digest"))
                + ")");
        return ExitCode.OK;
      }
      case 200 -> {
        Map<String, Object> body = Json.parseObject(response.body(), "the sbom publish receipt");
        String stored = Sha256.normalize(Json.string(body, "digest"));
        return verified(coordinate, "the sbom for " + coordinate, local, stored);
      }
      default -> throw refusal("the sbom publish for " + coordinate, url, response);
    }
  }

  // --- daemon submit -----------------------------------------------------------------------------

  /**
   * PUT a daemon binary at {@code (name, version)}.
   *
   * <p>Daemon versions are immutable and a re-publish is a 409 <em>even for identical bytes</em>, so
   * this is the surface where the fleet's contradiction lived: qits-ci-daemon's pipeline treats 409
   * as a hard failure ("re-running this publish is a lie") while the maven and npm steps beside it
   * treat their equivalent as a skip. Both were right about their own case and neither could say why.
   * The digest settles it: a 409 whose stored bytes are the ones in hand is a re-fire of a run that
   * already succeeded, and a 409 whose stored bytes are different is two builds claiming one version.
   */
  int daemonSubmit(String name, String version, Path file) {
    String url = store.daemonBinary(name, version);
    String local = Sha256.ofFile(file);
    String coordinate = name + "@" + version;

    Http.Response response =
        http.putFile(url, file, "application/octet-stream", Map.of());
    switch (response.status()) {
      case 201 -> {
        Map<String, Object> body = Json.parseObject(response.body(), "the daemon publish receipt");
        console.info(
            "published the daemon binary "
                + coordinate
                + " ("
                + orUnknown(Json.number(body, "sizeBytes"))
                + " bytes, "
                + orUnknown(Json.string(body, "digest"))
                + ")");
        return ExitCode.OK;
      }
      case 409 -> {
        // The 409 body names the stored digest in prose. Read it from a HEAD instead: a header is a
        // contract and a sentence is not, and the store may reword the sentence any day.
        Http.Response head = http.head(url);
        if (head.status() != 200) {
          throw CliException.transport(
              "the daemon binary "
                  + coordinate
                  + " is already published, but HEAD "
                  + url
                  + " answered "
                  + head.status()
                  + " so the stored bytes could not be compared with the ones in hand");
        }
        String stored = storedDigestOf(head);
        return verified(coordinate, "the daemon binary " + coordinate, local, stored);
      }
      default -> throw refusal("the daemon publish for " + coordinate, url, response);
    }
  }

  // --- docs submit -------------------------------------------------------------------------------

  /**
   * PUT a documentation bundle at {@code (site, version)} with its metadata headers.
   *
   * <p><b>This is the degraded case, and it is degraded at the store rather than here.</b> A docs PUT
   * is a gzipped tar that the store explodes into per-file blobs and then discards — no archive is
   * kept, so there is no bundle digest to compare against at any route, and even a byte-identical
   * {@code .tar.gz} would have nothing to be identical to. The policy's fourth case therefore
   * applies: probe, skip, and WARN naming the degradation, with the stored version's shape printed
   * beside it so a human reading the log can see what is actually there.
   *
   * <p>Verifying properly would mean reading {@code files[]} and issuing one HEAD per file to compare
   * per-file ETags. That is a real option the day it is worth its request count; it is not worth it
   * for a bundle that is usually one OpenAPI document, and doing it half-way — comparing file counts
   * and calling that verification — would be worse than saying plainly that nothing was compared.
   */
  int docsSubmit(String site, String version, Path archive, List<String> meta) {
    String url = store.docsBundle(site, version);
    String coordinate = site + "@" + version;
    Map<String, String> headers = metadataHeaders(meta);

    // No Content-Type: the docs route does not inspect one, and the fleet's curl invocations send
    // none. Sending a wrong one would be worse than sending nothing.
    Http.Response response = http.putFile(url, archive, null, headers);
    switch (response.status()) {
      case 201 -> {
        Map<String, Object> body = Json.parseObject(response.body(), "the docs publish receipt");
        console.info(
            "published the docs bundle "
                + coordinate
                + " ("
                + orUnknown(Json.number(body, "fileCount"))
                + " files, "
                + orUnknown(Json.number(body, "totalBytes"))
                + " bytes)");
        return ExitCode.OK;
      }
      case 409 -> {
        console.warn(
            "the docs bundle "
                + coordinate
                + " is already published and was NOT verified: the docs store explodes a bundle into"
                + " per-file blobs and keeps no archive digest, so these bytes cannot be compared"
                + " with what is stored. Probing and skipping.");
        Http.Response probe = http.get(url);
        if (probe.status() == 200) {
          Map<String, Object> body = Json.parseObject(probe.body(), "the docs bundle description");
          console.info(
              "the stored "
                  + coordinate
                  + " has "
                  + orUnknown(Json.number(body, "fileCount"))
                  + " files, "
                  + orUnknown(Json.number(body, "totalBytes"))
                  + " bytes, published at "
                  + orUnknown(Json.string(body, "publishedAt")));
        } else {
          console.warn(
              "and the probe answered " + probe.status() + ", so not even its shape could be read");
        }
        return ExitCode.OK;
      }
      default -> throw refusal("the docs publish for " + coordinate, url, response);
    }
  }

  /**
   * {@code --meta k=v} pairs as {@code X-Artifacts-Meta-<k>} headers, which is the store's literal
   * convention: the key keeps its spelling, dots and all, and the prefix is matched
   * case-insensitively. Refused here rather than at the store when a key or value carries anything a
   * header cannot hold — a CR in a value is header injection, and it is repository-supplied text.
   */
  private static Map<String, String> metadataHeaders(List<String> meta) {
    Map<String, String> headers = new LinkedHashMap<>();
    for (String pair : meta) {
      int eq = pair.indexOf('=');
      if (eq <= 0) {
        throw CliException.policy("--meta must be written key=value, not '" + pair + "'");
      }
      String key = pair.substring(0, eq);
      String value = pair.substring(eq + 1);
      for (int i = 0; i < key.length(); i++) {
        char c = key.charAt(i);
        boolean ok =
            (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '.'
                || c == '-'
                || c == '_';
      if (!ok) {
          throw CliException.policy(
              "--meta key '" + key + "' carries '" + c + "', which a header name cannot hold");
        }
      }
      for (int i = 0; i < value.length(); i++) {
        if (value.charAt(i) < 0x20 || value.charAt(i) == 0x7f) {
          throw CliException.policy("--meta value for '" + key + "' carries a control character");
        }
      }
      if (headers.putIfAbsent("X-Artifacts-Meta-" + key, value) != null) {
        throw CliException.policy("--meta " + key + " was given more than once");
      }
    }
    return headers;
  }

  // --- exists ------------------------------------------------------------------------------------

  /**
   * Is this coordinate published? {@code 0} yes, {@code 1} no, {@code 2} the question could not be
   * answered — which is a third answer on purpose, because a step that treats an unreachable store as
   * "absent" republishes on every outage.
   *
   * <p>{@code <type>} is the artifact type as a pipeline declares it. {@code sbom} is the one that
   * needs two name parts, because an sbom coordinate genuinely has two: write it {@code
   * <packageType>/<packageName>}, e.g. {@code docker/qits/qits-ci}.
   */
  int exists(String type, String name, String version) {
    return switch (type) {
      case "daemon" -> probe(http.head(store.daemonBinary(name, version)), type + " " + name + "@" + version);
      case "docs" -> probe(http.get(store.docsBundle(name, version)), type + " " + name + "@" + version);
      case "sbom" -> {
        int slash = name.indexOf('/');
        if (slash <= 0) {
          throw CliException.policy(
              "an sbom coordinate is <packageType>/<packageName>, e.g. docker/qits/qits-ci — got '"
                  + name
                  + "'");
        }
        String packageType = Store.normalizeType(name.substring(0, slash));
        String packageName = name.substring(slash + 1);
        yield probe(
            http.head(store.sbomDocument(packageType, packageName, version)),
            "the sbom for " + packageType + " " + packageName + "@" + version);
      }
      case "npm" -> {
        Http.Response response = http.get(store.npmPackument(name));
        if (response.status() == 404) {
          console.info("npm " + name + " has no published version at all");
          yield ExitCode.POLICY;
        }
        if (response.status() != 200) {
          throw refusal("the npm packument for " + name, store.npmPackument(name), response);
        }
        Map<String, Object> packument = Json.parseObject(response.body(), "the npm packument");
        Map<String, Object> versions = Json.object(packument, "versions");
        boolean present = versions != null && versions.containsKey(version);
        console.info("npm " + name + "@" + version + (present ? " is published" : " is not published"));
        yield present ? ExitCode.OK : ExitCode.POLICY;
      }
      default -> throw CliException.policy(
          "unknown type '" + type + "'; exists answers for daemon, docs, npm and sbom");
    };
  }

  private int probe(Http.Response response, String what) {
    return switch (response.status()) {
      case 200 -> {
        console.info(what + " is published");
        yield ExitCode.OK;
      }
      case 404 -> {
        console.info(what + " is not published");
        yield ExitCode.POLICY;
      }
      default -> throw CliException.transport(
          "cannot tell whether " + what + " is published: the store answered " + response.status()
              + (response.excerpt().isEmpty() ? "" : " — " + response.excerpt()));
    };
  }

  // --- the shared half of the policy ---------------------------------------------------------------

  /**
   * Case 2, 3 and 4 of the policy, once. {@code stored} is {@code null} when the store answered
   * nothing comparable, which is the degradation the WARN is for.
   */
  private int verified(String coordinate, String what, String local, String stored) {
    if (stored == null) {
      console.warn(
          what
              + " is already published, and the store returned no digest for it — these bytes were"
              + " NOT verified against what is stored. Skipping.");
      return ExitCode.OK;
    }
    if (stored.equals(local)) {
      console.info(what + " is already published with the same bytes (" + stored + ") — verified");
      return ExitCode.OK;
    }
    throw CliException.policy(
        what
            + " is already published with DIFFERENT bytes: the store holds "
            + stored
            + " and this run produced "
            + local
            + ". A coordinate must never come to mean two things, so this publish stops here rather"
            + " than asking again; release a new version, or find out why one version has two"
            + " builds.");
  }

  /** The stored digest from a read, preferring the header that is a contract over the one that is a cache validator. */
  private static String storedDigestOf(Http.Response response) {
    String digest = Sha256.normalize(response.header("Docker-Content-Digest"));
    return digest != null ? digest : Sha256.normalize(response.header("ETag"));
  }

  /** Everything the policy does not have a case for: status, address, and a bounded excerpt. */
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

  private static String orUnknown(String value) {
    return value == null ? "?" : value;
  }
}
