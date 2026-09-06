package eu.wohlben.qits.publish;

/**
 * "Is this version below the one the registry calls latest?" — the comparator the jslib release
 * pipeline inlined as a line of {@code node -p}, moved here verbatim in behaviour.
 *
 * <p>The original, which this reproduces exactly:
 *
 * <pre>{@code
 * const p = v => v.split(".").map(Number);
 * const [a, b] = [version, latest || "0"].map(p);
 * let r = "same";
 * for (let i = 0; i < Math.max(a.length, b.length); i++) {
 *   const x = a[i] || 0, y = b[i] || 0;
 *   if (x !== y) { r = x < y ? "below" : "above"; break }
 * }
 * }</pre>
 *
 * <p>Two details of that are load-bearing and are kept rather than tidied:
 *
 * <ul>
 *   <li><b>{@code a[i] || 0} makes a non-numeric segment zero.</b> {@code Number("204627-main")} is
 *       {@code NaN}, and {@code NaN} is falsy, so it becomes {@code 0}. The registry really does hold
 *       versions of that shape — {@code @qits/ui-components@2026.902.204627-main.geec9f2c} froze
 *       there when the per-push pipeline was retired — so this is not a hypothetical, and a
 *       comparator that threw on it would fail the very case it exists to handle.
 *   <li><b>A missing segment is zero too</b>, so {@code 2026.906} and {@code 2026.906.0} are the
 *       same version.
 * </ul>
 *
 * <p>This is CalVer ordering and not semver: nothing here knows what a prerelease is, and the store
 * is the one that enforces semver precedence when {@code latest} moves. What this decides is only
 * whether a publish is a <em>replay</em> — a version below the registry's latest, which must take a
 * throwaway tag rather than claim {@code latest} and pull it backwards.
 */
enum VersionOrder {
  BELOW,
  SAME,
  ABOVE;

  static VersionOrder compare(String version, String latest) {
    String[] left = split(version);
    String[] right = split(latest == null || latest.isEmpty() ? "0" : latest);
    int length = Math.max(left.length, right.length);
    for (int i = 0; i < length; i++) {
      long x = i < left.length ? numberOrZero(left[i]) : 0;
      long y = i < right.length ? numberOrZero(right[i]) : 0;
      if (x != y) {
        return x < y ? BELOW : ABOVE;
      }
    }
    return SAME;
  }

  private static String[] split(String version) {
    return version.split("\\.", -1);
  }

  /** {@code Number(segment) || 0}: digits are their value, everything else is zero. */
  private static long numberOrZero(String segment) {
    if (segment.isEmpty()) {
      return 0;
    }
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      if (c < '0' || c > '9') {
        return 0;
      }
    }
    try {
      return Long.parseLong(segment);
    } catch (NumberFormatException overflow) {
      // A segment longer than a long is not a CalVer segment; JavaScript would hold it as an
      // imprecise double, and zero is the same "this is not a number I can order by" answer.
      return 0;
    }
  }
}
