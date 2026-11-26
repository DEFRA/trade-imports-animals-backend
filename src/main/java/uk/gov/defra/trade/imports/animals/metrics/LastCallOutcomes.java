package uk.gov.defra.trade.imports.animals.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The outcome of the most recent call to each dependency. The outcome has no time attached: one
 * failed call, on any of the dependency's operations, marks the whole dependency as unavailable
 * until its next call succeeds.
 */
@Component
public class LastCallOutcomes {

  private final Map<Dependency, Availability> availabilityByDependency = new ConcurrentHashMap<>();

  public void recordSuccess(Dependency dependency) {
    availabilityByDependency.put(dependency, Availability.AVAILABLE);
  }

  public void recordFailure(Dependency dependency) {
    availabilityByDependency.put(dependency, Availability.UNAVAILABLE);
  }

  /**
   * The dependency's availability as its most recent call left it.
   *
   * @param dependency the system called
   * @return available after a success, unavailable after a failure, and unknown when the
   *     dependency has not been called since start-up
   */
  public Availability lastCallAvailability(Dependency dependency) {
    return availabilityByDependency.getOrDefault(dependency, Availability.UNKNOWN);
  }
}
