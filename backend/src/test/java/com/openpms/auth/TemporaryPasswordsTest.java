package com.openpms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 임시 비밀번호 generation: every kind of character, nothing that reads alike, at least 16 long. */
class TemporaryPasswordsTest {
    @Test
    void aGeneratedPasswordHasEveryKindAndNoLookAlikes() {
        for (int i = 0; i < 500; i++) {
            var p = TemporaryPasswords.generate(10);
            assertThat(p).hasSize(16);
            for (var kind : PasswordPolicy.CLASSES) assertThat(PasswordPolicy.has(p, kind)).as(kind + " in " + p).isTrue();
            assertThat(p.chars().noneMatch(c -> "0O1lI".indexOf(c) >= 0)).as(p).isTrue();
            assertThat(p.chars().allMatch(c -> (TemporaryPasswords.LOWER + TemporaryPasswords.UPPER + TemporaryPasswords.DIGITS + TemporaryPasswords.SPECIAL).indexOf(c) >= 0)).as(p).isTrue();
        }
        assertThat(TemporaryPasswords.generate(24)).hasSize(24);
        assertThat(TemporaryPasswords.generate(10)).isNotEqualTo(TemporaryPasswords.generate(10));
    }
}
