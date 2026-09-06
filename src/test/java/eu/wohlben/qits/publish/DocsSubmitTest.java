package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The docs wire, which is the policy's fourth case and the only surface in it: the store explodes a
 * bundle into per-file blobs and keeps no archive digest, so an occupied version cannot be verified
 * at all and the WARN is what stops that reading as a verification.
 *
 * <p>Note the two path facts the fleet's curl invocations carry and a client must reproduce: the docs
 * wire has a repository segment ({@code docs}) that the sbom and daemon wires do not, and the site is
 * a two-segment name like {@code @apidocs/qits-ci} sent with a real slash.
 */
class DocsSubmitTest {

  private static final String PATH = "/artifacts/docs/docs/@apidocs/qits-ci/-/2026.906.1";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path archive;

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    archive = work.resolve("apidocs.tgz");
    Files.write(archive, new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0});
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void anAbsentVersionIsPublishedWithItsMetadataHeaders() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":120}"));

    Harness.Run run =
        cli.run(
            "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1", "--archive",
            archive.toString(), "--meta", "git.commit.hash=deadbeef", "--meta",
            "git.repository.name=qits-ci");

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("published the docs bundle @apidocs/qits-ci@2026.906.1"), run.err());
    assertTrue(run.errContains("1 files, 120 bytes"), run.err());
    StubStore.Request request = store.lastRequest();
    assertEquals("deadbeef", request.header("X-Artifacts-Meta-git.commit.hash"));
    assertEquals("qits-ci", request.header("X-Artifacts-Meta-git.repository.name"));
    // The route inspects no Content-Type and the fleet's curl sends none; sending a wrong one would
    // be worse than sending nothing.
    assertNull(request.header("Content-Type"));
  }

  @Test
  void anOccupiedVersionSkipsAndSaysOutLoudThatNothingWasCompared() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "docs versions are immutable"));
    store.on(
        "GET",
        PATH,
        StubStore.Reply.of(
            200, "{\"fileCount\":3,\"totalBytes\":900,\"publishedAt\":\"2026-09-06T10:00:00Z\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("WARN:"), run.err());
    assertTrue(run.errContains("keeps no archive digest"), run.err());
    assertTrue(run.errContains("3 files, 900 bytes"), run.err());
    assertEquals(List.of("PUT " + PATH, "GET " + PATH), store.paths());
  }

  @Test
  void anOccupiedVersionWhoseProbeAlsoFailsIsStillSuccessBecauseTheConflictProvedItIsThere() {
    store.on("PUT", PATH, StubStore.Reply.of(409, "docs versions are immutable"));
    store.on("GET", PATH, StubStore.Reply.of(500, "no"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("not even its shape could be read"), run.err());
  }

  @Test
  void aStoreThatFailsExitsTwo() {
    store.on("PUT", PATH, StubStore.Reply.of(503, "away"));
    assertEquals(ExitCode.TRANSPORT, submit().code());
  }

  @Test
  void aMetadataValueCarryingANewlineIsRefusedRatherThanSentAsTwoHeaders() {
    Harness.Run run =
        cli.run(
            "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "1", "--archive",
            archive.toString(), "--meta", "k=a\r\nX-Evil: 1");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("control character"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void anExplicitDocsRootWinsOverTheDerivedOne() {
    store.on("PUT", "/somewhere/else/@apidocs/qits-ci/-/1", StubStore.Reply.of(201, "{}"));

    Harness.Run run =
        new Harness()
            .store(store)
            .with("QITS_DOCS_URL", store.url() + "/somewhere/else")
            .run(
                "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "1", "--archive",
                archive.toString());

    assertEquals(ExitCode.OK, run.code());
    assertEquals(List.of("PUT /somewhere/else/@apidocs/qits-ci/-/1"), store.paths());
  }

  private Harness.Run submit() {
    return cli.run(
        "docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1", "--archive",
        archive.toString());
  }
}
