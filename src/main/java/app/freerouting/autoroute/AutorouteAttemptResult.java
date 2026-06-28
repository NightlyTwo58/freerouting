package app.freerouting.autoroute;

import java.util.Collections;
import java.util.Set;

/**
 * Result of a single autoroute attempt for one net connection.
 *
 * <p>Carries both the outcome state and the set of net numbers whose already-routed
 * geometry blocked the maze search.  The blocking-net set is consumed by
 * {@link ConflictAwareRetryScheduler} to build a dependency graph so that a
 * failed net is only retried when at least one of its blockers has been
 * successfully re-routed (CDCL-style conflict-driven scheduling).
 */
public class AutorouteAttemptResult {

  public AutorouteAttemptState state;
  public String details;

  /**
   * The net numbers whose routed traces/vias prevented this connection from
   * being completed.  Empty when the attempt succeeded, or when no blocker
   * information is available (e.g. layer-disabled failure).
   */
  public final Set<Integer> blockingNets;

  // Constructors

  /** Minimal constructor used in catch-blocks where no context is available. */
  public AutorouteAttemptResult(AutorouteAttemptState state) {
    this.state = state;
    this.details = "";
    this.blockingNets = Collections.emptySet();
  }

  public AutorouteAttemptResult(AutorouteAttemptState state, Set<Integer> blockingNets) {
    this.state = state;
    this.blockingNets = blockingNets != null ? blockingNets : Collections.emptySet();
    this.details = "";
  }

  public AutorouteAttemptResult(AutorouteAttemptState state, String details, Set<Integer> blockingNets) {
    this.state = state;
    this.details = details != null ? details : "";
    this.blockingNets = blockingNets != null ? blockingNets : Collections.emptySet();
  }

  // Helpers

  /** Returns true when the attempt ended in any kind of failure. */
  public boolean isFailed() {
    return state == AutorouteAttemptState.FAILED;
  }

  /** Returns true when the attempt resulted in a new route being placed. */
  public boolean isRouted() {
    return state == AutorouteAttemptState.ROUTED;
  }

  @Override
  public String toString() {
    return this.state.toString().toUpperCase() + ": " + this.details;
  }
}
