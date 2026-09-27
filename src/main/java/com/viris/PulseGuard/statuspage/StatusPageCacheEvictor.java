package com.viris.PulseGuard.statuspage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops a saved page from the public cache. After commit: evicting earlier would let a
 * concurrent visitor re-cache the old version before the new one is visible. Unpublishing
 * therefore takes effect at once instead of after the TTL.
 */
@Component
public class StatusPageCacheEvictor {

    private static final Logger log = LoggerFactory.getLogger(StatusPageCacheEvictor.class);

    private final CacheManager cacheManager;

    public StatusPageCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onChanged(StatusPageChangedEvent event) {
        Cache cache = cacheManager.getCache(StatusPageCacheConfig.PUBLIC_STATUS_PAGES);
        if (cache == null) {
            return;
        }
        for (String slug : event.slugs()) {
            try {
                cache.evict(slug);
            } catch (RuntimeException e) {
                // Fail open like the rest of the cache: the entry expires with its TTL anyway.
                log.warn("Could not evict status page slug={} from cache: {}", slug, e.getClass().getSimpleName());
            }
        }
    }
}
