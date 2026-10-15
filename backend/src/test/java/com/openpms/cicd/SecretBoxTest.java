package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpms.common.ApiException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretBoxTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void sealsAndOpens() {
        var box = new SecretBox(KEY);
        var sealed = box.seal("glpat-abcdef1234");
        assertThat(sealed).startsWith("v1:").doesNotContain("glpat");
        assertThat(box.open(sealed)).isEqualTo("glpat-abcdef1234");
    }

    @Test void sameTextSealsDifferentlyEachTime() {
        var box = new SecretBox(KEY);
        assertThat(box.seal("x")).isNotEqualTo(box.seal("x"));
    }

    @Test void tamperedValueIsRefused() {
        var box = new SecretBox(KEY);
        var sealed = box.seal("secret");
        var bad = sealed.substring(0, sealed.length() - 2) + (sealed.endsWith("A") ? "B" : "A") + "=";
        assertThatThrownBy(() -> box.open(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test void noKeyMeansNotReady() {
        var box = new SecretBox("");
        assertThat(box.ready()).isFalse();
        assertThatThrownBy(box::requireReady).isInstanceOf(ApiException.class).hasMessageContaining("OPENPMS_SECRET_KEY");
    }

    @Test void wrongKeyLengthIsRefusedAtStart() {
        assertThatThrownBy(() -> new SecretBox(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32");
    }

    @Test void hintShowsOnlyTheLastFour() {
        assertThat(SecretBox.hint("glpat-abcdef1234")).isEqualTo("1234");
        assertThat(SecretBox.hint("abc")).isEqualTo("****");
    }
}
