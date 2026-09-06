package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code exists} has three answers and not two, and that is the point of it: a step that reads an
 * unreachable store as "absent" republishes on every outage, and a step that reads it as "present"
 * skips a publish that never happened.
 */
class ExistsTest {

  private StubStore store;
  private Harness cli;

  @BeforeEach
  void setUp() {
    store = new StubStore();
    cli = new Harness().store(store).with("QITS_NPM_REGISTRY_URL", store.url() + "/artifacts/npm/npm");
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void aPublishedDaemonBinaryIsZero() {
    store.on("HEAD", "/artifacts/daemons/qits-ci-daemon/1.2.3", StubStore.Reply.of(200, ""));
    assertEquals(ExitCode.OK, cli.run("exists", "daemon", "qits-ci-daemon", "1.2.3").code());
  }

  @Test
  void anAbsentDaemonBinaryIsOne() {
    assertEquals(ExitCode.POLICY, cli.run("exists", "daemon", "qits-ci-daemon", "1.2.3").code());
  }

  @Test
  void aStoreThatCannotAnswerIsTwo() {
    store.on("HEAD", "/artifacts/daemons/qits-ci-daemon/1.2.3", StubStore.Reply.of(503, ""));
    Harness.Run run = cli.run("exists", "daemon", "qits-ci-daemon", "1.2.3");
    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("cannot tell whether"), run.err());
  }

  @Test
  void aDocsVersionIsAskedForWithAGetBecauseTheBundleRouteHasNoHead() {
    store.on("GET", "/artifacts/docs/docs/@userflows/qits-ci/-/abc", StubStore.Reply.of(200, "{}"));
    assertEquals(ExitCode.OK, cli.run("exists", "docs", "@userflows/qits-ci", "abc").code());
    assertEquals(List.of("GET /artifacts/docs/docs/@userflows/qits-ci/-/abc"), store.paths());
  }

  @Test
  void anSbomCoordinateCarriesItsPackageTypeInTheName() {
    store.on("HEAD", "/artifacts/sboms/docker/qits/qits-ci/-/1.2.3", StubStore.Reply.of(200, ""));
    assertEquals(ExitCode.OK, cli.run("exists", "sbom", "docker/qits/qits-ci", "1.2.3").code());
  }

  @Test
  void anSbomCoordinateWithoutAPackageTypeSaysHowToWriteOne() {
    Harness.Run run = cli.run("exists", "sbom", "qits-ci", "1.2.3");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("<packageType>/<packageName>"), run.err());
  }

  @Test
  void anNpmVersionIsLookedUpInThePackument() {
    store.on(
        "GET",
        "/artifacts/npm/npm/@qits/ui-components",
        StubStore.Reply.of(200, "{\"versions\":{\"1.0.0\":{},\"2.0.0\":{}}}"));

    assertEquals(ExitCode.OK, cli.run("exists", "npm", "@qits/ui-components", "2.0.0").code());
    assertEquals(ExitCode.POLICY, cli.run("exists", "npm", "@qits/ui-components", "3.0.0").code());
  }

  @Test
  void anNpmPackageNothingHasEverPublishedIsAbsentRatherThanAnError() {
    Harness.Run run = cli.run("exists", "npm", "@qits/nothing", "1.0.0");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("no published version at all"), run.err());
  }

  @Test
  void anUnknownTypeSaysWhichOnesAreAnswerable() {
    Harness.Run run = cli.run("exists", "docker", "qits/qits-ci", "1.2.3");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("daemon, docs, npm and sbom"), run.err());
  }

  @Test
  void aMissingArgumentSaysWhatWasExpectedThere() {
    Harness.Run run = cli.run("exists", "daemon", "qits-ci-daemon");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("expected a version"), run.err());
  }
}
