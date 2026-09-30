package com.viris.PulseGuard.enumeration;

/**
 * How an Ask AI chat message ended. COMPLETE: in full. PARTIAL: the person stopped it or the
 * connection dropped, and what was written so far is kept. FAILED: the model gave no answer.
 */
public enum MessageStatus { COMPLETE, PARTIAL, FAILED }
