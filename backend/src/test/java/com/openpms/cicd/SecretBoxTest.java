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

    @Test void flippedCiphertextBitFailsTheGcmTag() {
        var box = new SecretBox(KEY);
        var parts = box.seal("secret").split(":");
        var ct = Base64.getDecoder().decode(parts[2]);
        ct[0] ^= 1;
        var bad = "v1:" + parts[1] + ":" + Base64.getEncoder().encodeToString(ct);
        assertThatThrownBy(() -> box.open(bad)).isInstanceOf(IllegalStateException.class);
    }

    @Test void malformedBase64PartIsRefused() {
        var box = new SecretBox(KEY);
        var parts = box.seal("secret").split(":");
        assertThatThrownBy(() -> box.open("v1:" + parts[1] + ":!!!")).isInstanceOf(IllegalStateException.class);
    }

    @Test void nonBase64KeyErrorDoesNotEchoKeyCharacters() {
        assertThatThrownBy(() -> new SecretBox("not*base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("*").doesNotContain("!"));
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
