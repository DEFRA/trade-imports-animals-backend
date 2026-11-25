package uk.gov.defra.trade.imports.animals.metrics;

/** A dependency whose calls are recorded, with the value of its {@code dependency} tag. */
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
