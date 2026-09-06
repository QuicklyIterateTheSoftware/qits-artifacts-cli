package eu.wohlben.qits.publish;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wire, and the only place in this program that knows there is one.
 *
 * <p>{@code java.net.http}, no client library: the four surfaces this speaks to are plain PUT/GET/HEAD
 * with a body and a couple of headers, and the JDK's client is already in the image.
 *
 * <p><b>No credential is sent, and that is the platform's posture rather than an omission.</b>
 * qits-artifacts' publish surfaces — sboms, docs, daemons, npm — take no credential in either
 * direction, exactly as {@code npm publish}, {@code mvn deploy} and {@code docker push} do here; the
 * store's own suite pins that with the machine-token gate switched on. What keeps it honest is
 * immutability: a publish can add a coordinate and can never redefine one. {@code
 * QITS_COMMISSIONED_CLIENT_ID}/{@code _SECRET} are read by {@link Env} and deliberately not
 * presented here — they are a build-secret pair for resolving dependencies inside an image build,
 * not an HTTP credential. When machine auth arrives it arrives for every surface at once, and it
 * becomes one release of this binary rather than a sweep of forty pipelines. That is the whole
 * reason the HTTP lives here.
 *
 * <p>Every transport failure becomes {@link ExitCode#TRANSPORT}: the caller could not ask, which is
 * a different fact from being refused, and a release step is entitled to retry one and not the
 * other.
 */
final class Http {

  /** How long to wait for a connection. Short: these are same-network calls. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

  /**
   * How long one request may take, body included. Minutes rather than seconds because a docs bundle
   * or a native binary is tens of megabytes and the store hashes it as it stages it — and because
   * the step's own timeout is the real backstop.
   */
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

  /** How much of an error body is quoted back. Enough to read, bounded so a log stays readable. */
  private static final int EXCERPT_LIMIT = 1000;

  private final HttpClient client;

  Http() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
  }

  Http(HttpClient client) {
    this.client = client;
  }

  /** What came back: a status, a body, and the response headers, lower-cased for lookup. */
  record Response(int status, String body, Map<String, String> headers) {

    String header(String name) {
      return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** The body, bounded, for a log line that has to name why a request was refused. */
    String excerpt() {
      String text = body == null ? "" : body.strip();
      if (text.length() <= EXCERPT_LIMIT) {
        return text;
      }
      return text.substring(0, EXCERPT_LIMIT) + "… (" + text.length() + " bytes)";
    }
  }

  Response putFile(String url, Path file, String contentType, Map<String, String> headers) {
    if (!Files.isRegularFile(file)) {
      throw CliException.policy("no such file: " + file);
    }
    HttpRequest.BodyPublisher body;
    try {
      body = HttpRequest.BodyPublishers.ofFile(file);
    } catch (IOException e) {
      throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
    }
    return send(request(url, headers, contentType).PUT(body).build(), url);
  }

  Response putText(String url, String text, String contentType, Map<String, String> headers) {
    return send(
        request(url, headers, contentType)
            .PUT(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8))
            .build(),
        url);
  }

  Response get(String url) {
    return send(request(url, Map.of(), null).GET().build(), url);
  }

  Response head(String url) {
    return send(
        request(url, Map.of(), null).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
        url);
  }

  private HttpRequest.Builder request(String url, Map<String, String> headers, String contentType) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri(url)).timeout(REQUEST_TIMEOUT).header("User-Agent", userAgent());
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    headers.forEach(builder::header);
    return builder;
  }

  private static String userAgent() {
    // Named so a store's access log says which client wrote a coordinate. There is exactly one
    // publisher on this platform now, and its name in a log is worth the seventeen bytes.
    return "qits-publish";
  }

  private static URI uri(String url) {
    try {
      // `new URI(String)` rather than `URI.create`, so a malformed address is a refusal naming the
      // address rather than an IllegalArgumentException with a stack trace. The scoped npm package
      // names and the `@apidocs/...` docs sites both carry characters (`@`) that are legal in a path
      // segment and look wrong to a reader — a clear message matters here.
      URI parsed = new URI(url);
      if (parsed.getScheme() == null || parsed.getHost() == null) {
        throw CliException.transport("not an absolute http(s) address: " + url);
      }
      return parsed;
    } catch (URISyntaxException e) {
      throw CliException.transport("not a usable address: " + url + " (" + e.getReason() + ")");
    }
  }

  private Response send(HttpRequest request, String url) {
    try {
      HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      Map<String, String> headers = new LinkedHashMap<>();
      response
          .headers()
          .map()
          .forEach(
              (name, values) -> {
                if (!values.isEmpty()) {
                  headers.put(name.toLowerCase(java.util.Locale.ROOT), values.get(0));
                }
              });
      return new Response(response.statusCode(), response.body(), headers);
    } catch (IOException e) {
      throw CliException.transport(
          request.method() + " " + url + " failed: " + e.getClass().getSimpleName()
              + ": " + e.getMessage(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw CliException.transport(request.method() + " " + url + " was interrupted", e);
    }
  }
}
