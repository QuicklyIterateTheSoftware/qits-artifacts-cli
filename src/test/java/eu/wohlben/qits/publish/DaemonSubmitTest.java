package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The idempotency matrix for the daemon-binary wire, which is the one the fleet disagreed with
 * itself about: qits-ci-daemon's pipeline calls a 409 a hard failure, and the maven and npm steps
 * beside it call their equivalent a skip. Both were right about their own case; the digest is what
 * lets one rule cover both.
 */
class DaemonSubmitTest {

  private static final String PATH = "/artifacts/daemons/qits-artifacts-cli/2026.906.1";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path binary;

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    binary = work.resolve("qits-publish");
    Files.write(binary, new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 3, 4});
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void anAbsentCoordinateIsPublishedAsOctetStream() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"digest\":\"sha256:abc\",\"sizeBytes\":8}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("published the daemon binary qits-artifacts-cli@2026.906.1"), run.err());
    assertEquals("application/octet-stream", store.lastRequest().header("Content-Type"));
    assertEquals(8, store.lastRequest().body().length);
  }

  @Test
  void aConflictWhoseStoredBytesAreTheSameIsSuccess() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "already published"));
    store.on(
        "HEAD",
        PATH,
        StubStore.Reply.of(200, "", Map.of("Docker-Content-Digest", Sha256.ofFile(binary))));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("already published with the same bytes"), run.err());
    assertEquals(List.of("PUT " + PATH, "HEAD " + PATH), store.paths());
  }

  @Test
  void aConflictWhoseStoredBytesDifferIsAHardFailureNamingBoth() {
    String stored = "sha256:" + "cd".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(409, "already published"));
    store.on("HEAD", PATH, StubStore.Reply.of(200, "", Map.of("Docker-Content-Digest", stored)));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("DIFFERENT bytes"), run.err());
    assertTrue(run.errContains(stored), run.err());
    assertTrue(run.errContains(Sha256.ofFile(binary)), run.err());
  }

  @Test
  void anEtagIsReadWhenTheDigestHeaderIsNotThere() {
    String hex = Sha256.ofFile(binary).substring("sha256:".length());
    store.on("PUT", PATH, StubStore.Reply.of(409, "already published"));
    store.on("HEAD", PATH, StubStore.Reply.of(200, "", Map.of("ETag", "\"" + hex + "\"")));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("verified"), run.err());
  }

  @Test
  void aConflictThatCannotBeReadBackIsCouldNotAskRatherThanVerified() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "already published"));
    store.on("HEAD", PATH, StubStore.Reply.of(500, ""));

    Harness.Run run = submit();

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("could not be compared"), run.err());
  }

  @Test
  void aStoreThatFailsExitsTwo() {
    store.on("PUT", PATH, StubStore.Reply.of(502, "bad gateway"));

    assertEquals(ExitCode.TRANSPORT, submit().code());
  }

  @Test
  void aMissingFileIsRefusedBeforeAnythingIsSent() {
    Harness.Run run =
        cli.run(
            "daemon", "submit", "--name", "qits-artifacts-cli", "--version", "2026.906.1",
            "--file", work.resolve("nope").toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("no such file"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  private Harness.Run submit() {
    return cli.run(
        "daemon", "submit", "--name", "qits-artifacts-cli", "--version", "2026.906.1", "--file",
        binary.toString());
  }
}
