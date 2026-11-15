package com.viris.PulseGuard.ai;

/**
 * Who may ask the model for an incident's summary right now. Without it, every page view that
 * misses the cache at the same moment pays for its own call, and a provider that keeps failing
 * is paid for on every refresh.
 * <p>
 * A lease is never released early: it simply expires, so a failed call is retried at most once
 * per lease. If the shared store is down, implementations hand out leases per node
 * (LocalFixedWindow): an outage must neither stop summaries nor lift the limit.
 */
@FunctionalInterface
public interface SummaryLease {

    /** @return true when the caller may ask the model for this incident's summary now. */
    boolean tryAcquire(Long incidentId);
}
