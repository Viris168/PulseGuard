package com.viris.PulseGuard.statuspage;

import com.viris.PulseGuard.statuspage.dto.PublicStatusPageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * The public status page's data, open to anyone (SecurityConfig). Under /api rather than at
 * /status/{slug}, which is the frontend's route for the page itself.
 */
@RestController
@RequiredArgsConstructor
public class PublicStatusPageController {

    /**
     * Revalidate every time. Load is taken by the server-side Redis cache, which saving the page
     * evicts at once; a browser or CDN copy would keep an unpublished page visible for its max-age.
     */
    private static final CacheControl CACHE = CacheControl.noCache();

    private final PublicStatusPageService publicStatusPageService;

    @GetMapping("/api/status/{slug}")
    public ResponseEntity<PublicStatusPageResponse> get(@PathVariable String slug) {
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(publicStatusPageService.render(slug.toLowerCase(Locale.ROOT)));
    }
}
