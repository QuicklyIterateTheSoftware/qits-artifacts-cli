package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code npm plan}, {@code npm dist-tag} and {@code npm rewrite-lockfile-origin} — the three pieces
 * of reasoning that used to live inline in every jslib and frontend pipeline.
 */
class NpmTest {

  private static final String PACKUMENT = "/artifacts/npm/npm/@qits/ui-components";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;

  @BeforeEach
  void setUp() {
    store = new StubStore();
    cli =
        new Harness()
            .store(store)
            .with("QITS_NPM_REGISTRY_URL", store.url() + "/artifacts/npm/npm")
            .with("QITS_NPM_PROXY_URL", store.url() + "/artifacts/npm/npmjs");
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  // --- plan --------------------------------------------------------------------------------------

  @Test
  void aPackageWithNothingPublishedPlansAPublish() {
    assertEquals("publish\n", plan("2026.906.1").out());
  }

  @Test
  void aVersionTheRegistryAlreadyHoldsPlansASkip() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.1\"},\"versions\":{\"2026.906.1\":{}}}"));

    Harness.Run run = plan("2026.906.1");
    assertEquals("skip\n", run.out());
    assertEquals(ExitCode.OK, run.code());
  }

  @Test
  void aVersionBelowLatestPlansAReplay() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.9\"},\"versions\":{\"2026.906.9\":{}}}"));

    Harness.Run run = plan("2026.905.1");
    assertEquals("publish-replay\n", run.out());
    assertTrue(run.errContains("is below latest 2026.906.9"), run.err());
  }

  @Test
  void aVersionAboveLatestPlansAnOrdinaryPublish() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.9\"},\"versions\":{\"2026.906.9\":{}}}"));

    assertEquals("publish\n", plan("2026.907.1").out());
  }

  @Test
  void aNonNumericSegmentInLatestCountsAsZeroTheWayTheShellComparatorDid() {
    // The registry really holds one of these: @qits/ui-components@2026.902.204627-main.geec9f2c
    // froze there when the per-push pipeline was retired. `Number("204627-main")` is NaN, NaN is
    // falsy, and `NaN || 0` is 0 — so latest reads as 2026.902.0 and a 2026.902.1 is above it.
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200,
            "{\"dist-tags\":{\"latest\":\"2026.902.204627-main.geec9f2c\"},\"versions\":{}}"));

    assertEquals("publish\n", plan("2026.902.1").out());
  }

  @Test
  void anUnreachableRegistryIsNotAPublishDecision() {
    store.on("GET", PACKUMENT, StubStore.Reply.of(500, "away"));

    Harness.Run run = plan("2026.906.1");
    assertEquals(ExitCode.TRANSPORT, run.code());
    assertEquals("", run.out());
  }

  @Test
  void thePlanIsTheOnlyThingOnStdoutSoACommandSubstitutionCapturesJustTheWord() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(200, "{\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{}}"));

    Harness.Run run = plan("2026.906.1");
    assertEquals("publish\n", run.out());
    assertTrue(!run.err().isEmpty(), "the reasoning still belongs in the log");
  }

  // --- dist-tag ----------------------------------------------------------------------------------

  @Test
  void aDistTagIsPutAsAJsonStringUnderTheRepositoryScopedNamespace() {
    String path = "/artifacts/npm/npm/-/package/@qits/ui-components/dist-tags/main";
    store.on("PUT", path, StubStore.Reply.of(200, "{\"latest\":\"2026.906.1\",\"main\":\"2026.906.1\"}"));

    Harness.Run run =
        cli.run(
            "npm", "dist-tag", "--package", "@qits/ui-components", "--version", "2026.906.1",
            "--tag", "main");

    assertEquals(ExitCode.OK, run.code());
    assertEquals("\"2026.906.1\"", store.lastRequest().bodyText());
    assertEquals("application/json", store.lastRequest().header("Content-Type"));
    assertTrue(run.errContains("the main dist-tag now names"), run.err());
  }

  @Test
  void aRefusedBackwardsMoveOfLatestIsARealFailureRatherThanSwallowed() {
    String path = "/artifacts/npm/npm/-/package/@qits/ui-components/dist-tags/latest";
    store.on("PUT", path, StubStore.Reply.of(403, "latest only moves forward"));

    Harness.Run run =
        cli.run(
            "npm", "dist-tag", "--package", "@qits/ui-components", "--version", "1.0.0", "--tag",
            "latest");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("HTTP 403"), run.err());
    assertTrue(run.errContains("latest only moves forward"), run.err());
  }

  // --- rewrite-lockfile-origin -------------------------------------------------------------------

  @Test
  void everyResolvedPinIsRepointedByPathAndNothingElseInTheFileMoves() throws IOException {
    Path lockfile = work.resolve("package-lock.json");
    Files.writeString(
        lockfile,
        """
        {
          "name": "qits-observability-frontend",
          "lockfileVersion": 3,
          "packages": {
            "node_modules/zone.js": {
              "version": "0.15.0",
              "resolved": "http://mirror.dev.localhost:8080/artifacts/npm/npmjs/zone.js/-/zone.js-0.15.0.tgz",
              "integrity": "sha512-deadbeef"
            },
            "node_modules/@qits/ui-components": {
              "version": "2026.906.1",
              "resolved": "http://registry.dev.localhost:8080/artifacts/npm/npm/@qits/ui-components/-/ui-components-2026.906.1.tgz",
              "integrity": "sha512-cafebabe"
            },
            "node_modules/local": { "version": "1.0.0", "resolved": "file:../local" }
          }
        }
        """);

    Harness.Run run = cli.run("npm", "rewrite-lockfile-origin", "--lockfile", lockfile.toString());

    assertEquals(ExitCode.OK, run.code());
    String after = Files.readString(lockfile);
    assertTrue(
        after.contains(store.url() + "/artifacts/npm/npmjs/zone.js/-/zone.js-0.15.0.tgz"),
        after);
    assertTrue(
        after.contains(
            store.url() + "/artifacts/npm/npm/@qits/ui-components/-/ui-components-2026.906.1.tgz"),
        after);
    // A non-http pin is left exactly as it was, and so is every other line.
    assertTrue(after.contains("\"resolved\": \"file:../local\""), after);
    assertTrue(after.contains("\"integrity\": \"sha512-cafebabe\""), after);
    assertTrue(after.contains("\"lockfileVersion\": 3,"), after);
    assertTrue(run.errContains("repointed 2 resolved URLs"), run.err());
  }

  @Test
  void runningItTwiceChangesNothingTheSecondTime() throws IOException {
    Path lockfile = work.resolve("package-lock.json");
    Files.writeString(
        lockfile,
        "{\"resolved\": \"http://elsewhere:1/artifacts/npm/npmjs/a/-/a-1.tgz\"}");

    cli.run("npm", "rewrite-lockfile-origin", "--lockfile", lockfile.toString());
    String once = Files.readString(lockfile);
    Harness.Run again = cli.run("npm", "rewrite-lockfile-origin", "--lockfile", lockfile.toString());

    assertEquals(once, Files.readString(lockfile));
    assertTrue(again.errContains("unchanged"), again.err());
  }

  @Test
  void aMissingRegistryVariableStopsTheStepRatherThanResolvingAgainstNpmjs() {
    Harness.Run run =
        new Harness().run("npm", "rewrite-lockfile-origin", "--lockfile", "package-lock.json");

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("QITS_NPM_REGISTRY_URL is not set"), run.err());
  }

  private Harness.Run plan(String version) {
    return cli.run("npm", "plan", "--package", "@qits/ui-components", "--version", version);
  }
}
