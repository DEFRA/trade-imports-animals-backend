package uk.gov.defra.trade.imports.animals.operators;

/**
 * The three-way existence classification of an operator id, plus a distinct outage outcome.
 *
 * <p>This is the <em>only</em> thing {@link OperatorsApiClient} exposes about an operator: its
 * existence, never its field values (c-017/c-018). {@link #UNAVAILABLE} is deliberately kept
 * separate from {@link #NOT_FOUND} — an operators-service outage means the existence of the id is
 * unknown, and must never be mapped to "the operator was deleted".
 */
public enum OperatorExistence {

  /** {@code 200} with {@code status: ACTIVE} — the operator exists and is live. */
  ACTIVE,

  /** {@code 200} with {@code status: DELETED} — the operator was tombstoned (c-018). */
  DELETED,

  /** {@code 404} — unknown id, or an id outside the caller's crn scope. Not a deletion. */
  NOT_FOUND,

  /** Operators service unreachable / timed out — the existence of the id could not be determined. */
  UNAVAILABLE
}
