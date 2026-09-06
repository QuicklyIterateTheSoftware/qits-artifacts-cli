package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The argv surface itself: what a mistyped invocation does.
 *
 * <p>These read as small, and they are the ones that matter most in practice — a publish command is
 * written once and then runs unattended for a year, so a dropped flag must stop the step rather than
 * publish to a coordinate nobody meant.
 */
class CommandSurfaceTest {

  private final Harness cli = new Harness().with("QITS_ARTIFACTS_URL", "http://127.0.0.1:1");

  @Test
  void noArgumentsPrintsTheUsageAndIsNotSuccess() {
    Harness.Run run = cli.run();
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("qits-publish sbom submit"), run.err());
  }

  @Test
  void helpIsAskedForOnPurposeSoItIsTheAnswerAndGoesToStdout() {
    Harness.Run run = cli.run("--help");
    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.out().contains("Exit codes:"), run.out());
    assertTrue(run.out().contains("QITS_ARTIFACTS_URL"), run.out());
    assertEquals("", run.err());
  }

  @Test
  void anUnknownCommandNamesItself() {
    Harness.Run run = cli.run("upload", "--file", "x");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("unknown command 'upload'"), run.err());
  }

  @Test
  void anUnknownSubcommandListsTheOnesThereAre() {
    Harness.Run run = cli.run("npm", "publish");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("plan, dist-tag, rewrite-lockfile-origin"), run.err());
  }

  @Test
  void aMistypedFlagIsRefusedRatherThanIgnored() {
    Harness.Run run =
        cli.run("sbom", "submit", "--type", "docker", "--name", "x", "--verison", "1", "--file", "f");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--version is required") || run.errContains("unknown option --verison"), run.err());
  }

  @Test
  void aMissingRequiredFlagNamesItself() {
    Harness.Run run = cli.run("daemon", "submit", "--name", "x", "--file", "f");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--version is required"), run.err());
  }

  @Test
  void aFlagWithNoValueIsRefused() {
    Harness.Run run = cli.run("daemon", "submit", "--name");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--name needs a value"), run.err());
  }

  @Test
  void equalsFormIsAcceptedForTheSameFlags() {
    Harness.Run run = cli.run("npm", "plan", "--package=@qits/x");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--version is required"), run.err());
  }

  @Test
  void aRepeatedSingleValueFlagIsRefusedRatherThanSilentlyLastWins() {
    Harness.Run run =
        cli.run(
            "daemon", "submit", "--name", "a", "--name", "b", "--version", "1", "--file", "f");
    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--name was given more than once"), run.err());
  }

  @Test
  void anUnreachableStoreIsCouldNotAskRatherThanRefused() {
    // 127.0.0.1:1 refuses the connection immediately; that is a transport fact, not a policy one.
    Harness.Run run = cli.run("exists", "daemon", "qits-ci-daemon", "1.2.3");
    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("failed"), run.err());
  }
}
