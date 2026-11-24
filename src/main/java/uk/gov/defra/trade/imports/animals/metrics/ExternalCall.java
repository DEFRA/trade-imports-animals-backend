package uk.gov.defra.trade.imports.animals.metrics;

import java.util.Objects;

/**
 * One call to a system the service depends on.
 *
 * @param dependency the system called
 * @param operation the operation on that system, for example {@code get-object}
 */
public record ExternalCall(Dependency dependency, String operation) {

  public ExternalCall {
    Objects.requireNonNull(dependency, "dependency must not be null");
    Objects.requireNonNull(operation, "operation must not be null");
  }
}
