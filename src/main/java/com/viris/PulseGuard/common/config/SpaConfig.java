package com.viris.PulseGuard.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.time.Duration;

/**
 * Serves the built React app (copied into {@code static/} by the Dockerfile) from the same
 * origin as the API, so the frontend's relative {@code /api/...} calls need no CORS.
 * <p>
 * Real files are served as they are. Any other path outside the API gets {@code index.html},
 * so reloading a client-side route such as {@code /monitors/5} works. Controllers and actuator
 * are mapped before these handlers, and API paths never fall back: an unknown {@code /api/...}
 * stays a 404, not a page of HTML.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

    static final String STATIC = "classpath:/static/";
    static final String INDEX = "index.html";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Vite puts a content hash in every file name here, so a changed file is a new URL.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        // index.html names the current hashed assets; never cached, so a deploy shows at once.
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(false)
                .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null || isBackendPath(resourcePath)) {
                return file;
            }
            return super.getResource(INDEX, location);
        }

        private static boolean isBackendPath(String path) {
            return path.startsWith("api/") || path.startsWith("actuator/") || path.equals("error");
        }
    }
}
