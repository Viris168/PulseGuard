package com.viris.PulseGuard.ai.help;

import com.viris.PulseGuard.ai.help.dto.HelpArticleResponse;
import com.viris.PulseGuard.ai.help.dto.HelpArticleSummary;
import com.viris.PulseGuard.common.exception.HelpArticleNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The help docs for the /docs page, open to anyone (SecurityConfig). Read from the Markdown files
 * that HelpArticles loads at startup; no database and no AI, so the docs work when both are down.
 */
@RestController
@RequiredArgsConstructor
public class HelpController {

    /** The docs only change with a deploy, so a short shared cache is safe. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final HelpArticles helpArticles;

    @GetMapping("/api/help")
    public ResponseEntity<List<HelpArticleSummary>> list() {
        List<HelpArticleSummary> articles = helpArticles.all().stream()
                .map(a -> new HelpArticleSummary(a.slug(), a.title(), a.summary()))
                .toList();
        return ResponseEntity.ok().cacheControl(CACHE).body(articles);
    }

    @GetMapping("/api/help/{slug}")
    public ResponseEntity<HelpArticleResponse> get(@PathVariable String slug) {
        HelpArticles.Article a = helpArticles.find(slug)
                .orElseThrow(() -> new HelpArticleNotFoundException(slug));
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(new HelpArticleResponse(a.slug(), a.title(), a.summary(), a.body()));
    }
}
