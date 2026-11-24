package uk.gov.defra.trade.imports.animals.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

public final class EmfDocuments {

  private EmfDocuments() {}

  public static String namespaceOf(JsonNode document) {
    return directiveOf(document).path("Namespace").asText();
  }

  public static String unitOf(JsonNode document) {
    return directiveOf(document).path("Metrics").get(0).path("Unit").asText();
  }

  public static List<List<String>> dimensionSetsOf(JsonNode document) {
    List<List<String>> dimensionSets = new ArrayList<>();
    directiveOf(document)
        .path("Dimensions")
        .forEach(
            dimensionSet -> {
              List<String> keys = new ArrayList<>();
              dimensionSet.forEach(key -> keys.add(key.asText()));
              dimensionSets.add(keys);
            });
    return dimensionSets;
  }

  private static JsonNode directiveOf(JsonNode document) {
    return document.path("_aws").path("CloudWatchMetrics").get(0);
  }
}
