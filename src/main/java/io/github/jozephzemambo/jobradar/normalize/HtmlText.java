package io.github.jozephzemambo.jobradar.normalize;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

/** Converts ATS description HTML to plain text for scoring and search. */
public final class HtmlText {

    private HtmlText() {
    }

    /**
     * Greenhouse returns its {@code content} field entity-escaped ({@code &lt;h2&gt;...}), so a single parse would
     * leave literal tags in the text. Unescaping first, then parsing, handles both escaped and raw HTML.
     */
    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String unescaped = Parser.unescapeEntities(html, false);
        return Jsoup.parse(unescaped).text().strip();
    }
}
