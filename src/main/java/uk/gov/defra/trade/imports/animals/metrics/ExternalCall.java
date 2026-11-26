package uk.gov.defra.trade.imports.animals.metrics;

import java.util.Objects;
import java.util.Set;

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

  /**
   * A call named by its operation when the operation is one of the dependency's known operations,
   * and by {@code other} when it is not, or is missing.
   *
   * @param dependency the system called
   * @param operation the operation on that system, or null when the call does not name one
   * @param knownOperations the operations reported by name
   * @return the call
   */
  public static ExternalCall of(
      Dependency dependency, String operation, Set<String> knownOperations) {
    boolean known = operation != null && knownOperations.contains(operation);
    return new ExternalCall(dependency, known ? operation : "other");
  }
}
