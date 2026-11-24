package uk.gov.defra.trade.imports.animals.metrics;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Whether the most recent call to each dependency succeeded. The outcome has no time attached: one
 * failed call, on any of the dependency's operations, marks the whole dependency as failed until
 * its next call succeeds.
 */
@Component
public class LastCallOutcomes {

  private final Map<Dependency, Boolean> succeededByDependency = new ConcurrentHashMap<>();

  public void recordSuccess(Dependency dependency) {
    succeededByDependency.put(dependency, true);
  }

  public void recordFailure(Dependency dependency) {
    succeededByDependency.put(dependency, false);
  }

  /**
   * The outcome of the dependency's most recent call.
   *
   * @param dependency the system called
   * @return whether it succeeded, or empty when the dependency has not been called since start-up
   */
  public Optional<Boolean> lastCallSucceeded(Dependency dependency) {
    return Optional.ofNullable(succeededByDependency.get(dependency));
  }
}
