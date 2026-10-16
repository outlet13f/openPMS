package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.openpms.common.ApiException;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImageTagsTest {
    @Test void tagIsTheLastColonAfterTheLastSlash() {
        assertThat(ImageTags.tagOf("registry.local:5000/web/portal:1a2b3c4d")).isEqualTo("1a2b3c4d");
        assertThat(ImageTags.tagOf("registry.local:5000/web/portal")).isEmpty();          // the port is no tag
        assertThat(ImageTags.tagOf("web/portal:main-1a2b3c4@sha256:abcdef")).isEqualTo("main-1a2b3c4");
        assertThat(ImageTags.tagOf(null)).isEmpty();
        assertThat(ImageTags.tagOf("app:" + "x".repeat(129))).isEmpty();                  // longer than 128
    }

    @Test void defaultPatternFindsTheCommit() {
        assertThat(ImageTags.sha("web/portal:1A2B3C4D5E6F", null)).contains("1a2b3c4d5e6f");
        assertThat(ImageTags.sha("web/portal:main-1a2b3c4", null)).contains("1a2b3c4");
        assertThat(ImageTags.sha("web/portal:v1.2.3", null)).isEmpty();
        assertThat(ImageTags.sha("web/portal:20261015", null)).isEmpty();   // digits only: a date or a build number
        assertThat(ImageTags.sha("web/portal", null)).isEmpty();
    }

    @Test void laterMatchIsTriedWhenTheFirstIsNoCommit() {
        assertThat(ImageTags.sha("app:20261015-1a2b3c4", null)).contains("1a2b3c4");            // a date first
        assertThat(ImageTags.sha("app:20261015-1a2b3c4-9f8e7d6a", null)).contains("1a2b3c4");   // two hex runs: the first valid one
        assertThat(ImageTags.sha("app:20261015-20261016", null)).isEmpty();
    }

    @Test void ownPatternUsesTheFirstGroup() {
        assertThat(ImageTags.sha("api:release-2026.10-g9f8e7d6", "-g([0-9a-f]{7,40})$")).contains("9f8e7d6");
        assertThat(ImageTags.sha("api:release-2026.10", "-g([0-9a-f]{7,40})$")).isEmpty();
        // a group that matches something that is not a commit number gives nothing
        assertThat(ImageTags.sha("api:build-xyz", "build-(\\w+)")).isEmpty();
    }

    @Test void runawayPatternGivesUpQuickly() {
        @SuppressWarnings("unchecked")
        Optional<String>[] out = new Optional[1];
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> out[0] = ImageTags.sha("app:" + "a".repeat(60) + "!", "(a+)+$"));
        assertThat(out[0]).isEmpty();
    }

    @Test void validation() {
        assertThat(ImageTags.validate(null)).isNull();
        assertThat(ImageTags.validate("  ")).isNull();
        assertThat(ImageTags.validate(" -g([0-9a-f]{7,40})$ ")).isEqualTo("-g([0-9a-f]{7,40})$");
        assertThatThrownBy(() -> ImageTags.validate("([0-9")).isInstanceOf(ApiException.class).hasMessageContaining("이미지 태그 규칙");
        assertThatThrownBy(() -> ImageTags.validate("a".repeat(201))).isInstanceOf(ApiException.class);
    }
}
