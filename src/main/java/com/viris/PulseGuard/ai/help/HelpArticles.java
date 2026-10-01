package com.viris.PulseGuard.ai.help;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PulseGuard's help docs (AI_MILESTONE_3.md): the Markdown files in {@code src/main/resources/help/},
 * read once. Each article has front matter (title, summary) and {@code ##} sections that each
 * make sense alone, because each becomes one search chunk. HelpDocsFactsTest keeps them honest.
 */
@Component
public class HelpArticles {

    static final String LOCATION = "classpath:help/*.md";

    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---\\n(.*?)\\n---\\n", Pattern.DOTALL);

    /**
     * One article. {@code intro} is the text before the first section; {@code body} is all the
     * Markdown after the front matter, as the docs page shows it.
     */
    public record Article(String slug, String title, String summary, String intro, String body,
                          List<Section> sections) {
    }

    /** One {@code ##} section; {@code anchor} is its id on the docs page. */
    public record Section(String heading, String anchor, String content) {
    }

    /** One section as the search index stores it; {@code position} is its place in the article. */
    public record Chunk(String article, String title, String heading, String anchor, int position, String content) {

        /** What gets embedded: the section with its titles, so it still makes sense alone. */
        public String embeddingText() {
            return title + " › " + heading + "\n\n" + content;
        }
    }

    private final List<Article> articles;

    public HelpArticles() {
        this(LOCATION);
    }

    HelpArticles(String location) {
        this.articles = load(location);
    }

    /** Every article, by slug. */
    public List<Article> all() {
        return articles;
    }

    /** The article with this slug, if there is one. */
    public Optional<Article> find(String slug) {
        return articles.stream().filter(a -> a.slug().equals(slug)).findFirst();
    }

    /** Every section of every article, in order. */
    public List<Chunk> chunks() {
        List<Chunk> chunks = new ArrayList<>();
        for (Article a : articles) {
            for (int i = 0; i < a.sections().size(); i++) {
                Section s = a.sections().get(i);
                chunks.add(new Chunk(a.slug(), a.title(), s.heading(), s.anchor(), i, s.content()));
            }
        }
        return chunks;
    }

    private static List<Article> load(String location) {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources(location);
            List<Article> loaded = new ArrayList<>();
            for (Resource file : files) {
                String name = file.getFilename();
                if (name == null || !name.endsWith(".md")) {
                    continue;
                }
                loaded.add(parse(name.substring(0, name.length() - 3), file.getContentAsString(StandardCharsets.UTF_8)));
            }
            loaded.sort(Comparator.comparing(Article::slug));
            return List.copyOf(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the help docs", e);
        }
    }

    static Article parse(String slug, String markdown) {
        String text = markdown.replace("\r\n", "\n");
        Matcher front = FRONT_MATTER.matcher(text);
        if (!front.find()) {
            throw new IllegalStateException("Help article " + slug + " has no front matter");
        }
        String title = field(front.group(1), "title", slug);
        String summary = field(front.group(1), "summary", slug);

        String body = text.substring(front.end());
        List<Section> sections = new ArrayList<>();
        String intro = "";
        String[] parts = ("\n" + body).split("\n(?=## )");
        for (String part : parts) {
            if (!part.startsWith("## ")) {
                intro = part.strip();
                continue;
            }
            int lineEnd = part.indexOf('\n');
            String heading = (lineEnd < 0 ? part.substring(3) : part.substring(3, lineEnd)).strip();
            String content = lineEnd < 0 ? "" : part.substring(lineEnd + 1).strip();
            sections.add(new Section(heading, anchor(heading), content));
        }
        return new Article(slug, title, summary, intro, body.strip(), List.copyOf(sections));
    }

    /** "What counts as a failed check" → "what-counts-as-a-failed-check"; "can't" → "cant". */
    static String anchor(String heading) {
        String plain = Normalizer.normalize(heading, Normalizer.Form.NFKD).replaceAll("\\p{M}", "")
                .replaceAll("['’]", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String field(String frontMatter, String name, String slug) {
        Matcher m = Pattern.compile("(?m)^" + name + ": (.+)$").matcher(frontMatter);
        if (!m.find()) {
            throw new IllegalStateException("Help article " + slug + " has no " + name);
        }
        return m.group(1).strip();
    }
}
