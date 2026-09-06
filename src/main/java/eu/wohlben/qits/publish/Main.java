package eu.wohlben.qits.publish;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * The process boundary, and nothing else.
 *
 * <p>This is the only class that knows there is a JVM to exit, an environment to read, or streams
 * that were not handed to it. Everything the program actually does is {@link Cli#run}, a function of
 * argv and environment — so the suite exercises the whole surface, exit code included, without a
 * subprocess.
 *
 * <p><b>The two streams are re-wrapped as UTF-8 on purpose.</b> Since JDK 19 {@code System.out}
 * encodes with {@code stdout.encoding}, which falls back to the platform's native encoding when
 * stdout is not a console — and a step container runs in the POSIX locale, where that is ASCII. The
 * measured result is every em-dash in a log line arriving as {@code ?}. The messages this program
 * prints are the diagnosis a human reads out of a release log, so they are written in UTF-8 whatever
 * the container's locale says.
 */
public final class Main {

  private Main() {}

  public static void main(String[] argv) {
    PrintStream out =
        new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
    PrintStream err =
        new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
    System.exit(new Cli(Env.ofSystem(), out, err).run(argv));
  }
}
