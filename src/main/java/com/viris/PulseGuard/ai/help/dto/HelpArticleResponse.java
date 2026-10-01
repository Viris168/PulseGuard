package com.viris.PulseGuard.ai.help.dto;

/**
 * GET /api/help/{slug}; mirrors {@code HelpArticle} in frontend/src/api/help.ts. {@code markdown}
 * is the article without its front matter; the page gives each {@code ##} heading the id that
 * HelpArticles.anchor gives it, so source links land on the right section.
 */
public record HelpArticleResponse(String slug, String title, String summary, String markdown) {
}
