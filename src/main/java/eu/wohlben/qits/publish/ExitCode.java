package eu.wohlben.qits.publish;

/**
 * The three answers this program gives, and the reason there are only three.
 *
 * <p>A release step branches on an exit code, and every branch it can take has to mean something a
 * pipeline author can act on. {@link #OK} means the coordinate now holds the bytes that were asked
 * for — whether this run wrote them or a previous one did. {@link #POLICY} means the request was
 * refused and re-running will not change that: the caller asked for something impossible, or a
 * coordinate already means different bytes and a second attempt would only ask again. {@link
 * #TRANSPORT} means the question could not be put or could not be answered — the store was
 * unreachable, it answered 5xx, or the environment names no store at all — and a retry is exactly
 * the right response.
 *
 * <p>The split between the last two is what makes an operator's first question answerable without
 * reading the log: 1 is ours, 2 is theirs. A 4xx is our request being wrong, so it is {@code
 * POLICY}; a 5xx is the store failing to answer a well-formed one, so it is {@code TRANSPORT}.
 *
 * <p>{@code exists} reads the same numbers with one addition of its own: 1 means "absent", which is
 * the same shape ("we asked, the answer is no") rather than a fourth meaning.
 */
final class ExitCode {

  /** Published, or already published with the same bytes. */
  static final int OK = 0;

  /**
   * Refused, and re-running will not help: invalid arguments, a 4xx, or an occupied coordinate that
   * holds different bytes. {@code exists} also uses it for "absent".
   */
  static final int POLICY = 1;

  /** Could not ask, or could not be answered: no store configured, an I/O failure, a 5xx. */
  static final int TRANSPORT = 2;

  private ExitCode() {}
}
