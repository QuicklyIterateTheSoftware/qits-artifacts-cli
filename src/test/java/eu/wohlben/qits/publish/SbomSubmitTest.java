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
 * The idempotency matrix for the sbom wire, which is the cheapest of the three: an occupied
 * coordinate answers 200 with the stored digest, so the PUT that was going to happen anyway settles
 * whether the bytes agree.
 */
class SbomSubmitTest {

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path document;

  private static final String PATH = "/artifacts/sboms/docker/qits/qits-ci/-/2026.906.1";

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    document = work.resolve("sbom.json");
    Files.writeString(document, "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}");
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void anAbsentCoordinateIsPublishedAndTheReceiptIsReadBack() {
    store.on(
        "PUT",
        PATH,
        StubStore.Reply.of(
            201, "{\"digest\":\"" + Sha256.ofFile(document) + "\",\"sizeBytes\":42}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("published the sbom for docker qits/qits-ci@2026.906.1"), run.err());
    assertTrue(run.errContains("42 bytes"), run.err());
    assertEquals(
        "application/vnd.cyclonedx+json", store.lastRequest().header("Content-Type"));
    assertEquals("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}", store.lastRequest().bodyText());
  }

  @Test
  void anOccupiedCoordinateHoldingTheSameBytesIsSuccessAndSaysSo() {
    store.on(
        "PUT",
        PATH,
        StubStore.Reply.of(
            200,
            "{\"alreadyPublished\":true,\"digest\":\"" + Sha256.ofFile(document) + "\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("already published with the same bytes"), run.err());
    assertTrue(run.errContains("verified"), run.err());
  }

  @Test
  void anOccupiedCoordinateHoldingDifferentBytesFailsNamingBothDigests() {
    String other = "sha256:" + "ab".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true,\"digest\":\"" + other + "\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("DIFFERENT bytes"), run.err());
    assertTrue(run.errContains(other), run.err());
    assertTrue(run.errContains(Sha256.ofFile(document)), run.err());
  }

  @Test
  void anOccupiedCoordinateThatCarriesNoDigestDegradesLoudlyRatherThanSilently() {
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("WARN:"), run.err());
    assertTrue(run.errContains("NOT verified"), run.err());
  }

  @Test
  void aStoreThatFailsIsNotARefusalAndExitsTwo() {
    store.on("PUT", PATH, StubStore.Reply.of(503, "the database is away"));

    Harness.Run run = submit();

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("HTTP 503"), run.err());
    assertTrue(run.errContains("the database is away"), run.err());
  }

  @Test
  void aBadRequestIsARefusalAndExitsOne() {
    store.on("PUT", PATH, StubStore.Reply.of(400, "not a CycloneDX document"));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("HTTP 400"), run.err());
  }

  @Test
  void aPackageTypeTheStoreDoesNotServeIsRefusedBeforeAnythingIsSent() {
    Harness.Run run =
        cli.run(
            "sbom", "submit", "--type", "gem", "--name", "x", "--version", "1", "--file",
            document.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("unknown package type 'gem'"), run.err());
    assertTrue(store.requests().isEmpty(), "nothing should have been sent");
  }

  @Test
  void noStoreAddressIsCouldNotAskRatherThanRefused() {
    Harness.Run run =
        new Harness()
            .run(
                "sbom", "submit", "--type", "docker", "--name", "x", "--version", "1", "--file",
                document.toString());

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("QITS_ARTIFACTS_URL is not set"), run.err());
  }

  @Test
  void theStoreRootIsDerivedFromTheNpmRegistryWithAWarnWhileTheVariableIsMissing() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"digest\":\"x\",\"sizeBytes\":1}"));

    Harness.Run run =
        new Harness()
            .with("QITS_NPM_REGISTRY_URL", store.url() + "/artifacts/npm/npm")
            .run(
                "sbom", "submit", "--type", "docker", "--name", "qits/qits-ci", "--version",
                "2026.906.1", "--file", document.toString());

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("QITS_ARTIFACTS_URL is not set; deriving"), run.err());
    assertEquals("PUT " + PATH, store.paths().get(0));
  }

  private Harness.Run submit() {
    return cli.run(
        "sbom",
        "submit",
        "--type",
        "docker",
        "--name",
        "qits/qits-ci",
        "--version",
        "2026.906.1",
        "--file",
        document.toString());
  }

  @Test
  void aNameThatTriesToTraverseIsRefused() {
    Harness.Run run =
        cli.run(
            "sbom", "submit", "--type", "docker", "--name", "qits/../daemons", "--version", "1",
            "--file", document.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("traverses"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void theDocumentIsSentUnderTheCoordinateTheArgumentsSpell() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"digest\":\"d\",\"sizeBytes\":1}"));
    submit();
    assertEquals("PUT " + PATH, store.paths().get(0));
  }
}
