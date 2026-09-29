package com.viris.PulseGuard.auth.account;

/**
 * Caps emails the account flows send (reset, verification, email change), so no form can be
 * used to flood an inbox or spray many addresses. Counted whether or not an address has an
 * account, so hitting the limit reveals nothing. If the shared store is down, limits are kept
 * per node instead (LocalFixedWindow), never switched off.
 */
public interface EmailSendThrottle {

    /**
     * @param purpose which flow, e.g. "reset"; each has its own per-address budget, while the
     *                per-IP budget is shared by all of them
     * @return true when another email may be sent now
     */
    boolean tryAcquire(String purpose, String email, String clientIp);
}
