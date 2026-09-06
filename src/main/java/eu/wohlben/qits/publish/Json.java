package eu.wohlben.qits.publish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON, by hand, because the alternative is a dependency and this program has none.
 *
 * <p>That is not asceticism for its own sake. Everything read here is a response from one of four
 * routes in one service — a publish receipt with five fields, a docs bundle description, an npm
 * packument — and everything written is either a dist-tag body (one string) or a CycloneDX document
 * this program builds itself. A binding library would earn nothing against that and would cost a
 * reflection-configured native image.
 *
 * <p>The parser is a complete RFC 8259 reader with two deliberate liberties: it accepts any
 * top-level value, and it keeps every number as a {@code double} because nothing here does
 * arithmetic on one. Objects come back as {@link LinkedHashMap}, arrays as {@link ArrayList},
 * strings as {@link String}, {@code true}/{@code false} as {@link Boolean} and {@code null} as
 * {@code null}. A malformed document is a {@link CliException} naming the offset, never a silent
 * empty map — a response that cannot be read must not look like a response that said nothing.
 */
final class Json {

  private final String text;
  private int at;

  private Json(String text) {
    this.text = text;
  }

  // --- reading -----------------------------------------------------------------------------------

  /** The document, or a refusal naming where it stopped making sense. */
  static Object parse(String text) {
    Json reader = new Json(text == null ? "" : text);
    reader.skipWhitespace();
    Object value = reader.readValue();
    reader.skipWhitespace();
    if (reader.at < reader.text.length()) {
      throw reader.refuse("trailing content");
    }
    return value;
  }

  /**
   * The document as an object, for the responses whose shape is pinned by a route. Anything else —
   * an array, a bare string, a truncated body — is a refusal rather than an empty map.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> parseObject(String text, String what) {
    Object value = parse(text);
    if (!(value instanceof Map)) {
      throw CliException.transport(what + " is not a JSON object");
    }
    return (Map<String, Object>) value;
  }

  /** A string field, or {@code null} when it is absent or is not a string. */
  static String string(Map<String, Object> object, String key) {
    Object value = object == null ? null : object.get(key);
    return value instanceof String s ? s : null;
  }

  /** A numeric field rendered the way it was meant to read, or {@code null}. */
  static String number(Map<String, Object> object, String key) {
    Object value = object == null ? null : object.get(key);
    if (!(value instanceof Double d)) {
      return null;
    }
    return d == Math.rint(d) && !d.isInfinite() ? String.valueOf((long) (double) d) : d.toString();
  }

  /** A nested object, or {@code null} when the key is absent or holds something else. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> object(Map<String, Object> object, String key) {
    Object value = object == null ? null : object.get(key);
    return value instanceof Map ? (Map<String, Object>) value : null;
  }

  // --- writing -----------------------------------------------------------------------------------

  /**
   * A JSON string literal, quotes included. Every value this program emits goes through here for the
   * same reason the shell it replaces sent every value through {@code %s}: an image reference read
   * out of a Dockerfile is repository-controlled text, and it must never be able to end the string
   * it is written into.
   */
  static String quote(String value) {
    StringBuilder out = new StringBuilder(value.length() + 2);
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }

  // --- the reader --------------------------------------------------------------------------------

  private Object readValue() {
    if (at >= text.length()) {
      throw refuse("unexpected end of document");
    }
    char c = text.charAt(at);
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't' -> readKeyword("true", Boolean.TRUE);
      case 'f' -> readKeyword("false", Boolean.FALSE);
      case 'n' -> readKeyword("null", null);
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    Map<String, Object> object = new LinkedHashMap<>();
    at++; // '{'
    skipWhitespace();
    if (peek() == '}') {
      at++;
      return object;
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw refuse("expected a member name");
      }
      String key = readString();
      skipWhitespace();
      if (peek() != ':') {
        throw refuse("expected ':'");
      }
      at++;
      skipWhitespace();
      object.put(key, readValue());
      skipWhitespace();
      char c = peek();
      at++;
      if (c == '}') {
        return object;
      }
      if (c != ',') {
        throw refuse("expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray() {
    List<Object> array = new ArrayList<>();
    at++; // '['
    skipWhitespace();
    if (peek() == ']') {
      at++;
      return array;
    }
    while (true) {
      skipWhitespace();
      array.add(readValue());
      skipWhitespace();
      char c = peek();
      at++;
      if (c == ']') {
        return array;
      }
      if (c != ',') {
        throw refuse("expected ',' or ']'");
      }
    }
  }

  private String readString() {
    at++; // '"'
    StringBuilder out = new StringBuilder();
    while (true) {
      if (at >= text.length()) {
        throw refuse("unterminated string");
      }
      char c = text.charAt(at++);
      if (c == '"') {
        return out.toString();
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (at >= text.length()) {
        throw refuse("unterminated escape");
      }
      char escape = text.charAt(at++);
      switch (escape) {
        case '"' -> out.append('"');
        case '\\' -> out.append('\\');
        case '/' -> out.append('/');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          if (at + 4 > text.length()) {
            throw refuse("truncated \\u escape");
          }
          out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
          at += 4;
        }
        default -> throw refuse("unknown escape \\" + escape);
      }
    }
  }

  private Object readKeyword(String keyword, Object value) {
    if (!text.startsWith(keyword, at)) {
      throw refuse("expected " + keyword);
    }
    at += keyword.length();
    return value;
  }

  private Double readNumber() {
    int start = at;
    if (peek() == '-') {
      at++;
    }
    while (at < text.length() && isNumberCharacter(text.charAt(at))) {
      at++;
    }
    try {
      return Double.valueOf(text.substring(start, at));
    } catch (NumberFormatException e) {
      throw refuse("not a number");
    }
  }

  private static boolean isNumberCharacter(char c) {
    return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
  }

  private char peek() {
    if (at >= text.length()) {
      throw refuse("unexpected end of document");
    }
    return text.charAt(at);
  }

  private void skipWhitespace() {
    while (at < text.length()) {
      char c = text.charAt(at);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      at++;
    }
  }

  private CliException refuse(String why) {
    return CliException.transport("malformed JSON at offset " + at + ": " + why);
  }
}
