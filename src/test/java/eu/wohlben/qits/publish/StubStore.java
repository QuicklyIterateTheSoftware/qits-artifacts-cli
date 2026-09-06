package eu.wohlben.qits.publish;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * qits-artifacts, as much of it as these tests need: a real HTTP server on an ephemeral port,
 * answering scripted replies and recording what it was asked.
 *
 * <p>A real server rather than a seam, for the qits-ci-daemon reason — the thing under test <em>is</em>
 * the HTTP conversation, so a fake client would only test that the fake agrees with itself. And
 * {@code com.sun.net.httpserver}, which is in the JDK, rather than a test-scope server: this
 * repository's main scope has no dependency and its test scope has exactly one, and the suite should
 * not be the place a second arrives.
 *
 * <p>Replies are keyed by {@code "METHOD /path"}. An unscripted request answers 404 with a plain-text
 * body, which is what the real store does for a coordinate nobody published — so "absent" needs no
 * scripting at all.
 */
final class StubStore implements AutoCloseable {

  /** What the store was asked, kept whole so a test can assert on headers and body bytes. */
  record Request(String method, String path, Map<String, String> headers, byte[] body) {

    String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }

    String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  /** What it answers. */
  record Reply(int status, String body, Map<String, String> headers) {

    static Reply of(int status, String body) {
      return new Reply(status, body, Map.of());
    }

    static Reply of(int status, String body, Map<String, String> headers) {
      return new Reply(status, body, headers);
    }
  }

  private final HttpServer server;
  private final Map<String, Reply> replies = new LinkedHashMap<>();
  private final List<Request> requests = new CopyOnWriteArrayList<>();

  StubStore() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException("cannot bind a stub store", e);
    }
    server.createContext("/", this::handle);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  StubStore on(String method, String path, Reply reply) {
    replies.put(method + " " + path, reply);
    return this;
  }

  List<Request> requests() {
    return List.copyOf(requests);
  }

  Request lastRequest() {
    if (requests.isEmpty()) {
      throw new IllegalStateException("nothing was asked of the stub store");
    }
    return requests.get(requests.size() - 1);
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] body;
    try (InputStream in = exchange.getRequestBody()) {
      body = in.readAllBytes();
    }
    Map<String, String> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
              }
            });
    String method = exchange.getRequestMethod();
    String path = exchange.getRequestURI().getRawPath();
    requests.add(new Request(method, path, headers, body));

    Reply reply =
        replies.getOrDefault(
            method + " " + path, Reply.of(404, "no such thing here: " + method + " " + path));
    byte[] answer = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
    reply.headers().forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
    if (!reply.headers().containsKey("Content-Type")) {
      exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
    }
    if (method.equals("HEAD")) {
      // A HEAD carries the GET's Content-Length and no body, which is what the real routes do and
      // what the JDK's server needs told explicitly.
      exchange.getResponseHeaders().set("Content-Length", String.valueOf(answer.length));
      exchange.sendResponseHeaders(reply.status(), -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(reply.status(), answer.length == 0 ? -1 : answer.length);
    if (answer.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(answer);
      }
    }
    exchange.close();
  }

  @Override
  public void close() {
    server.stop(0);
  }

  /** Every path the stub was asked for, in order — handy for asserting a probe really happened. */
  List<String> paths() {
    List<String> paths = new ArrayList<>();
    for (Request request : requests) {
      paths.add(request.method() + " " + request.path());
    }
    return paths;
  }
}
