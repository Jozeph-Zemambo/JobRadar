package io.github.jozephzemambo.jobradar.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlTextTest {

    @Test
    void stripsEntityEscapedHtmlAsGreenhouseSendsIt() {
        String greenhouse = "&lt;h2&gt;&lt;strong&gt;Who we are &lt;/strong&gt;&lt;/h2&gt;"
                + "&lt;p&gt;&lt;span style=&quot;font-weight: 400;&quot;&gt;Stripe is a financial "
                + "infrastructure platform&lt;/span&gt;&lt;/p&gt;";
        assertThat(HtmlText.toPlainText(greenhouse))
                .isEqualTo("Who we are Stripe is a financial infrastructure platform");
    }

    @Test
    void stripsRawHtml() {
        assertThat(HtmlText.toPlainText("<ul><li>Java</li><li>Spring &amp; SQL</li></ul>"))
                .isEqualTo("Java Spring & SQL");
    }

    @Test
    void blankInputGivesEmptyString() {
        assertThat(HtmlText.toPlainText(null)).isEmpty();
        assertThat(HtmlText.toPlainText("  ")).isEmpty();
    }
}
