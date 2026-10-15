package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RefParserTest {
    static List<Long> nums(List<RefParser.Ref> refs) {
        return refs.stream().map(RefParser.Ref::number).toList();
    }

    @Test void findsHashNumbersInText() {
        assertThat(nums(RefParser.inText("로그인 오류 고침 #123, (#45) [#6]\n#7 끝", "message"))).containsExactly(123L, 45L, 6L, 7L);
    }

    @Test void ignoresLookalikes() {
        assertThat(RefParser.inText("abc#12 #12a &#123; http://x/#99 issue#5 #", "message")).isEmpty();
    }

    @Test void keepsTheFirstSourceAndOrder() {
        var refs = RefParser.merge(RefParser.inBranch("feature/12-login"), RefParser.inText("#12 #13", "title"));
        assertThat(refs).containsExactly(new RefParser.Ref(12, "branch"), new RefParser.Ref(13, "title"));
    }

    @Test void branches() {
        assertThat(nums(RefParser.inBranch("feature/123-login"))).containsExactly(123L);
        assertThat(nums(RefParser.inBranch("123_hotfix"))).containsExactly(123L);
        assertThat(nums(RefParser.inBranch("fix/77"))).containsExactly(77L);
        assertThat(RefParser.inBranch("release/2026-10")).isEmpty();   // 날짜이지 작업 2026이 아님
        assertThat(RefParser.inBranch("hotfix/88-crash")).isEmpty();
        assertThat(RefParser.inBranch("v1.2.3")).isEmpty();
        assertThat(RefParser.inBranch("main")).isEmpty();
        assertThat(RefParser.inBranch(null)).isEmpty();
    }

    @Test void capsAtTwentyAndTwelveDigits() {
        var many = new StringBuilder();
        for (int i = 1; i <= 30; i++) many.append(" #").append(i);
        assertThat(RefParser.inText(many.toString(), "message")).hasSize(20);
        assertThat(RefParser.inText("#1234567890123", "message")).isEmpty();
    }
}
