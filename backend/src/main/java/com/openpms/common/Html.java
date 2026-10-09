package com.openpms.common;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

/**
 * Descriptions and comments are stored as sanitised HTML plus plain text for search (architecture.md §1).
 * The allow-list covers what the editor produces: paragraphs, emphasis, lists, code, links and @mentions.
 */
public final class Html {
    private Html() {}

    private static final PolicyFactory POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "b", "em", "i", "u", "s", "code", "pre", "blockquote", "ul", "ol", "li", "h2", "h3", "h4", "span", "a")
            .allowUrlProtocols("http", "https", "mailto")
            .allowAttributes("href").onElements("a")
            .requireRelsOnLinks("noopener", "noreferrer")
            .allowAttributes("class").matching(Pattern.compile("mention")).onElements("span")
            .allowAttributes("data-user-id").matching(Pattern.compile("[0-9a-fA-F-]{36}")).onElements("span")
            .toFactory();

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern SPACE = Pattern.compile("\\s+");
    private static final Pattern MENTION = Pattern.compile("data-user-id=\"([0-9a-fA-F-]{36})\"");

    public static String sanitize(String html) {
        return html == null ? "" : POLICY.sanitize(html);
    }

    /** Plain text for search and "is it empty?" checks. */
    public static String text(String html) {
        if (html == null) return "";
        var t = TAG.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return SPACE.matcher(t).replaceAll(" ").trim();
    }

    /** People mentioned with the editor's @-mention markup. */
    public static List<UUID> mentions(String html) {
        var out = new LinkedHashSet<UUID>();
        var m = MENTION.matcher(html == null ? "" : html);
        while (m.find()) {
            try {
                out.add(UUID.fromString(m.group(1)));
            } catch (IllegalArgumentException ignored) {
                // not a user id
            }
        }
        return List.copyOf(out);
    }
}
