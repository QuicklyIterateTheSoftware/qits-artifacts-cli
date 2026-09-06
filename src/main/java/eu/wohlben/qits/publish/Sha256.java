package eu.wohlben.qits.publish;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The digest the whole idempotency policy turns on.
 *
 * <p>Every route in the artifacts store that can answer "what is stored here" answers with sha-256,
 * in one of three spellings: a JSON {@code digest} field and a {@code Docker-Content-Digest} header
 * both say {@code sha256:<hex>}, while an {@code ETag} is the bare hex in quotes. {@link
 * #normalize(String)} folds all three into one string so a comparison is a string comparison and
 * cannot be got subtly wrong at three call sites.
 */
final class Sha256 {

  private Sha256() {}

  /** The file's digest, as {@code sha256:<64 lowercase hex>}. Streamed — a bundle can be large. */
  static String ofFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = in.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return "sha256:" + hex(digest.digest());
    } catch (java.nio.file.NoSuchFileException e) {
      // A file the caller named and that is not there is the caller's mistake, not the store's
      // absence — POLICY, so a step does not retry its way through a typo.
      throw CliException.policy("no such file: " + file);
    } catch (IOException e) {
      throw CliException.transport("cannot read " + file + ": " + e.getMessage(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("this JDK has no SHA-256", e);
    }
  }

  /**
   * One spelling out of the store's three, or {@code null} for anything that is not a sha-256 at
   * all. A {@code null} means "the store told us nothing comparable" and the caller must degrade
   * loudly rather than treat it as a mismatch.
   */
  static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String text = value.trim();
    if (text.startsWith("W/")) { // a weak ETag validator; the hex is still the hex
      text = text.substring(2).trim();
    }
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
      text = text.substring(1, text.length() - 1);
    }
    if (text.startsWith("sha256:")) {
      text = text.substring("sha256:".length());
    }
    if (text.length() != 64) {
      return null;
    }
    for (int i = 0; i < text.length(); i++) {
      char c = Character.toLowerCase(text.charAt(i));
      if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
        return null;
      }
    }
    return "sha256:" + text.toLowerCase(java.util.Locale.ROOT);
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      out.append(Character.forDigit((b >> 4) & 0xf, 16));
      out.append(Character.forDigit(b & 0xf, 16));
    }
    return out.toString();
  }
}
