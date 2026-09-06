package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The replay comparator, case for case against the {@code node -p} one-liner it replaces. */
class VersionOrderTest {

  @Test
  void aLaterCalVerIsAbove() {
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.906.2", "2026.906.1"));
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.907.1", "2026.906.99999"));
  }

  @Test
  void anEarlierCalVerIsBelowAndThatIsWhatMakesAPublishAReplay() {
    assertEquals(VersionOrder.BELOW, VersionOrder.compare("2026.905.1", "2026.906.1"));
  }

  @Test
  void theSameVersionIsSame() {
    assertEquals(VersionOrder.SAME, VersionOrder.compare("2026.906.1", "2026.906.1"));
  }

  @Test
  void aMissingSegmentIsZero() {
    assertEquals(VersionOrder.SAME, VersionOrder.compare("2026.906", "2026.906.0"));
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.906.1", "2026.906"));
  }

  @Test
  void segmentsCompareNumericallyAndNotAsText() {
    // "9" > "10" as text; 9 < 10 as numbers, and CalVer's third segment is seconds of day.
    assertEquals(VersionOrder.BELOW, VersionOrder.compare("2026.906.9", "2026.906.10"));
  }

  @Test
  void aNonNumericSegmentIsZeroBecauseNumberOfItIsNaNAndNaNIsFalsy() {
    // `@qits/ui-components@2026.902.204627-main.geec9f2c` is really in the registry.
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.902.1", "2026.902.204627-main.geec9f2c"));
    assertEquals(VersionOrder.SAME, VersionOrder.compare("2026.902.0", "2026.902.204627-main"));
  }

  @Test
  void anAbsentLatestReadsAsZeroSoEverythingIsAboveIt() {
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.906.1", null));
    assertEquals(VersionOrder.ABOVE, VersionOrder.compare("2026.906.1", ""));
    assertEquals(VersionOrder.SAME, VersionOrder.compare("0", null));
  }

  @Test
  void aSegmentTooLargeForALongIsNotASegmentThisCanOrderBy() {
    assertEquals(VersionOrder.SAME, VersionOrder.compare("1.0", "1." + "9".repeat(40)));
  }
}
