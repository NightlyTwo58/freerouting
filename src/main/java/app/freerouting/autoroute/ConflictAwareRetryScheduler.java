package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;

import java.util.*;

/**
 * CDCL-inspired conflict-aware retry scheduler for the batch autorouter.
 *
 * <p>Tracks, for each net:
 * <ul>
 *   <li>Which other nets blocked it (its conflict set).</li>
 *   <li>How many times it has failed consecutively.</li>
 *   <li>Whether anything on its routing path has changed since its last failure
 *       (the "dirty" flag).  A net that is not dirty will produce the same
 *       maze-search result it produced before, so retrying it wastes time.</li>
 *   <li>An adaptive ripup-cost multiplier that rises with repeated failures and
 *       falls back toward 1.0 on success, mimicking VSIDS-style activity decay.</li>
 * </ul>
 *
 * <p>Usage inside {@code autoroute_pass}:
 * <pre>
 *   ConflictAwareRetryScheduler scheduler = new ConflictAwareRetryScheduler(baseRipupCost);
 *
 *   // on failure:
 *   scheduler.recordFailure(netNo, result.blockingNets);
 *
 *   // on success:
 *   scheduler.recordSuccess(netNo);
 *
 *   // before routing a net decide whether to skip it:
 *   if (scheduler.shouldSkip(netNo)) continue;
 *
 *   // get the ripup cost to use for this net:
 *   int ripupCost = scheduler.getRipupCost(netNo);
 * </pre>
 */
public class ConflictAwareRetryScheduler {

    // Tuning constants

    /** Maximum adaptive multiplier on the ripup cost. */
    private static final double MAX_COST_MULTIPLIER = 4.0;

    /**
     * Per-failure cost multiplier growth.  After k consecutive failures the
     * effective cost multiplier is min(1 + k * COST_GROWTH_PER_FAILURE, MAX).
     */
    private static final double COST_GROWTH_PER_FAILURE = 0.5;

    /**
     * After a net succeeds its multiplier decays toward 1.0 by this factor
     * (i.e. multiplier *= DECAY_ON_SUCCESS).  Keeps recent history relevant.
     */
    private static final double DECAY_ON_SUCCESS = 0.75;

    // Per-net state

    /** Nets that blocked this net on the most recent failed attempt. */
    private final Map<Integer, Set<Integer>> blockers = new HashMap<>();

    /**
     * Nets that <em>depend on</em> this net: if this net is rerouted they must
     * be marked dirty.  This is the transpose of {@link #blockers}.
     */
    private final Map<Integer, Set<Integer>> dependents = new HashMap<>();

    /** Number of consecutive failures (reset to 0 on success). */
    private final Map<Integer, Integer> failureCount = new HashMap<>();

    /**
     * Dirty flag: true if the routing environment for this net may have changed
     * since its last failure.  All nets start dirty (we have no prior
     * knowledge).  A net is marked clean after a failed attempt; it is re-dirtied
     * when any net in its blocker set is successfully routed (because that frees
     * up space on the path).
     */
    private final Map<Integer, Boolean> dirty = new HashMap<>();

    /** Adaptive cost multiplier, ≥ 1.0. */
    private final Map<Integer, Double> costMultiplier = new HashMap<>();

    private final int baseRipupCost;

    // Constructor

    public ConflictAwareRetryScheduler(int baseRipupCost) {
        this.baseRipupCost = baseRipupCost;
    }

    // Public API

    /**
     * Records a routing failure for {@code netNo} with the supplied set of
     * blocking net numbers.
     *
     * @param netNo       the net that failed to route
     * @param blockingNets nets whose routed geometry blocked this net's path
     */
    public void recordFailure(int netNo, Set<Integer> blockingNets) {
        // Increment consecutive-failure counter.
        failureCount.merge(netNo, 1, Integer::sum);

        // Raise cost multiplier.
        double currentMultiplier = costMultiplier.getOrDefault(netNo, 1.0);
        double newMultiplier = Math.min(
                currentMultiplier + COST_GROWTH_PER_FAILURE,
                MAX_COST_MULTIPLIER);
        costMultiplier.put(netNo, newMultiplier);

        // Mark clean: we just attempted this net; retrying without any
        // environmental change would yield the same result.
        dirty.put(netNo, false);

        if (blockingNets == null || blockingNets.isEmpty()) {
            // No explicit blocker information.  We still recorded the failure;
            // the net stays non-dirty until something else changes.
            blockers.put(netNo, Collections.emptySet());
            return;
        }

        // Replace the previous blocker set.
        Set<Integer> prevBlockers = blockers.getOrDefault(netNo, Collections.emptySet());

        // Remove this net from the dependents of its old blockers.
        for (int oldBlocker : prevBlockers) {
            Set<Integer> deps = dependents.get(oldBlocker);
            if (deps != null) {
                deps.remove(netNo);
            }
        }

        Set<Integer> newBlockers = new HashSet<>(blockingNets);
        newBlockers.remove(netNo); // a net cannot block itself
        blockers.put(netNo, Collections.unmodifiableSet(newBlockers));

        // Register this net as a dependent of each blocker.
        for (int blocker : newBlockers) {
            dependents.computeIfAbsent(blocker, k -> new HashSet<>()).add(netNo);
        }

        FRLogger.debug("CDCL: net " + netNo + " failed (failures=" + failureCount.get(netNo)
                + ", multiplier=" + String.format("%.2f", newMultiplier)
                + ", blockers=" + newBlockers + ")");
    }

    /**
     * Records a successful routing for {@code netNo}.
     * Marks all nets that were waiting on this net as dirty (they should be
     * retried because space has been freed / a blocker has been resolved).
     *
     * @param netNo the net that was successfully routed
     */
    public void recordSuccess(int netNo) {
        // Reset failure counter.
        failureCount.remove(netNo);

        // Decay cost multiplier toward 1.0.
        double currentMultiplier = costMultiplier.getOrDefault(netNo, 1.0);
        double decayed = 1.0 + (currentMultiplier - 1.0) * DECAY_ON_SUCCESS;
        if (decayed < 1.01) {
            costMultiplier.remove(netNo);
        } else {
            costMultiplier.put(netNo, decayed);
        }

        // This net no longer blocks anything; remove it as a blocker for all
        // dependents.  The dependents are now re-dirtied.
        Set<Integer> deps = dependents.remove(netNo);
        if (deps != null && !deps.isEmpty()) {
            for (int dep : deps) {
                dirty.put(dep, true);
                // Also update the blocker set of the dependent.
                Set<Integer> depBlockers = blockers.get(dep);
                if (depBlockers != null && !depBlockers.isEmpty()) {
                    Set<Integer> updatedBlockers = new HashSet<>(depBlockers);
                    updatedBlockers.remove(netNo);
                    blockers.put(dep, Collections.unmodifiableSet(updatedBlockers));
                }
            }
            FRLogger.debug("CDCL: net " + netNo + " succeeded, woke dependents=" + deps);
        }

        // Also dirty this net itself so a fresh attempt is made if it ever
        // fails again in a later pass.
        dirty.remove(netNo);
        // Clear its own blocker record — it is now routed.
        blockers.remove(netNo);
    }

    /**
     * Returns {@code true} if the net should be skipped on this pass because
     * its routing environment has not changed since the last failed attempt.
     *
     * <p>A net is skipped only when:
     * <ol>
     *   <li>It has failed at least once ({@link #failureCount} &gt; 0), AND</li>
     *   <li>It has a known non-empty blocker set (i.e. we know <em>why</em> it
     *       failed), AND</li>
     *   <li>It is not dirty (none of its blockers have been resolved since the
     *       last attempt).</li>
     * </ol>
     *
     * @param netNo the net to evaluate
     * @return true if routing should be skipped for this net on this pass
     */
    public boolean shouldSkip(int netNo) {
        if (failureCount.getOrDefault(netNo, 0) == 0) {
            return false; // Never failed — always attempt.
        }
        Set<Integer> myBlockers = blockers.getOrDefault(netNo, Collections.emptySet());
        if (myBlockers.isEmpty()) {
            // Unknown blockers: always retry (conservative).
            return false;
        }
        boolean isDirty = dirty.getOrDefault(netNo, true);
        if (!isDirty) {
            FRLogger.debug("CDCL: skipping net " + netNo
                    + " (not dirty, blockers still present: " + myBlockers + ")");
            return true;
        }
        return false;
    }

    /**
     * Returns the adaptive ripup cost to use when routing {@code netNo}.
     * The cost rises with each consecutive failure, up to
     * {@value #MAX_COST_MULTIPLIER}× the base cost, then falls back after
     * success.
     *
     * @param netNo      the net about to be routed
     * @param passScale  the linear pass-based scaling factor already applied
     *                   by the caller (typically {@code start_ripup_costs * pass_no})
     * @return adjusted ripup cost
     */
    public int getRipupCost(int netNo, int passScale) {
        double multiplier = costMultiplier.getOrDefault(netNo, 1.0);
        // passScale is the base; multiply adaptively on top of it.
        return (int) Math.round(passScale * multiplier);
    }

    /**
     * Marks a net as dirty externally — call this when items on the board
     * change in a way that is known to affect a net's routing path (e.g. a
     * neighbour was ripped up during another net's routing).
     *
     * @param netNo the net to mark dirty
     */
    public void markDirty(int netNo) {
        dirty.put(netNo, true);
    }

    /**
     * Returns the current consecutive failure count for a net.
     */
    public int getFailureCount(int netNo) {
        return failureCount.getOrDefault(netNo, 0);
    }

    /**
     * Returns a read-only view of the current blocker set for a net.
     */
    public Set<Integer> getBlockers(int netNo) {
        return blockers.getOrDefault(netNo, Collections.emptySet());
    }

    /**
     * Returns a read-only view of the nets that are waiting on {@code netNo}
     * to be routed (its dependents).
     */
    public Set<Integer> getDependents(int netNo) {
        Set<Integer> deps = dependents.get(netNo);
        return deps != null ? Collections.unmodifiableSet(deps) : Collections.emptySet();
    }

    /**
     * Resets all state.  Call between passes when a full re-evaluation is
     * desired (e.g. after a board-history restore).
     */
    public void reset() {
        blockers.clear();
        dependents.clear();
        failureCount.clear();
        dirty.clear();
        costMultiplier.clear();
    }

    /**
     * Returns a diagnostic summary string.
     */
    @Override
    public String toString() {
        return "ConflictAwareRetryScheduler{"
                + "nets_with_failures=" + failureCount.size()
                + ", nets_blocked=" + blockers.size()
                + ", nets_dirty=" + dirty.values().stream().filter(v -> v).count()
                + "}";
    }
}
