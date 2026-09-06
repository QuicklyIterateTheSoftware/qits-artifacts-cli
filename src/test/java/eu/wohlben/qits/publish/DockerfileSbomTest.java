package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generator, against the real Dockerfiles it was written from.
 *
 * <p>The fixtures under {@code src/test/resources/dockerfiles} are copies of files that ship on this
 * platform — qits-database-oci's, two of qits-build-images-oci's, and qits-ci-daemon's — because the
 * shell this replaces was written against exactly those and its output is the specification. {@code
 * edges.Dockerfile} is the only invented one, and it holds the shapes the shell handled by hand: a
 * digest pin, a registry port that is not a tag, a repeated upstream, a dropped {@code --platform}
 * flag and a stage alias.
 */
class DockerfileSbomTest {

  @TempDir Path work;

  @BeforeEach
  void copyFixtures() throws IOException {
    for (String name :
        List.of(
            "database-oci.Dockerfile",
            "ci-base.Dockerfile",
            "node-docker-base.Dockerfile",
            "ci-daemon.Dockerfile",
            "edges.Dockerfile")) {
      try (InputStream in = getClass().getResourceAsStream("/dockerfiles/" + name)) {
        Files.write(work.resolve(name), in.readAllBytes());
      }
    }
  }

  @Test
  void theDatabaseImageProducesTheDocumentItsShellProduced() {
    String document =
        DockerfileSbom.document(
            "qits/qits-oci-postgresql",
            "2026.906.1",
            List.of(work.resolve("database-oci.Dockerfile")),
            Map.of());

    assertEquals(
        "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,"
            + "\"metadata\":{\"component\":{\"type\":\"container\","
            + "\"bom-ref\":\"pkg:docker/qits/qits-oci-postgresql@2026.906.1\","
            + "\"name\":\"qits/qits-oci-postgresql\",\"version\":\"2026.906.1\","
            + "\"purl\":\"pkg:docker/qits/qits-oci-postgresql@2026.906.1\"}},"
            + "\"components\":[{\"type\":\"container\","
            + "\"bom-ref\":\"pkg:docker/mirror.dev.localhost:8080/hub/library/postgres@18.4\","
            + "\"name\":\"mirror.dev.localhost:8080/hub/library/postgres\",\"version\":\"18.4\","
            + "\"purl\":\"pkg:docker/mirror.dev.localhost:8080/hub/library/postgres@18.4\"}],"
            + "\"dependencies\":[{\"ref\":\"pkg:docker/qits/qits-oci-postgresql@2026.906.1\","
            + "\"dependsOn\":[\"pkg:docker/mirror.dev.localhost:8080/hub/library/postgres@18.4\"]}]}\n",
        document);
  }

  @Test
  void aStageAliasIsNotAnUpstreamImage() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("ci-base.Dockerfile")), Map.of());

    // `FROM moby/buildkit:v0.33.0 AS buildkit` and `FROM docker:cli`. The alias `buildkit` names no
    // upstream, and nothing here is built FROM it, so two bases is the whole answer.
    assertEquals(List.of("moby/buildkit", "docker"), bases.stream().map(DockerfileSbom.Base::name).toList());
    assertEquals(List.of("v0.33.0", "cli"), bases.stream().map(DockerfileSbom.Base::version).toList());
  }

  @Test
  void anUntaggedFromMeansLatest() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("ci-base.Dockerfile")), Map.of());
    assertEquals("latest", DockerfileSbom.split("docker").version());
    assertEquals("cli", bases.get(1).version());
  }

  @Test
  void aDigestPinsHarderThanATagSoTheDigestIsTheVersion() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("edges.Dockerfile")), Map.of());

    assertEquals("registry.example.com/library/alpine", bases.get(0).name());
    assertEquals("sha256:" + "0123456789abcdef".repeat(4), bases.get(0).version());
  }

  @Test
  void aRegistryPortIsNotATagAndARepeatedUpstreamIsOneDependency() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("edges.Dockerfile")), Map.of());

    // Four FROM lines: a digest pin, the same host:port image twice (once behind --platform), and
    // one that names an earlier stage. Two distinct upstreams.
    assertEquals(2, bases.size());
    assertEquals("host.example.com:5000/team/tool", bases.get(1).name());
    assertEquals("latest", bases.get(1).version());
  }

  @Test
  void anArgInAFromResolvesFromTheFileSOwnDefault() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("ci-daemon.Dockerfile")), Map.of());

    // `ARG BUILDER_IMAGE=qits/graalvmce-musl-builder:jdk-25` then `FROM ${BUILDER_IMAGE} AS build`.
    // The shell this replaces refused the line outright: braces are not registry characters.
    assertEquals(1, bases.size());
    assertEquals("qits/graalvmce-musl-builder", bases.get(0).name());
    assertEquals("jdk-25", bases.get(0).version());
  }

  @Test
  void aBuildArgOverridesTheFileSDefaultExactlyAsTheBuildDoes() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(
            List.of(work.resolve("ci-daemon.Dockerfile")),
            Map.of("BUILDER_IMAGE", "registry:8080/qits/graalvmce-musl-builder:jdk-25"));

    assertEquals("registry:8080/qits/graalvmce-musl-builder", bases.get(0).name());
    assertEquals("jdk-25", bases.get(0).version());
  }

  @Test
  void scratchIsTheEmptyBaseAndNamesNoImage() {
    // ci-daemon.Dockerfile ends on two `FROM scratch` stages. A pkg:docker/scratch@latest component
    // would be a dependency on nothing.
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(List.of(work.resolve("ci-daemon.Dockerfile")), Map.of());
    assertTrue(bases.stream().noneMatch(base -> base.name().equals("scratch")), bases.toString());
  }

  @Test
  void anUnresolvableArgIsARefusalNamingTheVariableRatherThanAComponentThatLies() throws IOException {
    Path dockerfile = work.resolve("unresolved.Dockerfile");
    Files.writeString(dockerfile, "FROM ${MYSTERY}\n");

    CliException refusal =
        org.junit.jupiter.api.Assertions.assertThrows(
            CliException.class, () -> DockerfileSbom.bases(List.of(dockerfile), Map.of()));

    assertEquals(ExitCode.POLICY, refusal.code());
    assertTrue(refusal.getMessage().contains("$MYSTERY"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("--build-arg MYSTERY="), refusal.getMessage());
  }

  @Test
  void severalDockerfilesBecomeOneDocumentWithOneEntryPerDistinctUpstream() {
    List<DockerfileSbom.Base> bases =
        DockerfileSbom.bases(
            List.of(work.resolve("ci-base.Dockerfile"), work.resolve("node-docker-base.Dockerfile")),
            Map.of());

    // moby/buildkit:v0.33.0 is the first stage of both files and appears once.
    assertEquals(
        List.of("moby/buildkit", "docker", "node"),
        bases.stream().map(DockerfileSbom.Base::name).toList());
  }

  @Test
  void aDockerfileWithNoFromAtAllIsARefusal() throws IOException {
    Path dockerfile = work.resolve("empty.Dockerfile");
    Files.writeString(dockerfile, "# nothing here\nRUN true\n");

    CliException refusal =
        org.junit.jupiter.api.Assertions.assertThrows(
            CliException.class,
            () -> DockerfileSbom.document("x", "1", List.of(dockerfile), Map.of()));

    assertTrue(refusal.getMessage().contains("declares no base image"), refusal.getMessage());
  }

  @Test
  void aReferenceOutsideTheRegistrySCharsetIsRefused() throws IOException {
    Path dockerfile = work.resolve("hostile.Dockerfile");
    Files.writeString(dockerfile, "FROM alpine\"},{\"x\n");

    CliException refusal =
        org.junit.jupiter.api.Assertions.assertThrows(
            CliException.class, () -> DockerfileSbom.bases(List.of(dockerfile), Map.of()));

    assertTrue(refusal.getMessage().contains("unusable FROM"), refusal.getMessage());
  }

  @Test
  void theCommandWritesTheDocumentWhereItWasAskedTo() throws IOException {
    Path out = work.resolve(".sbom/sbom.json");

    Harness.Run run =
        new Harness()
            .run(
                "sbom", "from-dockerfile", "--root-name", "qits/qits-oci-postgresql",
                "--root-version", "2026.906.1", "--dockerfile",
                work.resolve("database-oci.Dockerfile").toString(), "-o", out.toString());

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("1 base image(s) from 1 Dockerfile(s)"), run.err());
    String document = Files.readString(out, StandardCharsets.UTF_8);
    assertTrue(document.startsWith("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\""), document);
    assertTrue(document.endsWith("}\n"), document);
  }

  @Test
  void theCommandDefaultsToTheDockerfileEveryBuildDefaultsTo() throws IOException {
    // No --dockerfile at all: `Dockerfile`, resolved against the working directory.
    Path here = Path.of("Dockerfile");
    if (Files.exists(here)) {
      return; // never touch a real file in the checkout
    }
    Harness.Run run =
        new Harness()
            .run(
                "sbom", "from-dockerfile", "--root-name", "x", "--root-version", "1", "-o",
                work.resolve("out.json").toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("Dockerfile"), run.err());
  }
}
