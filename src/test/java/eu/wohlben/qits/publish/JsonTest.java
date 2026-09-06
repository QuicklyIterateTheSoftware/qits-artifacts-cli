package eu.wohlben.qits.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hand-rolled reader and writer, which stand in for a dependency this repository will not take. */
class JsonTest {

  @Test
  void theShapesTheStoreAnswersWithAreRead() {
    Map<String, Object> receipt =
        Json.parseObject(
            "{\"packageType\":\"docker\",\"digest\":\"sha256:ab\",\"sizeBytes\":1234,"
                + "\"alreadyPublished\":true,\"createdAt\":\"2026-09-06T10:00:00Z\"}",
            "a receipt");

    assertEquals("docker", Json.string(receipt, "packageType"));
    assertEquals("sha256:ab", Json.string(receipt, "digest"));
    assertEquals("1234", Json.number(receipt, "sizeBytes"));
    assertEquals(Boolean.TRUE, receipt.get("alreadyPublished"));
  }

  @Test
  void aNestedObjectIsReachableAndAMissingOneIsNullRatherThanAnEmptyOne() {
    Map<String, Object> packument =
        Json.parseObject("{\"dist-tags\":{\"latest\":\"1.2.3\"}}", "a packument");

    assertEquals("1.2.3", Json.string(Json.object(packument, "dist-tags"), "latest"));
    assertNull(Json.object(packument, "versions"));
  }

  @Test
  void nestingArraysStringsEscapesAndLiteralsAllSurviveARoundTrip() {
    Object parsed =
        Json.parse("{\"a\":[1,-2.5e3,null,true,false,\"x\\ny\\u0041\"],\"b\":{\"c\":[]}}");

    Map<?, ?> object = (Map<?, ?>) parsed;
    List<?> array = (List<?>) object.get("a");
    assertEquals(6, array.size());
    assertEquals(1.0, array.get(0));
    assertEquals(-2500.0, array.get(1));
    assertNull(array.get(2));
    assertEquals("x\nyA", array.get(5));
  }

  @Test
  void aTruncatedBodyIsARefusalRatherThanAnEmptyAnswer() {
    // The failure this prevents: a response that cannot be read must never look like a response
    // that said nothing, because "said nothing" is how the policy spells "could not verify".
    CliException refusal = assertThrows(CliException.class, () -> Json.parse("{\"digest\":\"sha"));
    assertEquals(ExitCode.TRANSPORT, refusal.code());
    assertTrue(refusal.getMessage().contains("malformed JSON"), refusal.getMessage());
  }

  @Test
  void somethingThatIsNotAnObjectIsRefusedWhereAnObjectWasPromised() {
    assertThrows(CliException.class, () -> Json.parseObject("[1,2]", "a receipt"));
  }

  @Test
  void everyValueThisProgramWritesIsEscapedSoNothingCanEndTheStringItIsWrittenInto() {
    assertEquals("\"a\\\"b\"", Json.quote("a\"b"));
    assertEquals("\"a\\\\b\"", Json.quote("a\\b"));
    assertEquals("\"a\\nb\"", Json.quote("a\nb"));
    assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    assertEquals("\"pkg:docker/qits/x@1.2.3\"", Json.quote("pkg:docker/qits/x@1.2.3"));
  }
}
