package app.freerouting.autoroute;

import app.freerouting.logger.FRLogger;

import java.util.*;

/**
 * Conflict-aware retry scheduler for the batch autorouter.
 *
 * <p>Tracks, for each net:
 * <ul>
 *   <li>Which other nets blocked it on its most recent failed attempt (its
 *       conflict set), and the transpose of that (its dependents).</li>
 *   <li>How many times it has failed consecutively.</li>
 *   <li>Whether anything on its routing path has changed since its last
 *       failure (the "dirty" flag).</li>
 *   <li>An adaptive ripup-cost multiplier that rises with repeated failures
 *       and falls back toward 1.0 on success.</li>
 * </ul>
 *
 * <p>Conflict information is used to <em>prioritize</em> retries, not to
 * permanently prune them. A net is deferred for at most
 * {@link #MAX_SKIP_PASSES} passes even if nothing about its blockers appears
 * to have changed, and recorded blocker sets age out automatically after
 * {@link #BLOCKER_MAX_AGE} passes so stale conflict information can't
 * suppress exploration forever. There is no "learned clause" store: observed
 * conflicts are evidence about what's likely to fail again, not a logical
 * guarantee that it will.
 *
 * <p>Usage inside {@code autoroute_pass}:
 * <pre>
 *   ConflictAwareRetryScheduler scheduler = new ConflictAwareRetryScheduler(baseRipupCost);
 *
 *   // on failure:
 *   scheduler.recordFailure(netNo, result.blockingNets, currentPass);
 *
 *   // on success:
 *   scheduler.recordSuccess(netNo);
 *
 *   // when a net is ripped up but not yet successfully rerouted:
 *   scheduler.recordRipped(netNo);
 *
 *   // before routing, gate and order the candidate list for this pass:
 *   List&lt;Integer&gt; toRoute = new ArrayList&lt;&gt;();
 *   for (int netNo : allNets) {
 *       if (!scheduler.shouldSkip(netNo, currentPass)) {
 *           toRoute.add(netNo);
 *       }
 *   }
 *   toRoute.sort(Comparator.comparingInt(scheduler::priority).reversed());
 *
 *   // get the ripup cost to use for this net:
 *   int ripupCost = scheduler.getRipupCost(netNo, passScale);
 *
 *   // once per pass, after routing attempts for the pass are done:
 *   scheduler.nextPass();
 * </pre>
 */
public class ConflictAwareRetryScheduler {

    // Tuning constants

    /** Maximum adaptive multiplier on the ripup cost. */
    private static final double MAX_COST_MULTIPLIER = 2.0;

    /**
     * Per-failure cost multiplier growth factor (multiplicative / exponential
     * smoothing rather than a flat additive step, so the multiplier doesn't
     * jump by the same fixed amount whether it's near 1.0 or near the cap).
     * Growth only kicks in after MIN_FAILURES_BEFORE_ESCALATION failures to
     * avoid penalising nets that fail once on a first pass due to ordering.
     */
    private static final double COST_GROWTH_FACTOR = 1.15;
    private static final int    MIN_FAILURES_BEFORE_ESCALATION = 2;

    /**
     * After a net succeeds its multiplier decays toward 1.0 by this factor
     * (i.e. multiplier = 1.0 + (multiplier - 1.0) * DECAY_ON_SUCCESS).
     */
    private static final double DECAY_ON_SUCCESS = 0.6;

    /**
     * A net is never skipped more than this many consecutive passes in a
     * row, even if none of its recorded blockers appear to have changed.
     * This guarantees every net eventually gets re-examined, so a stale or
     * incomplete blocker set can't permanently suppress it.
     */
    private static final int MAX_SKIP_PASSES = 2;

    /**
     * Recorded blocker sets older than this many passes are discarded and
     * the owning net is marked dirty again, regardless of whether a success
     * was ever observed for any of its blockers. This bounds how long stale
     * conflict information can influence scheduling.
     */
    private static final int BLOCKER_MAX_AGE = 3;

    // Per-net state

    /** Nets that blocked this net on the most recent failed attempt. */
    private final Map<Integer, Set<Integer>> blockers = new HashMap<>();

    /**
     * Nets that <em>depend on</em> this net: if this net is rerouted or
     * ripped they must be marked dirty. This is the transpose of
     * {@link #blockers}.
     */
    private final Map<Integer, Set<Integer>> dependents = new HashMap<>();

    /** Number of consecutive failures (reset to 0 on success). */
    private final Map<Integer, Integer> failureCount = new HashMap<>();

    /**
     * Dirty flag: true if the routing environment for this net may have
     * changed since its last failure. All nets start dirty (we have no
     * prior knowledge). A net is marked clean after a failed attempt; it is
     * re-dirtied when any net in its blocker set is successfully rerouted
     * or ripped up (because that frees up space on the path).
     */
    private final Map<Integer, Boolean> dirty = new HashMap<>();

    /** Adaptive cost multiplier, &ge; 1.0. */
    private final Map<Integer, Double> costMultiplier = new HashMap<>();

    /** The pass number on which this net was last attempted (failed). */
    private final Map<Integer, Integer> lastTriedPass = new HashMap<>();

    /**
     * Number of passes since this net's blocker set was last refreshed by a
     * failure. Used to age out stale blocker information.
     */
    private final Map<Integer, Integer> blockerAge = new HashMap<>();

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
     * @param netNo        the net that failed to route
     * @param blockingNets nets whose routed geometry blocked this net's path
     * @param currentPass  the current autoroute pass number, used to bound
     *                     how long this net can be skipped for
     */
    public void recordFailure(int netNo, Set<Integer> blockingNets, int currentPass) {
        // Increment consecutive-failure counter.
        failureCount.merge(netNo, 1, Integer::sum);
        int failures = failureCount.get(netNo);

        // Raise cost multiplier, but only after enough failures to be sure
        // this is a genuine structural conflict rather than a pass-ordering
        // accident.
        double currentMultiplier = costMultiplier.getOrDefault(netNo, 1.0);
        double newMultiplier = currentMultiplier;
        if (failures >= MIN_FAILURES_BEFORE_ESCALATION) {
            newMultiplier = Math.min(currentMultiplier * COST_GROWTH_FACTOR, MAX_COST_MULTIPLIER);
            costMultiplier.put(netNo, newMultiplier);
        }

        // Mark clean: we just attempted this net; retrying without any
        // environmental change would yield the same result (for at most
        // MAX_SKIP_PASSES passes - see shouldSkip).
        dirty.put(netNo, false);
        lastTriedPass.put(netNo, currentPass);
        blockerAge.put(netNo, 0);

        // Remove this net from the dependents of its old blockers before
        // recording the new set.
        Set<Integer> prevBlockers = blockers.getOrDefault(netNo, Collections.emptySet());
        for (int oldBlocker : prevBlockers) {
            Set<Integer> deps = dependents.get(oldBlocker);
            if (deps != null) {
                deps.remove(netNo);
            }
        }

        if (blockingNets == null || blockingNets.isEmpty()) {
            // No explicit blocker information. We still recorded the
            // failure; the net stays non-dirty until something else changes
            // or it ages out / is force-retried.
            blockers.put(netNo, Collections.emptySet());
            FRLogger.debug("Retry scheduler: net " + netNo + " failed (failures=" + failures
                    + ", multiplier=" + String.format("%.2f", newMultiplier)
                    + ", no blocker info)");
            return;
        }

        Set<Integer> newBlockers = new HashSet<>(blockingNets);
        newBlockers.remove(netNo); // a net cannot block itself
        blockers.put(netNo, Collections.unmodifiableSet(newBlockers));

        for (int blocker : newBlockers) {
            dependents.computeIfAbsent(blocker, k -> new HashSet<>()).add(netNo);
        }

        FRLogger.debug("Retry scheduler: net " + netNo + " failed (failures=" + failures
                + ", multiplier=" + String.format("%.2f", newMultiplier)
                + ", blockers=" + newBlockers + ")");
    }

    /**
     * Records a successful routing for {@code netNo}. Marks all nets that
     * were waiting on this net as dirty, since space has been freed / a
     * blocker has been resolved.
     *
     * @param netNo the net that was successfully routed
     */
    public void recordSuccess(int netNo) {
        failureCount.remove(netNo);
        lastTriedPass.remove(netNo);
        blockerAge.remove(netNo);

        double currentMultiplier = costMultiplier.getOrDefault(netNo, 1.0);
        double decayed = 1.0 + (currentMultiplier - 1.0) * DECAY_ON_SUCCESS;
        if (decayed < 1.01) {
            costMultiplier.remove(netNo);
        } else {
            costMultiplier.put(netNo, decayed);
        }

        dirtyDependents(netNo);
    }

    /**
     * Records that {@code netNo} was ripped up (its routed geometry removed
     * from the board) regardless of whether it has yet been successfully
     * rerouted. The board topology has already changed at this point, so
     * dependents need to be re-examined even before {@code netNo} succeeds -
     * waiting for {@link #recordSuccess} alone leaves dependents thinking
     * the (now-removed) geometry is still in their way.
     *
     * @param netNo the net that was ripped up
     */
    public void recordRipped(int netNo) {
        dirtyDependents(netNo);
    }

    private void dirtyDependents(int netNo) {
        // This net no longer blocks anything in its current form; remove it
        // as a blocker for all dependents and re-dirty them.
        Set<Integer> deps = dependents.remove(netNo);
        if (deps != null && !deps.isEmpty()) {
            for (int dep : deps) {
                dirty.put(dep, true);
                Set<Integer> depBlockers = blockers.get(dep);
                if (depBlockers != null && !depBlockers.isEmpty()) {
                    Set<Integer> updatedBlockers = new HashSet<>(depBlockers);
                    updatedBlockers.remove(netNo);
                    blockers.put(dep, Collections.unmodifiableSet(updatedBlockers));
                }
            }
        }
    }

    /**
     * Returns true if routing should be skipped for {@code netNo} on this
     * pass. A net is only skipped when it has failed before, its blocker
     * set is non-empty and unchanged (not dirty), <em>and</em> it has been
     * attempted within the last {@link #MAX_SKIP_PASSES} passes. That last
     * condition guarantees no net can be skipped indefinitely, even if its
     * blocker set genuinely never changes.
     *
     * @param netNo       the net to evaluate
     * @param currentPass the current autoroute pass number
     * @return true if routing should be skipped for this net on this pass
     */
    public boolean shouldSkip(int netNo, int currentPass) {
        if (failureCount.getOrDefault(netNo, 0) == 0) {
            return false; // Never failed - always attempt.
        }
        if (dirty.getOrDefault(netNo, true)) {
            return false; // Environment may have changed - always attempt.
        }
        int lastTried = lastTriedPass.getOrDefault(netNo, currentPass);
        if (currentPass - lastTried >= MAX_SKIP_PASSES) {
            return false; // Force periodic re-exploration.
        }

        FRLogger.debug("Retry scheduler: skipping net " + netNo
                + " (blockers unchanged: " + getBlockers(netNo) + ")");
        return true;
    }

    /**
     * Returns a scheduling priority for {@code netNo}: higher should be
     * routed sooner. Intended to order the nets that {@link #shouldSkip}
     * did <em>not</em> filter out for this pass - it is not a substitute
     * for that gate, since routing everything every pass with no gate at
     * all defeats the point of having a scheduler.
     *
     * @param netNo the net to score
     * @return a priority score; higher routes first
     */
    public int priority(int netNo) {
        int score = 0;
        score += 4 * getDependents(netNo).size();
        score += 2 * failureCount.getOrDefault(netNo, 0);
        if (dirty.getOrDefault(netNo, true)) {
            score += 10;
        }
        return score;
    }

    /**
     * Returns the adaptive ripup cost to use when routing {@code netNo}.
     * The cost rises with each consecutive failure, up to
     * {@value #MAX_COST_MULTIPLIER}x the base cost, then falls back after
     * success.
     *
     * @param netNo     the net about to be routed
     * @param passScale the linear pass-based scaling factor already applied
     *                  by the caller (typically {@code start_ripup_costs * pass_no})
     * @return adjusted ripup cost
     */
    public int getRipupCost(int netNo, int passScale) {
        double multiplier = costMultiplier.getOrDefault(netNo, 1.0);
        return (int) Math.round(passScale * multiplier);
    }

    /**
     * Returns how many waiting nets would be unblocked if {@code netNo}
     * were ripped. This is informational only - do NOT feed this into
     * {@code AutorouteControl.ripupCostModifier} or any per-net cost
     * reduction. Reducing ripup costs for high-dependency nets causes
     * churn: the maze search rips previously stable routes, creating
     * re-route cycles that inflate pass counts (observed: B6 6->18 passes,
     * -23 score). Use this only for logging or offline analysis.
     */
    public int ripupUnblockScore(int netNo) {
        Set<Integer> deps = dependents.get(netNo);
        return deps == null ? 0 : deps.size();
    }

    /**
     * Marks a net as dirty externally - call this when items on the board
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
     * Ages all recorded blocker sets by one pass, discarding any that have
     * gone unrefreshed for more than {@link #BLOCKER_MAX_AGE} passes and
     * re-dirtying their owning net. Call this once at the end of every
     * routing pass.
     */
    public void nextPass() {
        Iterator<Map.Entry<Integer, Integer>> it = blockerAge.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Integer> e = it.next();
            int age = e.getValue() + 1;
            if (age > BLOCKER_MAX_AGE) {
                blockers.remove(e.getKey());
                dirty.put(e.getKey(), true);
                it.remove();
            } else {
                e.setValue(age);
            }
        }
    }

    /**
     * Resets all state. Call between passes when a full re-evaluation is
     * desired (e.g. after a board-history restore).
     */
    public void reset() {
        blockers.clear();
        dependents.clear();
        failureCount.clear();
        dirty.clear();
        costMultiplier.clear();
        lastTriedPass.clear();
        blockerAge.clear();
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