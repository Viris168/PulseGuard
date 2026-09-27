package com.viris.PulseGuard.statuspage;

import java.util.Set;

/** The slugs whose public page is now stale: the current one, and the old one after a rename. */
public record StatusPageChangedEvent(Set<String> slugs) {
}
