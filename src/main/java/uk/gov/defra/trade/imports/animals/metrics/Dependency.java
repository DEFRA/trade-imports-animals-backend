package uk.gov.defra.trade.imports.animals.metrics;

/** Every system the service calls, each with its {@code dependency} tag. */
public enum Dependency {
  MONGODB("mongodb"),
  S3("s3"),
  SNS("sns");

  private final String tagValue;

  Dependency(String tagValue) {
    this.tagValue = tagValue;
  }

  public String tagValue() {
    return tagValue;
  }
}
