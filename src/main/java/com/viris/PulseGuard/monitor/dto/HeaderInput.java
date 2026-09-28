package com.viris.PulseGuard.monitor.dto;

/**
 * A request header in a monitor create or update.
 *
 * @param value null on an update means "keep the value saved for this name", which is how
 *              the form sends back a secret header it was never shown
 */
public record HeaderInput(String name, String value) {
}
