# 임시 비밀번호(관리자가 메일 없이 사용자 등록·재발급) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 관리자가 메일 없이 사용자를 만들거나 기존 사용자에게 임시 비밀번호를 발급하고, 사용자가 바꾸기 전까지 다시 보고 전달하며, 사용자는 첫 로그인 때 반드시 새 비밀번호를 정한다.

**Architecture:** 새 테이블 `temporary_passwords`(사용자마다 한 행, 평문)와 이를 다루는 `auth/TemporaryPasswords` 컴포넌트가 중심이다. 로그인은 임시 비밀번호가 맞으면 세션 대신 일회용 `password_change` 토큰을 403 문제 상세의 `current`에 담아 돌려주고, 새 엔드포인트 `POST /auth/password-change`가 비밀번호를 바꾸며 로그인시킨다. 관리 API는 만들기(`method`), 다시 발급, 보기를 제공하고, 화면은 관리 · 사용자의 대화상자와 로그인 화면 안의 변경 폼으로 이를 쓴다.

**Tech Stack:** Spring Boot 4 / Java 21 / jOOQ / Flyway / PostgreSQL 17, React 19 / Vite / TypeScript / TanStack Query, JUnit 5 + MockMvc, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-12-temporary-password-design.md`

## Global Constraints

- 클린룸: OpenProject의 소스·저장소·JS 번들·네트워크 호출은 읽지 않는다.
- 임시 비밀번호 평문은 `temporary_passwords`와 세 응답(만들기·다시 발급·보기) 밖으로 나가지 않는다: 로그·웹훅·활동·메일·토스트·주소창·`idempotency_keys`에 남기지 않는다.
- 세 응답에는 `Cache-Control: no-store`.
- 임시 비밀번호: `SecureRandom`, 길이 max(16, 정책 최소 길이), 문자 집합에서 `0 O 1 l I` 제외, 특수 문자는 `!@#$%*-_+=?`만, 네 종류 하나씩 반드시.
- 유효 시간: `auth.invitation_ttl_hours`(기본 168). 변경 토큰: 10분, 일회용, 목적 `password_change`.
- 권한: 사용자 관리자(관리자 또는 `global.manage_users`). 관리자 계정은 관리자만. 나 자신 422, 잠김 422, 삭제·없음 404.
- 화면 문구는 이 계획의 문구를 그대로 쓴다(`frontend/src/i18n/ko.ts`).
- 커밋은 경로를 지정한다(`git add -A` 금지). 제목은 영어, 본문 설명은 한국어, 마지막 줄 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. 메시지는 스크래치 파일에 쓰고 `git commit -F <파일>`.
- 큰 수정은 Edit 도구나 스크래치 폴더의 Python 스크립트로 한다. bash heredoc으로 코드를 패치하지 않는다. prettier를 돌리지 않는다.
- 요청은 가상 스레드에서 돈다: `synchronized`나 `computeIfAbsent` 안에서 DB를 기다리지 않는다.
- 가짜 데이터 계층(`frontend/src/api/fake`)은 실제 API와 같은 규칙·문구로 맞춘다.
- 산출물 문서 줄바꿈: 03·04·05·10과 `replica/` 문서는 CRLF, 01·02·06·07·08·09와 README는 LF. 매뉴얼의 기존 번호 붙은 제목은 바꾸지 않는다.
- 이 Mac에서는 `podman`을 쓴다(README 그대로). 명령은 저장소 루트에서 실행한다.

## Review Focus

1. API 토큰(Bearer)으로 임시 비밀번호를 만들기·발급·보기 → 403 "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다."(토큰이 사람 비밀번호를 얻는 길이 되면 안 됨) — Task 3 테스트.
2. 임시 비밀번호가 있는 사람이 재설정 링크·초대 수락·본인 변경으로 비밀번호를 정함 → 행이 지워지고 관리자 보기 404 — Task 4 테스트.
3. 변경 토큰을 받은 뒤 관리자가 계정을 잠금 → `password-change` 410, 로그인되지 않음 — Task 4 테스트.
4. 만료된 임시 비밀번호로 로그인 → 401 `temporary_password_expired`, 해시가 비워져 같은 비밀번호로는 계속 401, 다시 발급하면 다시 쓸 수 있음 — Task 4 테스트.
5. 임시 비밀번호가 있는 사용자를 삭제(익명화) → 평문 행이 남지 않음 — Task 3 테스트.

---

## 파일 구조

| 파일 | 할 일 |
| --- | --- |
| Create `backend/src/main/resources/db/migration/V19__temporary_passwords.sql` | 테이블, 인덱스, `user_tokens.purpose` 넓히기 |
| Create `backend/src/main/java/com/openpms/auth/TemporaryPasswords.java` | 만들기·발급·보기·지우기·만료(유일하게 `temporary_passwords`를 만지는 곳) |
| Create `backend/src/main/java/com/openpms/admin/TemporaryPasswordController.java` | `POST`/`GET /api/v1/admin/users/{id}/temporary-password` |
| Modify `backend/src/main/java/com/openpms/admin/AdminController.java` | `createUser`의 `method`, `AdminUser.temporaryPasswordUntil`, 삭제 때 행 지우기 |
| Modify `backend/src/main/java/com/openpms/auth/AuthController.java` | 로그인 분기, `POST /auth/password-change`, 재설정·초대 수락에서 지우기 |
| Modify `backend/src/main/java/com/openpms/notification/AccountController.java` | 본인 변경에서 지우기 |
| Modify `backend/src/main/java/com/openpms/auth/SecurityConfig.java` | `/api/v1/auth/password-change` 공개 |
| Modify `backend/src/main/java/com/openpms/auth/ApiTokenFilter.java` | `…/temporary-password`를 토큰에 닫기 |
| Modify `backend/src/main/java/com/openpms/common/IdempotencyFilter.java` | 두 경로를 저장에서 빼기 |
| Modify `backend/src/main/java/com/openpms/jobs/Housekeeping.java` | 만료 행 정리 |
| Create `backend/src/test/java/com/openpms/auth/TemporaryPasswordsTest.java` | 비밀번호 생성 규칙(단위) |
| Create `backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java` | API 흐름 전부 |
| Modify `backend/src/test/java/com/openpms/auth/OidcIT.java` | SSO는 그대로 됨 |
| Modify `frontend/src/api/types.ts`, `api.ts`, `http.ts`, `admin-hooks.ts` | 타입·메서드 |
| Modify `frontend/src/api/fake/db.ts`, `fake/index.ts`, `fake/admin.ts`; Create `fake/temporary-passwords.ts` | 가짜 계층 |
| Create `frontend/src/pages/new-password.tsx` | 새 비밀번호 칸·검사·변경 폼(`auth-links.tsx`에서 옮김) |
| Modify `frontend/src/pages/auth-links.tsx`, `login.tsx` | 공용 모듈 사용, 로그인 안의 변경 폼 |
| Modify `frontend/src/pages/admin/users.tsx` | 사용자 추가, 결과 대화상자, 행 메뉴, 상태 칸 |
| Modify `frontend/src/i18n/ko.ts` | 문구 |
| Create `frontend/e2e/f19-temporary-password.spec.ts` | e2e F19 |

---

### Task 1: 마이그레이션 V19, jOOQ 코드, 복원 지점

**Files:**
- Create: `backend/src/main/resources/db/migration/V19__temporary_passwords.sql`
- Modify (generated): `backend/src/main/generated/com/openpms/db/**`

**Interfaces:**
- Produces: 테이블 `temporary_passwords(user_id, password, expires_at, issued_by, issued_at)`, jOOQ `Tables.TEMPORARY_PASSWORDS`, `TemporaryPasswordsRecord`; `user_tokens.purpose`에 `'password_change'` 허용.

주의: `reset-db`는 개발 DB를 지운다. 손으로 만든 계정(`admin@openpms.local`, 초대한 `ds.jang@okestro.com`)도 사라진다. CLAUDE.md의 복원 지점 절차라서 그대로 한다.

- [ ] **Step 1: 마이그레이션 파일 쓰기**

`backend/src/main/resources/db/migration/V19__temporary_passwords.sql`:

```sql
-- 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): 관리자가 메일 없이 사용자를 등록하거나
-- 다시 발급한다. 사용자마다 한 행이고, 행이 있으면 지금 비밀번호(users.password_hash)는 임시라서 첫 로그인 때 바꿔야 한다.
-- 관리자가 전달하도록 평문을 둔다. 비밀번호가 정해지거나 기한이 지나면 앱이 행을 지운다(auth/TemporaryPasswords).
create table temporary_passwords (
  user_id    uuid primary key references users (id) on delete cascade,
  password   text not null,                                   -- API로는 관리 · 사용자의 보기에만 나간다
  expires_at timestamptz not null,
  issued_by  uuid references users (id) on delete set null,
  issued_at  timestamptz not null default now()
);
create index temporary_passwords_issued_by_idx on temporary_passwords (issued_by);
create index temporary_passwords_expires_idx on temporary_passwords (expires_at);

-- 임시 비밀번호로 로그인한 사람에게 주는 일회용 변경 토큰
alter table user_tokens drop constraint user_tokens_purpose_check;
alter table user_tokens add constraint user_tokens_purpose_check check (purpose in ('invitation', 'password_reset', 'password_change'));
```

- [ ] **Step 2: 개발 DB를 새로 만들고 마이그레이션 적용**

```bash
podman stop openpms-app
bash backend/scripts/dev.sh reset-db
bash backend/scripts/dev.sh run
```

Expected: 마지막 줄 `app: http://127.0.0.1:8090`.

- [ ] **Step 3: 적용 확인**

```bash
podman exec openpms-db psql -U openpms -d openpms -At -c "select version from flyway_schema_history where version = '19'" -c "select pg_get_constraintdef(oid) from pg_constraint where conname = 'user_tokens_purpose_check'"
```

Expected: `19`와 `CHECK ((purpose = ANY (ARRAY['invitation'::text, 'password_reset'::text, 'password_change'::text])))`.

- [ ] **Step 4: jOOQ 코드 다시 만들기**

```bash
bash backend/scripts/dev.sh gradle jooqCodegen
ls backend/src/main/generated/com/openpms/db/tables/TemporaryPasswords.java backend/src/main/generated/com/openpms/db/tables/records/TemporaryPasswordsRecord.java
```

Expected: 두 파일이 있다.

- [ ] **Step 5: 복원 지점 다시 만들기**

```bash
podman stop openpms-app
bash backend/scripts/dev.sh snapshot-db
bash backend/scripts/dev.sh run
```

Expected: `CREATE DATABASE`, 그리고 `app: http://127.0.0.1:8090`.

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/resources/db/migration/V19__temporary_passwords.sql backend/src/main/generated
git status --short backend/src/main/generated | head
git commit -F /tmp/openpms-scratch/msg-task1.txt
```

메시지(`/tmp/openpms-scratch/msg-task1.txt`):

```
feat(db): V19 temporary_passwords and the password_change token purpose

임시 비밀번호(관리자가 메일 없이 등록·재발급)용 테이블과, 첫 로그인 변경 토큰 목적을 더합니다.
jOOQ 생성 코드를 함께 커밋합니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 2: `TemporaryPasswords` 컴포넌트

**Files:**
- Create: `backend/src/main/java/com/openpms/auth/TemporaryPasswords.java`
- Test: `backend/src/test/java/com/openpms/auth/TemporaryPasswordsTest.java`

**Interfaces:**
- Consumes: Task 1의 `TEMPORARY_PASSWORDS`; 기존 `Tokens.revokeAll(UUID, String)`, `Sessions.endAll(UUID)`, `PasswordPolicy.current().minLength()`, `PasswordPolicy.check(String, String)`, `SettingsRepo.integer(String, int)`, `PasswordEncoder`.
- Produces (`com.openpms.auth.TemporaryPasswords`, Spring `@Component`):
  - `record Issued(String temporaryPassword, OffsetDateTime expiresAt)`
  - `record Shown(String temporaryPassword, OffsetDateTime expiresAt, Dto.IdName issuedBy, OffsetDateTime issuedAt)`
  - `static String generate(int minLength)`
  - `Issued issue(UUID userId, UUID issuedBy)` — 비밀번호 교체, 행 upsert, 실패 잠금 해제, 재설정·변경 토큰 무효, 모든 세션 종료
  - `Optional<Shown> shown(UUID userId, UUID viewer)` — 기한 안의 것만
  - `OffsetDateTime expiresAt(UUID userId)` — 행이 없으면 null
  - `Map<UUID, OffsetDateTime> untilByUser()` — 모든 행(지난 것 포함)
  - `void clear(UUID userId)`
  - `boolean expire(UUID userId, OffsetDateTime now)` — 기한이 지난 행만 지우고 `password_hash`를 null로
  - `int expireAll(OffsetDateTime now)`

- [ ] **Step 1: 실패하는 단위 테스트 쓰기**

`backend/src/test/java/com/openpms/auth/TemporaryPasswordsTest.java`:

```java
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
```

- [ ] **Step 2: 실패 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordsTest`
Expected: 컴파일 실패(`cannot find symbol: class TemporaryPasswords`).

- [ ] **Step 3: 컴포넌트 구현**

`backend/src/main/java/com/openpms/auth/TemporaryPasswords.java`:

```java
package com.openpms.auth;

import static com.openpms.db.Tables.TEMPORARY_PASSWORDS;
import static com.openpms.db.Tables.USERS;

import com.openpms.api.Dto;
import com.openpms.common.SettingsRepo;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): an administrator gives someone a password
 * without mail. While a row is here the account's password is temporary: signing in with it only earns a change token
 * (AuthController.login), and the administrator may look it up again to pass it on. Every path that sets a password clears it.
 */
@Component
public class TemporaryPasswords {
    private static final Logger log = LoggerFactory.getLogger(TemporaryPasswords.class);
    // no 0 O 1 l I: retyped from a chat message without mistakes; specials that survive messengers
    static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    static final String DIGITS = "23456789";
    static final String SPECIAL = "!@#$%*-_+=?";
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Issued(String temporaryPassword, OffsetDateTime expiresAt) {}

    public record Shown(String temporaryPassword, OffsetDateTime expiresAt, Dto.IdName issuedBy, OffsetDateTime issuedAt) {}

    private final DSLContext db;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final SettingsRepo settings;
    private final Tokens tokens;
    private final Sessions sessions;

    public TemporaryPasswords(DSLContext db, PasswordEncoder encoder, PasswordPolicy policy, SettingsRepo settings, Tokens tokens, Sessions sessions) {
        this.db = db;
        this.encoder = encoder;
        this.policy = policy;
        this.settings = settings;
        this.tokens = tokens;
        this.sessions = sessions;
    }

    /** At least 16 characters (or the policy's minimum), one of each kind, shuffled: passes any password policy. */
    static String generate(int minLength) {
        var all = LOWER + UPPER + DIGITS + SPECIAL;
        var chars = new ArrayList<Character>();
        for (var set : List.of(LOWER, UPPER, DIGITS, SPECIAL)) chars.add(set.charAt(RANDOM.nextInt(set.length())));
        while (chars.size() < Math.max(16, minLength)) chars.add(all.charAt(RANDOM.nextInt(all.length())));
        Collections.shuffle(chars, RANDOM);
        var sb = new StringBuilder(chars.size());
        chars.forEach(sb::append);
        return sb.toString();
    }

    /** A new temporary password replaces the person's password (the caller has checked who may). Signs them out everywhere. */
    public Issued issue(UUID userId, UUID issuedBy) {
        var password = generate(policy.current().minLength());
        policy.check("password", password);
        var now = OffsetDateTime.now();
        var expiresAt = now.plusHours(settings.integer("auth.invitation_ttl_hours", 168));
        db.update(USERS).set(USERS.PASSWORD_HASH, encoder.encode(password)).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL)
                .where(USERS.ID.eq(userId)).execute();
        db.insertInto(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.USER_ID, userId).set(TEMPORARY_PASSWORDS.PASSWORD, password)
                .set(TEMPORARY_PASSWORDS.EXPIRES_AT, expiresAt).set(TEMPORARY_PASSWORDS.ISSUED_BY, issuedBy).set(TEMPORARY_PASSWORDS.ISSUED_AT, now)
                .onConflict(TEMPORARY_PASSWORDS.USER_ID).doUpdate()
                .set(TEMPORARY_PASSWORDS.PASSWORD, password).set(TEMPORARY_PASSWORDS.EXPIRES_AT, expiresAt)
                .set(TEMPORARY_PASSWORDS.ISSUED_BY, issuedBy).set(TEMPORARY_PASSWORDS.ISSUED_AT, now)
                .execute();
        tokens.revokeAll(userId, "password_reset");
        tokens.revokeAll(userId, "password_change");
        sessions.endAll(userId);
        log.info("temporary password issued for user {} by {}", userId, issuedBy);
        return new Issued(password, expiresAt);
    }

    /** The one in force, for the administrator to pass on; empty when there is none or it ran out. */
    public Optional<Shown> shown(UUID userId, UUID viewer) {
        var r = db.selectFrom(TEMPORARY_PASSWORDS)
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(userId), TEMPORARY_PASSWORDS.EXPIRES_AT.gt(OffsetDateTime.now())).fetchOne();
        if (r == null) return Optional.empty();
        var by = r.getIssuedBy() == null ? null
                : db.select(USERS.ID, USERS.NAME).from(USERS).where(USERS.ID.eq(r.getIssuedBy())).fetchOne(x -> new Dto.IdName(x.value1(), x.value2()));
        log.info("temporary password of user {} looked up by {}", userId, viewer);
        return Optional.of(new Shown(r.getPassword(), r.getExpiresAt(), by, r.getIssuedAt()));
    }

    /** When the temporary password runs out, or null when the password is not temporary. */
    public OffsetDateTime expiresAt(UUID userId) {
        return db.select(TEMPORARY_PASSWORDS.EXPIRES_AT).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId))
                .fetchOne(TEMPORARY_PASSWORDS.EXPIRES_AT);
    }

    /** Until when each one runs, run-out ones too until token-cleanup takes them (관리 · 사용자 shows it; never the password). */
    public Map<UUID, OffsetDateTime> untilByUser() {
        return db.select(TEMPORARY_PASSWORDS.USER_ID, TEMPORARY_PASSWORDS.EXPIRES_AT).from(TEMPORARY_PASSWORDS)
                .fetchMap(TEMPORARY_PASSWORDS.USER_ID, TEMPORARY_PASSWORDS.EXPIRES_AT);
    }

    /** The person set a password of their own (any path), or the account went: the temporary one is gone. */
    public void clear(UUID userId) {
        db.deleteFrom(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId)).execute();
    }

    /**
     * Ran out: the row and the password itself go, or the old temporary password would work as an ordinary one.
     * Only a row that has really run out (a fresh one issued meanwhile stays). @return whether one went
     */
    public boolean expire(UUID userId, OffsetDateTime now) {
        int n = db.deleteFrom(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(userId), TEMPORARY_PASSWORDS.EXPIRES_AT.le(now)).execute();
        if (n > 0) db.update(USERS).setNull(USERS.PASSWORD_HASH).where(USERS.ID.eq(userId)).execute();
        return n > 0;
    }

    /** token-cleanup: every run-out one. @return how many went */
    public int expireAll(OffsetDateTime now) {
        var ids = db.select(TEMPORARY_PASSWORDS.USER_ID).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.EXPIRES_AT.le(now))
                .fetch(TEMPORARY_PASSWORDS.USER_ID);
        return (int) ids.stream().filter(id -> expire(id, now)).count();
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordsTest`
Expected: `BUILD SUCCESSFUL`, 1 test passed.

- [ ] **Step 5: 커밋**

```bash
git add backend/src/main/java/com/openpms/auth/TemporaryPasswords.java backend/src/test/java/com/openpms/auth/TemporaryPasswordsTest.java
git commit -F /tmp/openpms-scratch/msg-task2.txt
```

메시지:

```
feat(auth): TemporaryPasswords issues, shows, clears and expires temporary passwords

임시 비밀번호의 생성(16자 이상, 네 종류, 헷갈리는 글자 제외)과 발급·보기·지우기·만료를 한곳에 둡니다.
발급하면 비밀번호를 바꾸고 실패 잠금을 풀고 재설정·변경 토큰을 없애고 모든 세션을 끝냅니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 3: 관리 API (만들기 `method`, 다시 발급, 보기, 목록, 삭제, 토큰·중복 방지 제외)

**Files:**
- Create: `backend/src/main/java/com/openpms/admin/TemporaryPasswordController.java`
- Modify: `backend/src/main/java/com/openpms/admin/AdminController.java` (record `AdminUser` 90–91행, 생성자 127–141행, `toUser` 575–586행, `listUsers` 589–600행, `createUser` 610–626행, `removeUser` 687–712행)
- Modify: `backend/src/main/java/com/openpms/auth/ApiTokenFilter.java:58`
- Modify: `backend/src/main/java/com/openpms/common/IdempotencyFilter.java:52-56`
- Test: `backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java`

**Interfaces:**
- Consumes: Task 2의 `TemporaryPasswords.issue/shown/untilByUser/clear`, `Issued`, `Shown`.
- Produces:
  - `POST /api/v1/admin/users` 본문 `method: "invite" | "temporaryPassword"`; `temporaryPassword`이면 201 `AdminController.CreatedWithPassword(AdminUser user, String temporaryPassword, OffsetDateTime expiresAt)`.
  - `POST /api/v1/admin/users/{id}/temporary-password` → 200 `TemporaryPasswords.Issued`.
  - `GET /api/v1/admin/users/{id}/temporary-password` → 200 `TemporaryPasswords.Shown`, 없으면 404 "유효한 임시 비밀번호가 없습니다."
  - `AdminController.AdminUser`의 마지막 필드 `OffsetDateTime temporaryPasswordUntil`.
  - 테스트 도우미(같은 클래스 안에서 Task 4·5가 씀): `login(email, password)`, `send(session, request, body)`, `signIn(session, email, password)`, `make(admin, email)` → `Made(UUID id, String password)`, `issue(admin, id)` → `String`, `email(prefix)`, `fakeSession(UUID)`, `sessions(UUID)`.

- [ ] **Step 1: 실패하는 통합 테스트 쓰기**

`backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java`:

```java
package com.openpms.auth;

import static com.openpms.db.Tables.IDEMPOTENCY_KEYS;
import static com.openpms.db.Tables.SETTINGS;
import static com.openpms.db.Tables.TEMPORARY_PASSWORDS;
import static com.openpms.db.Tables.USERS;
import static com.openpms.db.Tables.USER_TOKENS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * 임시 비밀번호: made without mail, issued again, looked up until changed, changed at the first sign-in, cleared by every
 * other password path, run out. The test database is shared by every class: each test makes its own accounts.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class TemporaryPasswordIT {
    static final String ADMIN = "seoyeon.kim@openpms.local";
    static final String DEV = "openpms!";
    static final String OWN = "my-own-pass-2026!";

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    JsonMapper json;
    @Autowired
    Tokens tokens;

    MockMvc mvc;

    record Made(UUID id, String password) {}

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    static String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@openpms.local";
    }

    ResultActions send(MockHttpSession s, MockHttpServletRequestBuilder r, String body) throws Exception {
        return mvc.perform(r.session(s).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    ResultActions signIn(MockHttpSession s, String email, String password) throws Exception {
        return send(s, post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    MockHttpSession login(String email, String password) throws Exception {
        var s = new MockHttpSession();
        signIn(s, email, password).andExpect(status().isOk());
        return s;
    }

    Made make(MockHttpSession admin, String email) throws Exception {
        var body = send(admin, post("/api/v1/admin/users"), "{\"name\":\"임시 확인\",\"email\":\"" + email + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        return new Made(UUID.fromString(node.get("user").get("id").asString()), node.get("temporaryPassword").asString());
    }

    String issue(MockHttpSession admin, UUID id) throws Exception {
        var body = send(admin, post("/api/v1/admin/users/" + id + "/temporary-password"), "{}").andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store"))).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("temporaryPassword").asString();
    }

    /** MockMvc does not go through Spring Session: a stored session row stands for the person signed in elsewhere. */
    void fakeSession(UUID userId) {
        db.execute("insert into spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) "
                + "values (?, ?, 0, 0, 1800, ?, ?)", UUID.randomUUID().toString(), UUID.randomUUID().toString(), System.currentTimeMillis() + 1_800_000L, userId.toString());
    }

    int sessions(UUID userId) {
        return db.fetchCount(table("spring_session"), field("principal_name", String.class).eq(userId.toString()));
    }

    @Test
    void aNewAccountGetsATemporaryPasswordWithoutMailAndTheAnswerIsNotKept() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("made");
        var key = UUID.randomUUID().toString();
        var body = mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"임시 확인\",\"email\":\"" + email + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.user.status").value("active")).andExpect(jsonPath("$.user.temporaryPasswordUntil").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        var node = json.readTree(body);
        var id = UUID.fromString(node.get("user").get("id").asString());
        var password = node.get("temporaryPassword").asString();
        assertThat(password).hasSizeGreaterThanOrEqualTo(16);
        // no invitation was issued, so no mail went out
        assertThat(db.fetchCount(USER_TOKENS, USER_TOKENS.USER_ID.eq(id), USER_TOKENS.PURPOSE.eq("invitation"))).isZero();
        // the answer with the password is not kept for Idempotency-Key retries
        assertThat(db.fetchCount(IDEMPOTENCY_KEYS, IDEMPOTENCY_KEYS.KEY.eq(key))).isZero();
        assertThat(db.select(TEMPORARY_PASSWORDS.PASSWORD).from(TEMPORARY_PASSWORDS).where(TEMPORARY_PASSWORDS.USER_ID.eq(id))
                .fetchOne(TEMPORARY_PASSWORDS.PASSWORD)).isEqualTo(password);
        // an unknown method is refused before anything is made
        var other = email("bad");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"x\",\"email\":\"" + other + "\",\"isAdmin\":false,\"method\":\"sms\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("method"));
        assertThat(db.fetchCount(USERS, USERS.EMAIL.eq(other))).isZero();
    }

    @Test
    void issuingAgainActivatesAnInvitationClearsTheLockAndSignsThePersonOut() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("invited");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"초대 대기\",\"email\":\"" + email + "\",\"isAdmin\":false}").andExpect(status().isCreated());
        var id = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(email)).fetchOne(USERS.ID);
        var link = tokens.issue(id, "invitation", Duration.ofHours(1), null);
        var first = issue(admin, id);
        assertThat(db.select(USERS.STATUS).from(USERS).where(USERS.ID.eq(id)).fetchOne(USERS.STATUS)).isEqualTo("active");
        mvc.perform(get("/api/v1/auth/invitations/" + link)).andExpect(status().isGone());

        db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 3).set(USERS.LOCKED_UNTIL, OffsetDateTime.now().plusMinutes(10)).where(USERS.ID.eq(id)).execute();
        var reset = tokens.issue(id, "password_reset", Duration.ofHours(1), null);
        fakeSession(id);
        var second = issue(admin, id);
        assertThat(second).isNotEqualTo(first);
        assertThat(sessions(id)).isZero();
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id)).fetchOne();
        assertThat(u.getLockedUntil()).isNull();
        assertThat(u.getFailedLoginCount()).isZero();
        send(new MockHttpSession(), post("/api/v1/auth/password-reset/confirm"), "{\"token\":\"" + reset + "\",\"newPassword\":\"reset-pass-2026!\"}")
                .andExpect(status().isGone());

        mvc.perform(get("/api/v1/admin/users/" + id + "/temporary-password").session(admin)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.temporaryPassword").value(second)).andExpect(jsonPath("$.issuedBy.name").value("김서연"));
        // the list says until when, never the password
        mvc.perform(get("/api/v1/admin/users").session(admin).param("q", email)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].temporaryPasswordUntil").isNotEmpty()).andExpect(jsonPath("$[0].temporaryPassword").doesNotExist());
    }

    @Test
    void whoMayIssueOrLookAndWhatIsRefused() throws Exception {
        var admin = login(ADMIN, DEV);
        var adminId = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(ADMIN)).fetchOne(USERS.ID);
        var doyoon = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq("doyoon.han@openpms.local")).fetchOne(USERS.ID);
        var role = json.readTree(send(admin, post("/api/v1/admin/roles"), "{\"name\":\"사용자 관리자(임시 비밀번호)\",\"scope\":\"global\",\"permissions\":[\"global.manage_users\"]}")
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asString();
        send(admin, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[\"" + role + "\"]}").andExpect(status().isOk());
        try {
            var manager = login("doyoon.han@openpms.local", DEV);
            var member = make(admin, email("member"));
            // a user manager handles ordinary accounts, not an administrator's
            issue(manager, member.id());
            send(manager, post("/api/v1/admin/users/" + adminId + "/temporary-password"), "{}").andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("관리자 계정은 관리자만 다룰 수 있습니다."));
            mvc.perform(get("/api/v1/admin/users/" + adminId + "/temporary-password").session(manager)).andExpect(status().isForbidden());
            // not myself
            send(admin, post("/api/v1/admin/users/" + adminId + "/temporary-password"), "{}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("내 비밀번호는 계정 설정에서 바꾸세요."));
            // someone without the right
            var plain = login("haneul.lee@openpms.local", DEV);
            send(plain, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/admin/users/" + member.id() + "/temporary-password").session(plain)).andExpect(status().isForbidden());
            // a locked account
            send(admin, patch("/api/v1/admin/users/" + member.id()), "{\"status\":\"locked\"}").andExpect(status().isOk());
            send(admin, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.title").value("잠긴 계정입니다. 먼저 잠금을 푸세요."));
            // a deleted account: gone, and its temporary password with it
            send(admin, delete("/api/v1/admin/users/" + member.id()), "{}").andExpect(status().isNoContent());
            assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(member.id()))).isZero();
            send(admin, post("/api/v1/admin/users/" + member.id() + "/temporary-password"), "{}").andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
        } finally {
            send(admin, put("/api/v1/admin/users/" + doyoon + "/global-roles"), "{\"roleIds\":[]}").andExpect(status().isOk());
        }
    }

    @Test
    void anApiTokenCannotMakeIssueOrLookUpATemporaryPassword() throws Exception {
        db.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq("api.tokens_enabled")).execute(); // tokens on (the default), whatever ApiTokensIT left
        var admin = login(ADMIN, DEV);
        var member = make(admin, email("token"));
        var secret = json.readTree(send(admin, post("/api/v1/me/api-tokens"), "{\"name\":\"임시 비밀번호 시험\",\"scope\":\"write\",\"expiresInDays\":1}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("secret").asString();
        var refused = "API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.";
        mvc.perform(post("/api/v1/admin/users/" + member.id() + "/temporary-password").header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
        mvc.perform(get("/api/v1/admin/users/" + member.id() + "/temporary-password").header("Authorization", "Bearer " + secret))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
        mvc.perform(post("/api/v1/admin/users").header("Authorization", "Bearer " + secret).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"토큰\",\"email\":\"" + email("bytoken") + "\",\"isAdmin\":false,\"method\":\"temporaryPassword\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value(refused));
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordIT`
Expected: 4개 모두 실패(`method`를 모름 → 201 대신 invite 응답, `…/temporary-password` 404 등).

- [ ] **Step 3: `TemporaryPasswordController` 만들기**

`backend/src/main/java/com/openpms/admin/TemporaryPasswordController.java`:

```java
package com.openpms.admin;

import static com.openpms.db.Tables.USERS;

import com.openpms.auth.TemporaryPasswords;
import com.openpms.auth.Tokens;
import com.openpms.common.ApiException;
import com.openpms.core.Current;
import com.openpms.db.tables.records.UsersRecord;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 · 사용자의 임시 비밀번호 발급·보기. Same rights as the rest of 관리 · 사용자; an administrator's account only by administrators. */
@RestController
@RequestMapping("/api/v1/admin/users/{id}/temporary-password")
public class TemporaryPasswordController {
    private final Current current;
    private final DSLContext db;
    private final TemporaryPasswords temporaryPasswords;
    private final Tokens tokens;

    public TemporaryPasswordController(Current current, DSLContext db, TemporaryPasswords temporaryPasswords, Tokens tokens) {
        this.current = current;
        this.db = db;
        this.temporaryPasswords = temporaryPasswords;
        this.tokens = tokens;
    }

    /** The account whose password may be handled here, or why not. */
    private UsersRecord target(UUID id) {
        var ctx = current.ctx();
        if (!ctx.isAdmin() && !ctx.globalPermissions().contains("global.manage_users")) throw ApiException.forbidden("사용자를 관리할 권한이 없습니다.");
        var u = db.selectFrom(USERS).where(USERS.ID.eq(id), USERS.STATUS.ne("deleted")).fetchOne();
        if (u == null) throw ApiException.notFound("사용자을(를) 찾을 수 없습니다.");
        if (u.getIsAdmin() && !ctx.isAdmin()) throw ApiException.forbidden("관리자 계정은 관리자만 다룰 수 있습니다.");
        if (u.getId().equals(ctx.userId())) throw ApiException.unprocessable("내 비밀번호는 계정 설정에서 바꾸세요.", null);
        if ("locked".equals(u.getStatus())) throw ApiException.unprocessable("잠긴 계정입니다. 먼저 잠금을 푸세요.", null);
        return u;
    }

    /** A new temporary password; someone still invited becomes active and their invitation link stops working. */
    @PostMapping
    @Transactional
    public ResponseEntity<TemporaryPasswords.Issued> issue(@PathVariable UUID id) {
        var u = target(id);
        var ctx = current.ctx();
        if ("invited".equals(u.getStatus())) {
            db.update(USERS).set(USERS.STATUS, "active").where(USERS.ID.eq(id)).execute();
            tokens.revokeAll(id, "invitation");
        }
        var issued = temporaryPasswords.issue(id, ctx.userId());
        ctx.refresh();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping
    public ResponseEntity<TemporaryPasswords.Shown> show(@PathVariable UUID id) {
        target(id);
        var shown = temporaryPasswords.shown(id, current.ctx().userId()).orElseThrow(() -> ApiException.notFound("유효한 임시 비밀번호가 없습니다."));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(shown);
    }
}
```

- [ ] **Step 4: `AdminController` 고치기**

4-1. `AdminUser` 레코드(90–91행) 끝에 필드를 더한다:

```java
    /** lockedUntil: a temporary lock after too many failed sign-ins, while it lasts (status stays active).
     *  temporaryPasswordUntil: until when the temporary password runs (past ones too until token-cleanup; never the password). */
    public record AdminUser(UUID id, String name, String email, String status, boolean isAdmin, String locale, String timeZone,
                            OffsetDateTime lastLoginAt, long projectCount, OffsetDateTime createdAt, OffsetDateTime lockedUntil, List<Dto.IdName> globalRoles,
                            OffsetDateTime temporaryPasswordUntil) {}

    /** 임시 비밀번호로 만들기: the password is in this answer (and later in GET …/temporary-password), nowhere else. */
    public record CreatedWithPassword(AdminUser user, String temporaryPassword, OffsetDateTime expiresAt) {}
```

4-2. 생성자에 `com.openpms.auth.TemporaryPasswords temporaryPasswords`를 마지막 매개변수로 더하고 필드 `private final com.openpms.auth.TemporaryPasswords temporaryPasswords;`와 `this.temporaryPasswords = temporaryPasswords;`를 넣는다.

4-3. `toUser`(575–586행)를 바꾼다:

```java
    private AdminUser toUser(Ctx ctx, Snapshot.UserRow u, Map<UUID, OffsetDateTime> lockouts, Map<UUID, OffsetDateTime> temporary) {
        var s = ctx.s();
        long projects = s.projects().values().stream().filter(p -> s.isMember(u.id(), p.id())).count();
        var globalRoles = s.globalRoleIds(u.id()).stream().map(id -> s.roles().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).sorted(Comparator.comparing(Snapshot.RoleRow::name, Text.KOREAN)).map(r -> new Dto.IdName(r.id(), r.name())).toList();
        return new AdminUser(u.id(), u.displayName(), u.email(), u.status(), u.isAdmin(), u.locale(), u.timeZone(), u.lastLoginAt(), projects, u.createdAt(),
                "active".equals(u.status()) ? lockouts.get(u.id()) : null, globalRoles, "deleted".equals(u.status()) ? null : temporary.get(u.id()));
    }

    private AdminUser toUser(Ctx ctx, Snapshot.UserRow u) {
        return toUser(ctx, u, lockouts(), temporaryPasswords.untilByUser());
    }
```

4-4. `listUsers`(589–600행)에서 `var lockouts = lockouts();` 다음 줄에 `var temporary = temporaryPasswords.untilByUser();`를 넣고, 마지막 `.map(u -> toUser(ctx, u, lockouts))`를 `.map(u -> toUser(ctx, u, lockouts, temporary))`로 바꾼다.

4-5. `createUser`(610–626행)를 바꾼다. `jakarta.servlet.http.HttpServletRequest`와 `org.springframework.http.CacheControl` import를 더한다(없으면).

```java
    @PostMapping("/users")
    @Transactional
    public ResponseEntity<?> createUser(@RequestBody Map<String, Object> in, HttpServletRequest req) {
        var ctx = userManager();
        var method = In.str(in, "method") == null ? "invite" : In.str(in, "method");
        if (!"invite".equals(method) && !"temporaryPassword".equals(method)) throw ApiException.field("method", "알 수 없는 등록 방법입니다.");
        boolean temporary = "temporaryPassword".equals(method);
        // an API token must not become a way to get a person's password (ApiTokenFilter closes the other temporary-password routes)
        if (temporary && com.openpms.auth.ApiTokenFilter.isBearer(req))
            throw ApiException.forbidden("API 토큰으로는 로그인·비밀번호·토큰·연동 설정을 다룰 수 없습니다.");
        var n = name(in, true);
        var email = In.str(in, "email") == null ? "" : In.str(in, "email").trim().toLowerCase();
        if (!EMAIL.matcher(email).matches()) throw ApiException.field("email", "이메일 형식이 아닙니다.");
        if (db.fetchExists(USERS, USERS.EMAIL.eq(email))) throw ApiException.field("email", "이미 등록된 이메일입니다.");
        boolean isAdmin = Boolean.TRUE.equals(In.bool(in, "isAdmin"));
        if (isAdmin && !ctx.isAdmin()) throw ApiException.forbidden("관리자만 관리자를 지정할 수 있습니다.");
        var u = db.insertInto(USERS).set(USERS.EMAIL, email).set(USERS.NAME, n).set(USERS.STATUS, temporary ? "active" : "invited").set(USERS.IS_ADMIN, isAdmin)
                .returning(USERS.ID).fetchOne();
        db.insertInto(USER_PREFERENCES).set(USER_PREFERENCES.USER_ID, u.getId()).execute();
        if (!temporary) {
            sendInvitation(ctx, u.getId(), email, n);
            ctx.refresh();
            return ResponseEntity.status(HttpStatus.CREATED).body(toUser(ctx, ctx.s().user(u.getId()).orElseThrow()));
        }
        var issued = temporaryPasswords.issue(u.getId(), ctx.userId());
        ctx.refresh();
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(new CreatedWithPassword(toUser(ctx, ctx.s().user(u.getId()).orElseThrow()), issued.temporaryPassword(), issued.expiresAt()));
    }
```

4-6. `removeUser`에서 `sessions.endAll(id);` 바로 앞에 한 줄을 더한다:

```java
        temporaryPasswords.clear(id); // no plain password stays behind an anonymised account
```

- [ ] **Step 5: `ApiTokenFilter`와 `IdempotencyFilter` 고치기**

`ApiTokenFilter.java:58`의 조건을 바꾼다:

```java
        if ((CLOSED.stream().anyMatch(path::startsWith) || path.endsWith("/temporary-password")) && !path.equals("/api/v1/auth/me")) {
```

`IdempotencyFilter.java`의 `shouldNotFilter`(52–56행)와 그 위에 상수를 둔다(`java.util.regex.Pattern` import):

```java
    /** Answers that carry a temporary password are never kept (they would sit in idempotency_keys for a day). */
    private static final Pattern NOT_KEPT = Pattern.compile("^/api/v1/admin/users(/[^/]+/temporary-password)?$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        var type = req.getContentType();
        return !"POST".equals(req.getMethod()) || !req.getRequestURI().startsWith("/api/") || req.getHeader(HEADER) == null
                || (type != null && type.toLowerCase().startsWith("multipart/")) || NOT_KEPT.matcher(req.getRequestURI()).matches();
    }
```

- [ ] **Step 6: 통과 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordIT --tests com.openpms.common.IdempotencyIT --tests com.openpms.auth.ApiTokensIT --tests com.openpms.admin.GlobalRolesIT`
Expected: `BUILD SUCCESSFUL`. 실패하면 메시지를 읽고 고친 뒤 다시 돌린다.

- [ ] **Step 7: 커밋**

```bash
git add backend/src/main/java/com/openpms/admin/TemporaryPasswordController.java backend/src/main/java/com/openpms/admin/AdminController.java backend/src/main/java/com/openpms/auth/ApiTokenFilter.java backend/src/main/java/com/openpms/common/IdempotencyFilter.java backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java
git commit -F /tmp/openpms-scratch/msg-task3.txt
```

메시지:

```
feat(api): administrators make accounts with a temporary password, issue it again and look it up

POST /admin/users의 method(invite|temporaryPassword), POST·GET /admin/users/{id}/temporary-password,
목록의 temporaryPasswordUntil을 더합니다. 관리자 계정은 관리자만, 나 자신·잠김은 422, 삭제하면 평문 행도 지웁니다.
API 토큰으로는 막고, 응답은 no-store이며 Idempotency-Key 저장에서 뺍니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 4: 로그인 분기, `POST /auth/password-change`, 다른 경로에서 지우기, 정리 작업

**Files:**
- Modify: `backend/src/main/java/com/openpms/auth/AuthController.java` (생성자 61–76행, `login` 93–118행, `confirmReset` 191–201행, `accept` 212–227행)
- Modify: `backend/src/main/java/com/openpms/notification/AccountController.java` (생성자 64–75행, `changePassword` 106–123행)
- Modify: `backend/src/main/java/com/openpms/auth/SecurityConfig.java:59`
- Modify: `backend/src/main/java/com/openpms/jobs/Housekeeping.java` (생성자 22–25행, `tokens` 31–43행)
- Test: `backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java` (테스트 추가)

**Interfaces:**
- Consumes: `TemporaryPasswords.expiresAt/expire/clear/expireAll`, `Tokens.issue/valid/revokeAll`, Task 3의 테스트 도우미.
- Produces:
  - 로그인 403 `{title: "새 비밀번호를 정해야 합니다.", current: {code: "password_change_required", changeToken}}`
  - 로그인 401 `{title: "임시 비밀번호가 만료되었습니다.", detail: "관리자에게 다시 발급을 요청하세요.", current: {code: "temporary_password_expired"}}`
  - `POST /api/v1/auth/password-change` `{token, newPassword}` → 200 `Dto.Me`; 410 `{title: "시간이 지났습니다.", detail: "임시 비밀번호로 다시 로그인하세요."}`; 422 필드 `newPassword`.

- [ ] **Step 1: 실패하는 테스트 더하기**

`TemporaryPasswordIT`에 import `com.openpms.jobs.Housekeeping`, `java.time.Instant`를 더하고, 필드 `@Autowired Housekeeping housekeeping;`와 아래 도우미·테스트를 더한다:

```java
    /** Sign in with the temporary password: no session, a change token in the answer. */
    String changeToken(String email, String password) throws Exception {
        var s = new MockHttpSession();
        var body = signIn(s, email, password).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("새 비밀번호를 정해야 합니다."))
                .andExpect(jsonPath("$.current.code").value("password_change_required")).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isUnauthorized());
        return json.readTree(body).get("current").get("changeToken").asString();
    }

    ResultActions change(MockHttpSession s, String token, String password) throws Exception {
        return send(s, post("/api/v1/auth/password-change"), "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
    }

    @Test
    void theFirstSignInAsksForAPasswordOfOnesOwn() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("first");
        var made = make(admin, email);
        var token = changeToken(email, made.password());
        // the same as the temporary one, or too weak: refused, and the token still works
        change(new MockHttpSession(), token, made.password()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.errors[0].message").value("임시 비밀번호와 다른 비밀번호를 정하세요."));
        change(new MockHttpSession(), token, "short").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field").value("newPassword"));
        fakeSession(made.id());
        var s = new MockHttpSession();
        change(s, token, OWN).andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk());
        assertThat(sessions(made.id())).isZero(); // signed in elsewhere: out
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(made.id()))).isZero();
        change(new MockHttpSession(), token, "another-pass-2026!").andExpect(status().isGone()).andExpect(jsonPath("$.title").value("시간이 지났습니다."));
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized());
        signIn(new MockHttpSession(), email, OWN).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users/" + made.id() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
    }

    @Test
    void aChangeTokenStopsWorkingWhenTheAccountIsLockedOrTheTenMinutesPass() throws Exception {
        var admin = login(ADMIN, DEV);
        var email = email("lockedafter");
        var made = make(admin, email);
        var token = changeToken(email, made.password());
        send(admin, patch("/api/v1/admin/users/" + made.id()), "{\"status\":\"locked\"}").andExpect(status().isOk());
        change(new MockHttpSession(), token, OWN).andExpect(status().isGone());
        send(admin, patch("/api/v1/admin/users/" + made.id()), "{\"status\":\"active\"}").andExpect(status().isOk());
        var late = changeToken(email, made.password());
        db.update(USER_TOKENS).set(USER_TOKENS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(USER_TOKENS.USER_ID.eq(made.id()), USER_TOKENS.PURPOSE.eq("password_change"), USER_TOKENS.USED_AT.isNull()).execute();
        change(new MockHttpSession(), late, OWN).andExpect(status().isGone());
    }

    @Test
    void aTemporaryPasswordRunsOutAtSignInAndInTheNightlyCleanup() throws Exception {
        var admin = login(ADMIN, DEV);
        // at sign-in
        var email = email("runout");
        var made = make(admin, email);
        db.update(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(made.id())).execute();
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.current.code").value("temporary_password_expired"));
        assertThat(db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(made.id())).fetchOne(USERS.PASSWORD_HASH)).isNull();
        signIn(new MockHttpSession(), email, made.password()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.current").doesNotExist());
        mvc.perform(get("/api/v1/admin/users/" + made.id() + "/temporary-password").session(admin)).andExpect(status().isNotFound());
        // issued again, it works again
        var again = issue(admin, made.id());
        changeToken(email, again);

        // in the nightly cleanup (token-cleanup)
        var other = make(admin, email("nightly"));
        db.update(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TEMPORARY_PASSWORDS.USER_ID.eq(other.id())).execute();
        mvc.perform(get("/api/v1/admin/users").session(admin).param("q", "nightly")).andExpect(jsonPath("$[0].temporaryPasswordUntil").isNotEmpty());
        housekeeping.tokens(Instant.now());
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(other.id()))).isZero();
        assertThat(db.select(USERS.PASSWORD_HASH).from(USERS).where(USERS.ID.eq(other.id())).fetchOne(USERS.PASSWORD_HASH)).isNull();
        // a live one stays
        assertThat(db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(made.id()))).isEqualTo(1);
    }

    /** A row as if one were issued meanwhile (the API would also change the password or end an invitation). */
    void putRow(UUID userId) {
        db.insertInto(TEMPORARY_PASSWORDS).set(TEMPORARY_PASSWORDS.USER_ID, userId).set(TEMPORARY_PASSWORDS.PASSWORD, "unused")
                .set(TEMPORARY_PASSWORDS.EXPIRES_AT, OffsetDateTime.now().plusHours(1)).execute();
    }

    int rows(UUID userId) {
        return db.fetchCount(TEMPORARY_PASSWORDS, TEMPORARY_PASSWORDS.USER_ID.eq(userId));
    }

    @Test
    void everyOtherWayOfSettingAPasswordClearsTheTemporaryOne() throws Exception {
        var admin = login(ADMIN, DEV);
        // the reset link (비밀번호를 잊으셨나요?)
        var a = make(admin, email("reset"));
        var reset = tokens.issue(a.id(), "password_reset", Duration.ofHours(1), null);
        send(new MockHttpSession(), post("/api/v1/auth/password-reset/confirm"), "{\"token\":\"" + reset + "\",\"newPassword\":\"" + OWN + "\"}")
                .andExpect(status().isNoContent());
        assertThat(rows(a.id())).isZero();

        // my own change (계정 설정): a session from the change form, then a row put back as if one were issued meanwhile
        var bEmail = email("own");
        var b = make(admin, bEmail);
        var mine = new MockHttpSession();
        change(mine, changeToken(bEmail, b.password()), "first-own-2026!").andExpect(status().isOk());
        putRow(b.id());
        send(mine, put("/api/v1/me/password"), "{\"currentPassword\":\"first-own-2026!\",\"newPassword\":\"second-own-2026!\"}")
                .andExpect(status().isNoContent());
        assertThat(rows(b.id())).isZero();

        // an invitation accepted while a row exists (put in directly: issuing through the API would end the invitation)
        var dEmail = email("accept");
        send(admin, post("/api/v1/admin/users"), "{\"name\":\"초대\",\"email\":\"" + dEmail + "\",\"isAdmin\":false}").andExpect(status().isCreated());
        var d = db.select(USERS.ID).from(USERS).where(USERS.EMAIL.eq(dEmail)).fetchOne(USERS.ID);
        putRow(d);
        var link = tokens.issue(d, "invitation", Duration.ofHours(1), null);
        send(new MockHttpSession(), post("/api/v1/auth/invitations/" + link + "/accept"), "{\"name\":\"초대\",\"password\":\"" + OWN + "\"}")
                .andExpect(status().isOk());
        assertThat(rows(d)).isZero();
    }
```

- [ ] **Step 2: 실패 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordIT`
Expected: 새 테스트 4개 실패(로그인이 200으로 세션을 만들어 `isForbidden` 기대가 깨짐, `/auth/password-change` 401 등).

- [ ] **Step 3: `AuthController` 고치기**

3-1. 생성자에 `TemporaryPasswords temporaryPasswords`를 마지막에서 두 번째(`@Value` 앞) 매개변수로 더하고 필드·대입을 넣는다. import `com.openpms.db.tables.records.UserTokensRecord`, `java.util.Map`을 더한다.

3-2. 레코드를 더한다(다른 레코드 옆):

```java
    public record PasswordChange(String token, String newPassword) {}
```

3-3. `login`에서 `db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0)…LAST_LOGIN_AT…` 줄 **앞에** 넣는다:

```java
        // 임시 비밀번호: right, but only good for choosing one's own (no session; a ten-minute change token instead)
        var temporaryUntil = temporaryPasswords.expiresAt(u.getId());
        if (temporaryUntil != null) {
            db.update(USERS).set(USERS.FAILED_LOGIN_COUNT, 0).setNull(USERS.LOCKED_UNTIL).where(USERS.ID.eq(u.getId())).execute();
            if (!temporaryUntil.isAfter(now)) {
                temporaryPasswords.expire(u.getId(), now);
                throw new ApiException(401, "임시 비밀번호가 만료되었습니다.", "관리자에게 다시 발급을 요청하세요.", null, Map.of("code", "temporary_password_expired"));
            }
            var changeToken = tokens.issue(u.getId(), "password_change", Duration.ofMinutes(10), null);
            throw new ApiException(403, "새 비밀번호를 정해야 합니다.", "임시 비밀번호로는 바로 쓸 수 없습니다. 새 비밀번호를 정하세요.", null,
                    Map.of("code", "password_change_required", "changeToken", changeToken));
        }
```

3-4. 새 엔드포인트(`confirmReset` 뒤):

```java
    /** 임시 비밀번호의 첫 로그인: the change token from the sign-in answer and a password of one's own, then signed in. */
    @PostMapping("/password-change")
    @Transactional
    public Dto.Me changeTemporaryPassword(@RequestBody PasswordChange in, HttpServletRequest req, HttpServletResponse res) {
        limiter.check("login:" + req.getRemoteAddr(), loginsPerMinute);
        UserTokensRecord t;
        try {
            t = tokens.valid(in.token(), "password_change");
        } catch (ApiException e) {
            throw new ApiException(410, "시간이 지났습니다.", "임시 비밀번호로 다시 로그인하세요.");
        }
        var u = db.selectFrom(USERS).where(USERS.ID.eq(t.getUserId())).fetchOne();
        if (u == null || !"active".equals(u.getStatus())) throw new ApiException(410, "시간이 지났습니다.", "임시 비밀번호로 다시 로그인하세요.");
        policy.check("newPassword", in.newPassword());
        if (u.getPasswordHash() != null && encoder.matches(in.newPassword(), u.getPasswordHash()))
            throw ApiException.field("newPassword", "임시 비밀번호와 다른 비밀번호를 정하세요.");
        db.update(USERS).set(USERS.PASSWORD_HASH, encoder.encode(in.newPassword())).set(USERS.LAST_LOGIN_AT, OffsetDateTime.now())
                .where(USERS.ID.eq(u.getId())).execute();
        temporaryPasswords.clear(u.getId());
        tokens.revokeAll(u.getId(), "password_change");
        sessions.endAll(u.getId());
        signIn(u.getId(), req, res);
        return me();
    }
```

3-5. `confirmReset`의 `tokens.revokeAll(t.getUserId(), "password_reset");` 다음 줄에 `temporaryPasswords.clear(t.getUserId());`를 넣는다.

3-6. `accept`의 `tokens.revokeAll(t.getUserId(), "invitation");` 다음 줄에 `temporaryPasswords.clear(t.getUserId());`를 넣는다.

- [ ] **Step 4: `AccountController`, `SecurityConfig`, `Housekeeping` 고치기**

`AccountController`: 생성자에 `com.openpms.auth.TemporaryPasswords temporaryPasswords`를 마지막 매개변수로 더하고 필드·대입을 넣는다. `changePassword`에서 `u.store();` 다음 줄에:

```java
        temporaryPasswords.clear(ctx.userId());
```

`SecurityConfig.java:59`의 `permitAll()` 목록에 `"/api/v1/auth/password-change"`를 `"/api/v1/auth/password-reset/confirm"` 다음에 더한다.

`Housekeeping`: 생성자를 `Housekeeping(DSLContext db, FileStorage storage, com.openpms.auth.TemporaryPasswords temporaryPasswords)`로 바꾸고 필드를 더한다. `tokens(Instant now)`의 `return n;` 앞에:

```java
        // 임시 비밀번호 that ran out: the row and the password itself (TemporaryPasswords.expire)
        n += temporaryPasswords.expireAll(OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
```

Javadoc 첫 문장 끝에 "; run-out temporary passwords" 를 더한다.

- [ ] **Step 5: 통과 확인**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.TemporaryPasswordIT --tests com.openpms.auth.AuthLinksIT --tests com.openpms.auth.LockoutIT --tests com.openpms.webhook.WebhooksIT`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 커밋**

```bash
git add backend/src/main/java/com/openpms/auth/AuthController.java backend/src/main/java/com/openpms/notification/AccountController.java backend/src/main/java/com/openpms/auth/SecurityConfig.java backend/src/main/java/com/openpms/jobs/Housekeeping.java backend/src/test/java/com/openpms/auth/TemporaryPasswordIT.java
git commit -F /tmp/openpms-scratch/msg-task4.txt
```

메시지:

```
feat(auth): a temporary password only earns a change token; POST /auth/password-change signs in

임시 비밀번호로 로그인하면 세션 대신 10분짜리 변경 토큰(403 current.changeToken)을 주고, 기한이 지났으면
401 temporary_password_expired와 함께 비밀번호를 비웁니다. 재설정 링크·초대 수락·본인 변경도 임시 비밀번호를
지우고, token-cleanup이 만료된 것을 정리합니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 5: SSO는 임시 비밀번호가 있어도 된다 (`OidcIT`)

**Files:**
- Modify: `backend/src/test/java/com/openpms/auth/OidcIT.java`

**Interfaces:**
- Consumes: `TemporaryPasswords.issue(UUID, UUID)`, `OidcIT.provider(boolean)`, `OidcIT.signIn(MockHttpSession, Map)`.

- [ ] **Step 1: 테스트 더하기**

필드 `@Autowired TemporaryPasswords temporaryPasswords;`를 더하고, 테스트를 더한다(이메일은 `@sso.example`이라 `cleanUp`이 지운다):

```java
    @Test
    void aTemporaryPasswordDoesNotStandInTheWayOfCompanySso() throws Exception {
        provider(false);
        var id = db.insertInto(USERS).set(USERS.EMAIL, "temporary@sso.example").set(USERS.NAME, "임시 SSO").set(USERS.STATUS, "active")
                .returning(USERS.ID).fetchOne().getId();
        temporaryPasswords.issue(id, null);
        var s = new MockHttpSession();
        assertThat(signIn(s, Map.of("sub", "u-temp", "email", "temporary@sso.example", "name", "임시 SSO"))).isEqualTo("/projects");
        mvc.perform(get("/api/v1/auth/me").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.email").value("temporary@sso.example"));
    }
```

- [ ] **Step 2: 실행**

Run: `bash backend/scripts/dev.sh test --tests com.openpms.auth.OidcIT`
Expected: `BUILD SUCCESSFUL`(SSO 경로는 바꾸지 않았으므로 처음부터 통과한다. 이 테스트는 그 동작을 고정한다).

- [ ] **Step 3: 커밋**

```bash
git add backend/src/test/java/com/openpms/auth/OidcIT.java
git commit -F /tmp/openpms-scratch/msg-task5.txt
```

메시지:

```
test(auth): company SSO still signs in someone who holds a temporary password

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 6: 화면 API 계층과 가짜 데이터 계층

**Files:**
- Modify: `frontend/src/api/types.ts` (`AdminUser` 1194–1202행 근처)
- Modify: `frontend/src/api/api.ts` (`auth` 91–110행, `admin.users` 478–491행, 상단 type import)
- Modify: `frontend/src/api/http.ts` (`auth` 92–106행, `admin.users` 307–314행)
- Modify: `frontend/src/api/admin-hooks.ts` (`useUserMutations` 140–148행)
- Modify: `frontend/src/api/fake/db.ts` (`DbToken` 125행, `FakeDb`의 `apiTokens?` 157행 근처)
- Create: `frontend/src/api/fake/temporary-passwords.ts`
- Modify: `frontend/src/api/fake/index.ts` (`changePassword` 857행, `auth.login` 894행, `confirmPasswordReset` 926행, `acceptInvitation` 942행)
- Modify: `frontend/src/api/fake/admin.ts` (`toUser` 65–71행, `users.create` 844–857행, `remove`, 새 메서드)

**Interfaces:**
- Produces (TypeScript):
  - `AdminUser.temporaryPasswordUntil: IsoDateTime | null`
  - `interface TemporaryPassword { temporaryPassword: string; expiresAt: IsoDateTime; issuedBy?: { id: Id; name: string } | null; issuedAt?: IsoDateTime }`
  - `api.auth.changeTemporaryPassword(token: string, newPassword: string): Promise<Me>`
  - `api.admin.users.createWithTemporaryPassword(input: UserInput): Promise<{ user: AdminUser } & TemporaryPassword>`
  - `api.admin.users.issueTemporaryPassword(id: Id): Promise<TemporaryPassword>`
  - `api.admin.users.temporaryPassword(id: Id): Promise<TemporaryPassword>`
  - `useUserMutations().createWithPassword`, `.issueTemporary`

- [ ] **Step 1: 타입**

`types.ts`의 `AdminUser`에 필드를 더하고, 바로 아래에 새 타입을 둔다:

```ts
  /** 임시 비밀번호: until when it runs (past = ran out, shown until the nightly cleanup); the password only via temporaryPassword(id) */
  temporaryPasswordUntil: IsoDateTime | null;
}
/** 임시 비밀번호 as an administrator sees it: made, issued again, or looked up (issuedBy/issuedAt only when looked up). */
export interface TemporaryPassword {
  temporaryPassword: string;
  expiresAt: IsoDateTime;
  issuedBy?: { id: Id; name: string } | null;
  issuedAt?: IsoDateTime;
}
```

- [ ] **Step 2: `Api` 인터페이스**

`api.ts` 상단 type import 목록에 `TemporaryPassword`를 더한다. `auth`의 `acceptInvitation` 아래에:

```ts
    /** POST /auth/password-change — after a sign-in with a temporary password (403, current: { code: "password_change_required", changeToken }):
     *  a password of one's own, then signed in; 410 when the ten minutes passed or the account was locked meanwhile. */
    changeTemporaryPassword(token: string, newPassword: string): Promise<Me>;
```

`admin.users`의 `create` 아래에:

```ts
      /** POST /admin/users { method: "temporaryPassword" } — an active account with a temporary password, no mail */
      createWithTemporaryPassword(input: UserInput): Promise<{ user: AdminUser } & TemporaryPassword>;
      /** POST /admin/users/{id}/temporary-password — a new one replaces the password; the person is signed out everywhere */
      issueTemporaryPassword(id: Id): Promise<TemporaryPassword>;
      /** GET /admin/users/{id}/temporary-password — the one in force (404 when none or it ran out) */
      temporaryPassword(id: Id): Promise<TemporaryPassword>;
```

- [ ] **Step 3: 실제 클라이언트**

`http.ts`의 `auth`에 `acceptInvitation` 다음 줄:

```ts
    changeTemporaryPassword: (token, newPassword) => post("/auth/password-change", { token, newPassword }),
```

`admin.users`의 `create` 다음 줄:

```ts
      createWithTemporaryPassword: (input) => post("/admin/users", { ...input, method: "temporaryPassword" }),
      issueTemporaryPassword: (id) => post(`/admin/users/${id}/temporary-password`),
      temporaryPassword: (id) => get(`/admin/users/${id}/temporary-password`),
```

`admin-hooks.ts`의 `useUserMutations`에 두 줄:

```ts
    createWithPassword: useMut((input: UserInput) => api.admin.users.createWithTemporaryPassword(input)),
    issueTemporary: useMut((id: Id) => api.admin.users.issueTemporaryPassword(id)),
```

- [ ] **Step 4: 가짜 DB 모양**

`fake/db.ts`:

```ts
export interface DbToken { token: string; userId: Id; purpose: "invitation" | "password_reset" | "password_change"; expiresAt: IsoDateTime; usedAt: IsoDateTime | null; createdBy: Id | null }
/** 임시 비밀번호 (backend temporary_passwords): kept until changed or run out, like the server */
export interface DbTemporaryPassword { userId: Id; password: string; expiresAt: IsoDateTime; issuedBy: Id | null; issuedAt: IsoDateTime }
```

`FakeDb`의 `apiTokens?: DbApiToken[];` 다음 줄에 `temporaryPasswords?: DbTemporaryPassword[];`. 시드는 바꾸지 않으므로(선택 필드) `SCHEMA`는 그대로 둔다.

- [ ] **Step 5: 가짜 임시 비밀번호 모듈**

`frontend/src/api/fake/temporary-passwords.ts`:

```ts
// 임시 비밀번호 in the fake data layer; backend: auth/TemporaryPasswords.java (same rules, same messages).
import { nowIso } from "@/lib/dates";
import { DEFAULT_AUTH_SETTINGS } from "../password-policy";
import { db } from "./db";
import { revokeAll } from "./tokens";

const LOWER = "abcdefghijkmnopqrstuvwxyz";
const UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
const DIGITS = "23456789";
const SPECIAL = "!@#$%*-_+=?";

const random = (n: number) => crypto.getRandomValues(new Uint32Array(1))[0] % n;
const pick = (set: string) => set[random(set.length)];

/** At least 16 characters, one of each kind, shuffled (TemporaryPasswords.generate). */
export function generateTemporaryPassword(minLength: number) {
  const chars = [LOWER, UPPER, DIGITS, SPECIAL].map(pick);
  const all = LOWER + UPPER + DIGITS + SPECIAL;
  while (chars.length < Math.max(16, minLength)) chars.push(pick(all));
  for (let i = chars.length - 1; i > 0; i--) {
    const j = random(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join("");
}

/** A new one replaces the password. The demo has one session per browser: if it is theirs, it ends. */
export function newTemporaryPassword(userId: string, issuedBy: string) {
  const settings = db.settings.auth ?? DEFAULT_AUTH_SETTINGS;
  const password = generateTemporaryPassword(settings.minLength);
  const issuedAt = nowIso();
  const expiresAt = new Date(Date.now() + settings.invitationTtlHours * 3_600_000).toISOString();
  const u = db.users.find((x) => x.id === userId)!;
  u.password = password;
  db.temporaryPasswords = [...(db.temporaryPasswords ?? []).filter((x) => x.userId !== userId), { userId, password, expiresAt, issuedBy, issuedAt }];
  revokeAll(userId, "password_reset");
  revokeAll(userId, "password_change");
  if (db.sessionUserId === userId) db.sessionUserId = null;
  return { temporaryPassword: password, expiresAt };
}

export const temporaryPasswordOf = (userId: string) => (db.temporaryPasswords ?? []).find((x) => x.userId === userId);

/** Any path that sets a password, or the account went. */
export function clearTemporaryPassword(userId: string) {
  db.temporaryPasswords = (db.temporaryPasswords ?? []).filter((x) => x.userId !== userId);
}

/** Ran out: the row and the password itself go (TemporaryPasswords.expire). */
export function expireTemporaryPassword(userId: string) {
  clearTemporaryPassword(userId);
  const u = db.users.find((x) => x.id === userId);
  if (u) u.password = "";
}
```

`AuthSettings`에 `minLength`와 `invitationTtlHours`가 있다(`types.ts:1081`, `password-policy.ts:16`).

- [ ] **Step 6: 가짜 인증**

`fake/index.ts`: import `{ clearTemporaryPassword, expireTemporaryPassword, temporaryPasswordOf } from "./temporary-passwords"`와, 아직 없으면 `issueToken`·`validToken`·`revokeAll`을 `./tokens`에서 가져온다(이미 쓰고 있음).

`auth.login`의 `if (!u || u.password !== password || u.status !== "active") throw …` 다음에:

```ts
        const temporary = temporaryPasswordOf(u.id);
        if (temporary) {
          if (temporary.expiresAt <= nowIso()) {
            expireTemporaryPassword(u.id);
            throw new ApiError(401, "임시 비밀번호가 만료되었습니다.", "관리자에게 다시 발급을 요청하세요.", { current: { code: "temporary_password_expired" } });
          }
          revokeAll(u.id, "password_change");
          const changeToken = issueToken(u.id, "password_change", 10, null);
          throw new ApiError(403, "새 비밀번호를 정해야 합니다.", "임시 비밀번호로는 바로 쓸 수 없습니다. 새 비밀번호를 정하세요.", { current: { code: "password_change_required", changeToken } });
        }
```

`auth`에 `acceptInvitation` 다음 메서드:

```ts
    changeTemporaryPassword: (token, newPassword) =>
      run(() => {
        const late = () => new ApiError(410, "시간이 지났습니다.", "임시 비밀번호로 다시 로그인하세요.");
        let t: ReturnType<typeof validToken>;
        try {
          t = validToken(token, "password_change");
        } catch {
          throw late();
        }
        const u = db.users.find((x) => x.id === t.userId);
        if (u?.status !== "active") throw late();
        checkNewPassword(newPassword, "newPassword");
        if (newPassword === u.password) throw fieldError("newPassword", "임시 비밀번호와 다른 비밀번호를 정하세요.");
        u.password = newPassword;
        u.lastLoginAt = nowIso();
        clearTemporaryPassword(u.id);
        revokeAll(u.id, "password_change");
        db.sessionUserId = u.id;
        return toMe(u);
      }),
```

`account.changePassword`의 `user.password = newPassword;` 다음, `auth.confirmPasswordReset`의 `u.password = newPassword;` 다음, `auth.acceptInvitation`의 `Object.assign(u, …)` 다음에 각각 `clearTemporaryPassword(u.id);`(`changePassword`에서는 `clearTemporaryPassword(user.id);`)를 넣는다.

- [ ] **Step 7: 가짜 관리**

`fake/admin.ts`: import `{ clearTemporaryPassword, newTemporaryPassword, temporaryPasswordOf } from "./temporary-passwords"`, 타입 import에 `TemporaryPassword`, `UserInput`.

`toUser`의 객체에 `temporaryPasswordUntil: u.status === "deleted" ? null : (temporaryPasswordOf(u.id)?.expiresAt ?? null),`를 더한다.

`requireUserManager` 아래에 둔다:

```ts
/** Whose temporary password may be handled (backend admin/TemporaryPasswordController.target). */
function temporaryTarget(id: string) {
  const me = sessionUser();
  requireUserManager(me);
  const u = db.users.find((x) => x.id === id && x.status !== "deleted");
  if (!u) throw notFound("사용자");
  if (u.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자 계정은 관리자만 다룰 수 있습니다.");
  if (u.id === me.id) throw new ApiError(422, "내 비밀번호는 계정 설정에서 바꾸세요.");
  if (u.status === "locked") throw new ApiError(422, "잠긴 계정입니다. 먼저 잠금을 푸세요.");
  return u;
}

/** A new account (users.create and createWithTemporaryPassword share the checks). */
function addUser(input: UserInput, status: DbUser["status"]) {
  const me = sessionUser();
  requireUserManager(me);
  checkName(input.name);
  const email = input.email.trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw fieldError("email", "이메일 형식이 아닙니다.");
  if (db.users.some((u) => u.email.toLowerCase() === email)) throw fieldError("email", "이미 등록된 이메일입니다.");
  if (input.isAdmin && !me.isAdmin) throw new ApiError(403, "관리자만 관리자를 지정할 수 있습니다.");
  const u: DbUser = {
    id: newId("u"), email, name: input.name.trim(), password: "", status, isAdmin: input.isAdmin, locale: "ko", timeZone: "Asia/Seoul",
    prefs: { commentsNewestFirst: false, warnOnUnsaved: true, keyboardShortcuts: true, immediateOnMention: true, digestEnabled: true, digestTimes: ["08:00"], remindersPausedUntil: null, myPageWidgets: ["assigned", "created"] }, createdAt: nowIso(), lastLoginAt: null,
  };
  db.users.push(u);
  return u;
}
```

`users.create`를 바꾸고, 그 다음에 세 메서드를 더한다:

```ts
    create: (input) =>
      run(() => {
        const u = addUser(input, "invited");
        invite(u, sessionUser().id);
        return toUser(u);
      }),
    createWithTemporaryPassword: (input) =>
      run(() => {
        const u = addUser(input, "active");
        const issued = newTemporaryPassword(u.id, sessionUser().id);
        return { user: toUser(u), ...issued };
      }),
    issueTemporaryPassword: (id) =>
      run(() => {
        const u = temporaryTarget(id);
        if (u.status === "invited") {
          u.status = "active";
          revokeAll(u.id, "invitation");
        }
        return newTemporaryPassword(u.id, sessionUser().id);
      }),
    temporaryPassword: (id) =>
      run((): TemporaryPassword => {
        temporaryTarget(id);
        const t = temporaryPasswordOf(id);
        if (!t || t.expiresAt <= nowIso()) throw new ApiError(404, "유효한 임시 비밀번호가 없습니다.");
        const by = t.issuedBy ? db.users.find((x) => x.id === t.issuedBy) : undefined;
        return { temporaryPassword: t.password, expiresAt: t.expiresAt, issuedBy: by ? { id: by.id, name: by.name } : null, issuedAt: t.issuedAt };
      }),
```

`users.remove`의 `Object.assign(u, { status: "deleted", … })` 다음 줄에 `clearTemporaryPassword(u.id);`.

- [ ] **Step 8: 타입 검사**

Run: `cd frontend && npx tsc -b --noEmit`
Expected: 오류 없음.

- [ ] **Step 9: 커밋**

```bash
git add frontend/src/api/types.ts frontend/src/api/api.ts frontend/src/api/http.ts frontend/src/api/admin-hooks.ts frontend/src/api/fake/db.ts frontend/src/api/fake/temporary-passwords.ts frontend/src/api/fake/index.ts frontend/src/api/fake/admin.ts
git commit -F /tmp/openpms-scratch/msg-task6.txt
```

메시지:

```
feat(ui-api): temporary password calls in the API layer and the fake data layer

실제 클라이언트와 가짜 계층에 만들기·다시 발급·보기와 첫 로그인 변경을 더합니다. 가짜 계층도 서버와
같은 권한·거절·만료·문구를 따릅니다(선택 필드라 SCHEMA는 그대로).

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 7: 로그인 안의 '새 비밀번호 정하기'

**Files:**
- Create: `frontend/src/pages/new-password.tsx`
- Modify: `frontend/src/pages/auth-links.tsx` (16–85행의 `Errors`, `fieldErrorsOf`, `NewPasswordFields`, `usePasswordCheck`, `focusFirstError`를 옮김)
- Modify: `frontend/src/pages/login.tsx`
- Modify: `frontend/src/i18n/ko.ts` (`auth` 블록, 76행~)

**Interfaces:**
- Consumes: Task 6의 `api.auth.changeTemporaryPassword`, `ApiError.current`.
- Produces: `export function PasswordChangeForm({ token, onSignedIn, onExpired }: { token: string; onSignedIn: (me: Me) => void; onExpired: () => void })`; `new-password.tsx`가 `Errors`, `fieldErrorsOf`, `NewPasswordFields`, `usePasswordCheck`, `focusFirstError`를 export.

- [ ] **Step 1: 공용 모듈 만들기**

`frontend/src/pages/new-password.tsx`: `auth-links.tsx`의 16–85행(`type Errors`부터 `focusFirstError`까지)을 **그대로 옮기고** 각각 `export`를 붙인다. 파일 머리와 끝은 이렇게 쓴다:

```tsx
// New password fields shared by the reset page, the invitation page and the first sign-in with a temporary password (S01).
import { useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { KeyRound } from "lucide-react";
import { api, isApiError, type Me } from "@/api";
import { usePasswordRules } from "@/api/hooks";
import { DEFAULT_AUTH_SETTINGS, describeRules, passwordProblem } from "@/api/password-policy";
import { Button } from "@/components/ui/button";
import { Field, Input } from "@/components/ui/field";
import { Banner } from "@/components/ui/feedback";

/* … auth-links.tsx 16–85행: export type Errors, export function fieldErrorsOf, export function NewPasswordFields,
   export function usePasswordCheck, export function focusFirstError … */

/** 임시 비밀번호로 로그인한 뒤: a password of one's own before anything else (the token stays in memory, never in the address). */
export function PasswordChangeForm({ token, onSignedIn, onExpired }: { token: string; onSignedIn: (me: Me) => void; onExpired: () => void }) {
  const { t } = useTranslation();
  const checkPasswords = usePasswordCheck();
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [errors, setErrors] = useState<Errors>({});
  const [failure, setFailure] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const form = e.currentTarget;
    setFailure(null);
    const local = checkPasswords(password, confirm);
    setErrors(local);
    if (Object.keys(local).length) return focusFirstError(form);
    setBusy(true);
    try {
      onSignedIn(await api.auth.changeTemporaryPassword(token, password));
    } catch (err) {
      if (isApiError(err) && err.status === 410) onExpired();
      else if (isApiError(err) && err.fieldErrors.length) (setErrors(fieldErrorsOf(err)), focusFirstError(form));
      else setFailure(t("app.errorBody"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <h1 className="text-xl text-fg">{t("authLinks.newPasswordTitle")}</h1>
      <p className="mt-1 text-sm text-fg-muted">{t("auth.temporary.subtitle")}</p>
      {failure && (
        <div className="mt-4">
          <Banner tone="danger">{failure}</Banner>
        </div>
      )}
      <form className="mt-5 flex flex-col gap-4" onSubmit={submit} noValidate>
        <NewPasswordFields password={password} confirm={confirm} onPassword={setPassword} onConfirm={setConfirm} errors={errors} />
        <Button type="submit" variant="primary" size="lg" loading={busy} icon={<KeyRound />}>
          {t("authLinks.savePassword")}
        </Button>
      </form>
    </>
  );
}
```

`Me`는 `@/api`에서 가져온다(`frontend/src/api/index.ts`가 `export * from "./types"`로 다시 내보낸다).

`auth-links.tsx`에서는 옮긴 블록을 지우고, import를 `import { focusFirstError, fieldErrorsOf, NewPasswordFields, usePasswordCheck, type Errors } from "./new-password";`로 바꾼다. 쓰지 않게 된 import(`describeRules`, `passwordProblem`, `DEFAULT_AUTH_SETTINGS`, `usePasswordRules` 등)는 `tsc`가 알려 주는 대로 지운다.

- [ ] **Step 2: 문구**

`ko.ts`의 `auth` 블록, `required` 다음에:

```ts
    temporary: {
      subtitle: "임시 비밀번호로 로그인했습니다. 계속하려면 나만 아는 새 비밀번호를 정하세요.",
      tokenExpired: "시간이 지났습니다. 임시 비밀번호로 다시 로그인하세요.",
      expired: "임시 비밀번호가 만료되었습니다. 관리자에게 다시 발급을 요청하세요.",
    },
```

- [ ] **Step 3: `login.tsx`**

import에 `import { PasswordChangeForm } from "./new-password";`. 상태에 `const [changeToken, setChangeToken] = useState<string | null>(null);`(다른 `useState` 옆, 이른 return보다 앞). `submit` 안의 성공·실패 처리를 바꾼다:

```tsx
    try {
      const user = await api.auth.login(email, password);
      signedIn(user);
    } catch (err) {
      const reason = isApiError(err) ? (err.current as { code?: string; changeToken?: string } | undefined) : undefined;
      if (reason?.code === "password_change_required" && reason.changeToken) {
        setPassword("");
        setChangeToken(reason.changeToken);
      } else if (reason?.code === "temporary_password_expired") setError(t("auth.temporary.expired"));
      else setError(isApiError(err) && err.status === 423 ? t("auth.locked") : t("auth.failed"));
    } finally {
      setBusy(false);
    }
```

`submit` 위에 둔다:

```tsx
  const signedIn = (user: Me) => {
    qc.setQueryData(qk.me, user);
    // only same-origin paths are accepted as a return target
    navigate(next && next.startsWith("/") && !next.startsWith("//") ? next : "/", { replace: true });
  };
```

(`Me` 타입은 `import type { Me } from "@/api";`.) 최종 `return (` 바로 앞에:

```tsx
  if (changeToken)
    return (
      <AuthShell>
        <PasswordChangeForm token={changeToken} onSignedIn={signedIn} onExpired={() => (setChangeToken(null), setError(t("auth.temporary.tokenExpired")))} />
      </AuthShell>
    );
```

- [ ] **Step 4: 타입 검사와 손 확인(가짜 계층)**

Run: `cd frontend && npx tsc -b --noEmit`
Expected: 오류 없음.

손 확인(5173, 가짜 계층): `cd frontend && npm run dev` → http://localhost:5173 → 김서연 예시 계정으로 로그인 → 개발자 도구 콘솔에서 `const { api } = await import("/src/api/index.ts"); const r = await api.admin.users.createWithTemporaryPassword({ name: "임시", email: "tmp@openpms.local", isAdmin: false }); r.temporaryPassword` → 로그아웃 → `tmp@openpms.local`과 그 비밀번호로 로그인 → "새 비밀번호 정하기" 폼이 보이고, 같은 비밀번호를 넣으면 "임시 비밀번호와 다른 비밀번호를 정하세요.", 새 비밀번호로 저장하면 홈으로 간다.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/new-password.tsx frontend/src/pages/auth-links.tsx frontend/src/pages/login.tsx frontend/src/i18n/ko.ts
git commit -F /tmp/openpms-scratch/msg-task7.txt
```

메시지:

```
feat(ui): the first sign-in with a temporary password asks for a password of one's own

로그인 응답이 password_change_required이면 같은 화면이 새 비밀번호 정하기 폼으로 바뀝니다(토큰은 메모리에만).
새 비밀번호 칸과 검사는 new-password.tsx로 옮겨 재설정·초대 수락과 함께 씁니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 8: 관리 · 사용자 화면

**Files:**
- Modify: `frontend/src/pages/admin/users.tsx`
- Modify: `frontend/src/i18n/ko.ts` (`admin.users` 블록, 841행~)

**Interfaces:**
- Consumes: `useUserMutations().create/createWithPassword/issueTemporary`, `api.admin.users.temporaryPassword`, `AdminUser.temporaryPasswordUntil`, `TemporaryPassword`.

- [ ] **Step 1: 문구**

`ko.ts`의 `admin.users`에서 `invite: "사용자 초대",`는 남겨 두고(다른 곳에서 쓸 수 있음) 아래를 더한다:

```ts
      add: "사용자 추가",
      method: "등록 방법",
      methodInvite: "초대 메일 보내기",
      methodTemporary: "임시 비밀번호 만들기",
      temporaryHint: "메일 없이 바로 계정을 만듭니다. 임시 비밀번호를 전달하면 사용자가 처음 로그인할 때 새 비밀번호를 정합니다.",
      createUser: "만들기",
      temporaryTitle: "{{name}}님의 임시 비밀번호",
      temporaryNote: "사용자가 처음 로그인할 때 새 비밀번호를 정합니다. 새 비밀번호를 정하면 이 비밀번호는 더 볼 수 없습니다.",
      temporaryPassword: "임시 비밀번호",
      temporaryUntil: "{{time}}까지 유효",
      temporaryIssued: "{{name}}님이 {{time}}에 발급",
      copyPassword: "비밀번호 복사",
      copyMessage: "전달용 문구 복사",
      copiedPassword: "비밀번호를 복사했습니다",
      copiedMessage: "전달용 문구를 복사했습니다",
      copyFailed: "복사하지 못했습니다. 직접 선택해 복사하세요.",
      temporaryMessage: "[openPMS] 계정이 준비되었습니다.\n접속 주소: {{url}}\n이메일: {{email}}\n임시 비밀번호: {{password}}\n유효 기한: {{until}}까지\n처음 로그인하면 새 비밀번호를 정합니다.",
      done: "확인",
      issueTemporary: "임시 비밀번호 발급",
      showTemporary: "임시 비밀번호 보기",
      issueTitle: "임시 비밀번호를 발급할까요?",
      issueBody: "{{name}}님의 비밀번호를 임시 비밀번호로 바꿉니다. 로그인된 곳에서 모두 로그아웃됩니다.",
      issueConfirm: "발급",
      temporaryBadge: "임시 비밀번호 · {{time}}까지",
      temporaryExpired: "임시 비밀번호 만료",
```

(`i18n/index.ts`는 `escapeValue: false`라 주소의 `/`가 바뀌지 않는다.)

- [ ] **Step 2: 대화상자 두 개**

`users.tsx`의 import를 고친다: lucide에서 `ClipboardCopy, Copy, Eye, KeyRound`를 더하고, `import { api, type AdminGroup, type AdminUser, type TemporaryPassword } from "@/api";`, `import { RadioCards } from "@/components/ui/choice";`(기존 `Checkbox` import에 합침), `import { EmptyState, useToast } from "@/components/ui/feedback";`.

`InviteDialog`를 아래 `AddUserDialog`로 바꾸고, 그 뒤에 `TemporaryPasswordDialog`를 둔다:

```tsx
type Shown = { user: AdminUser; password: TemporaryPassword };

/** 사용자 추가: an invitation by mail (as before) or, without mail, a temporary password to pass on. */
function AddUserDialog({ onClose, onMade }: { onClose: () => void; onMade: (shown: Shown) => void }) {
  const { t } = useTranslation();
  const me = useMe().data;
  const { create, createWithPassword } = useUserMutations();
  const runToast = useRunWithToast();
  const [v, setV] = useState({ name: "", email: "", isAdmin: false });
  const [method, setMethod] = useState<"invite" | "temporaryPassword">("invite");
  const error = method === "invite" ? create.error : createWithPassword.error;
  const submit = async () => {
    if (method === "invite") {
      if (await runToast(create.mutateAsync(v), t("admin.users.invited", { email: v.email }))) onClose();
      return;
    }
    const made = await runToast(createWithPassword.mutateAsync(v));
    if (made) onMade({ user: made.user, password: made });
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.add")}
      description={t(method === "invite" ? "admin.users.inviteHint" : "admin.users.temporaryHint")}
      footer={
        <>
          <Button onClick={onClose}>{t("app.cancel")}</Button>
          <Button variant="primary" icon={method === "invite" ? <Mail /> : <KeyRound />} loading={create.isPending || createWithPassword.isPending} onClick={() => void submit()}>
            {t(method === "invite" ? "admin.users.sendInvite" : "admin.users.createUser")}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <RadioCards
          aria-label={t("admin.users.method")}
          value={method}
          onValueChange={(m) => setMethod(m as typeof method)}
          options={[
            { value: "invite", title: t("admin.users.methodInvite"), icon: <Mail /> },
            { value: "temporaryPassword", title: t("admin.users.methodTemporary"), icon: <KeyRound /> },
          ]}
        />
        <Field label={t("admin.users.name")} required error={fieldErrorOf(error, "name")}>
          <Input data-autofocus value={v.name} maxLength={100} onChange={(e) => setV({ ...v, name: e.target.value })} />
        </Field>
        <Field label={t("admin.users.email")} required error={fieldErrorOf(error, "email")}>
          <Input type="email" value={v.email} onChange={(e) => setV({ ...v, email: e.target.value })} />
        </Field>
        {me?.isAdmin && <Checkbox label={t("admin.users.makeAdmin")} checked={v.isAdmin} onCheckedChange={(c) => setV({ ...v, isAdmin: c === true })} />}
      </div>
    </Dialog>
  );
}

/** 임시 비밀번호: what the administrator passes on — the password alone, or a ready message with the address and the deadline. */
function TemporaryPasswordDialog({ shown, onClose }: { shown: Shown; onClose: () => void }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [copied, setCopied] = useState("");
  const { user, password: p } = shown;
  const message = t("admin.users.temporaryMessage", { url: `${window.location.origin}/login`, email: user.email, password: p.temporaryPassword, until: formatDateTime(p.expiresAt) });
  const copy = async (text: string, done: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(done);
    } catch {
      toast({ tone: "danger", title: t("admin.users.copyFailed") });
    }
  };
  return (
    <Dialog
      open
      onOpenChange={(o) => !o && onClose()}
      size="sm"
      title={t("admin.users.temporaryTitle", { name: user.name })}
      description={t("admin.users.temporaryNote")}
      footer={
        <Button variant="primary" onClick={onClose}>
          {t("admin.users.done")}
        </Button>
      }
    >
      <div className="flex flex-col gap-4">
        <Field label={t("admin.users.email")}>
          <Input readOnly value={user.email} />
        </Field>
        <Field label={t("admin.users.temporaryPassword")} hint={t("admin.users.temporaryUntil", { time: formatDateTime(p.expiresAt) })}>
          <Input readOnly className="font-mono" value={p.temporaryPassword} />
        </Field>
        {p.issuedAt && <p className="text-xs text-fg-muted">{t("admin.users.temporaryIssued", { name: p.issuedBy?.name ?? "-", time: formatDateTime(p.issuedAt) })}</p>}
        <div className="flex flex-wrap gap-2">
          <Button icon={<Copy />} onClick={() => void copy(p.temporaryPassword, t("admin.users.copiedPassword"))}>
            {t("admin.users.copyPassword")}
          </Button>
          <Button icon={<ClipboardCopy />} onClick={() => void copy(message, t("admin.users.copiedMessage"))}>
            {t("admin.users.copyMessage")}
          </Button>
        </div>
        <p role="status" aria-live="polite" className="text-sm text-fg-muted">
          {copied}
        </p>
      </div>
    </Dialog>
  );
}
```

- [ ] **Step 3: 페이지 상태, 행 메뉴, 상태 칸**

`AdminUsersPage`에서 `const [inviting, setInviting] = useState(false);`를 `const [adding, setAdding] = useState(false);`로 바꾸고, 상태 두 개를 더한다:

```tsx
  const [issuing, setIssuing] = useState<AdminUser | null>(null);
  const [shown, setShown] = useState<Shown | null>(null);
  const live = (until: string | null) => !!until && Date.parse(until) > Date.now();
  const look = async (u: AdminUser) => {
    const p = await runToast(api.admin.users.temporaryPassword(u.id));
    if (p) setShown({ user: u, password: p });
  };
```

`actions` 안, `resend` 줄 다음에:

```tsx
    const handlesPassword = (u.status === "active" || u.status === "invited") && !self && (!u.isAdmin || !!me?.isAdmin);
    if (handlesPassword) items.push({ label: t("admin.users.issueTemporary"), icon: <KeyRound />, onSelect: () => setIssuing(u) });
    if (handlesPassword && live(u.temporaryPasswordUntil)) items.push({ label: t("admin.users.showTemporary"), icon: <Eye />, onSelect: () => void look(u) });
```

`PageHeader`의 버튼을 `onClick={() => setAdding(true)}`, 글자 `{t("admin.users.add")}`로 바꾼다.

상태 칸의 `) : (` 이하 `<Badge tone={STATUS_TONE[u.status]}>…</Badge>`를 바꾼다:

```tsx
                      ) : (
                        <span className="inline-flex flex-col items-start gap-0.5">
                          <Badge tone={STATUS_TONE[u.status]}>{t(`admin.users.statuses.${u.status}`)}</Badge>
                          {u.temporaryPasswordUntil &&
                            (live(u.temporaryPasswordUntil) ? (
                              <span className="text-xs text-fg-muted">{t("admin.users.temporaryBadge", { time: formatDateTime(u.temporaryPasswordUntil) })}</span>
                            ) : (
                              <Badge tone="warning">{t("admin.users.temporaryExpired")}</Badge>
                            ))}
                        </span>
                      )}
```

페이지 끝의 `{inviting && <InviteDialog … />}`를 바꾸고 둘을 더한다:

```tsx
      {adding && <AddUserDialog onClose={() => setAdding(false)} onMade={(s) => (setAdding(false), setShown(s))} />}
      {shown && <TemporaryPasswordDialog shown={shown} onClose={() => setShown(null)} />}
      <ConfirmDialog
        open={!!issuing}
        onOpenChange={(o) => !o && setIssuing(null)}
        title={t("admin.users.issueTitle")}
        body={issuing && t("admin.users.issueBody", { name: issuing.name })}
        confirm={t("admin.users.issueConfirm")}
        pending={m.issueTemporary.isPending}
        onConfirm={async () => {
          const target = issuing;
          setIssuing(null);
          if (!target) return;
          const p = await runToast(m.issueTemporary.mutateAsync(target.id));
          if (p) setShown({ user: target, password: p });
        }}
      />
```

- [ ] **Step 4: 타입 검사와 손 확인**

Run: `cd frontend && npx tsc -b --noEmit`
Expected: 오류 없음.

손 확인(5173 가짜 계층): 관리 · 사용자 → **사용자 추가** → **임시 비밀번호 만들기** → 만들기 → 결과 대화상자(비밀번호, 유효 기한, 두 복사 버튼, 복사 뒤 "…복사했습니다") → **확인** → 목록 상태 칸에 "임시 비밀번호 · …까지" → 행 메뉴 **임시 비밀번호 보기**가 같은 비밀번호와 "김서연님이 …에 발급"을 보인다.

- [ ] **Step 5: 커밋**

```bash
git add frontend/src/pages/admin/users.tsx frontend/src/i18n/ko.ts
git commit -F /tmp/openpms-scratch/msg-task8.txt
```

메시지:

```
feat(ui): 관리 · 사용자 adds people with a temporary password, issues it again and shows it until changed

사용자 추가 대화상자에 등록 방법(초대 메일·임시 비밀번호)을 두고, 결과 대화상자에서 비밀번호와 전달용 문구를
복사합니다. 행 메뉴에 임시 비밀번호 발급·보기, 상태 칸에 유효 기한·만료를 보입니다.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 9: e2e F19

**Files:**
- Create: `frontend/e2e/f19-temporary-password.spec.ts`

**Interfaces:**
- Consumes: `frontend/e2e/support.ts`의 `test, expect, login, logout, resetDb, axe, api`; Task 7·8의 화면 문구.

사전 조건: 백엔드를 다시 띄우고(`bash backend/scripts/dev.sh run`), `cd frontend && npm run dev:api`(5174)가 돌고 있어야 한다.

- [ ] **Step 1: 테스트 쓰기**

`frontend/e2e/f19-temporary-password.spec.ts`:

```ts
// F19 임시 비밀번호 (docs/superpowers/specs/2026-10-12-temporary-password-design.md): an administrator makes an account without mail,
// passes the password on as a ready message, looks it up again until it is changed, and the person sets their own at the first sign-in.
import { test, expect, login, logout, resetDb, axe, api } from "./support";

test.describe.configure({ mode: "serial" });
test.beforeAll(() => resetDb());
test.afterAll(() => resetDb());

const OWN = "my-own-pass-2026!";

test("F19-T1 an account made with a temporary password; the first sign-in asks for a password of one's own", async ({ page, context }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/admin/users");
  await page.getByRole("button", { name: "사용자 추가" }).click();
  const add = page.getByRole("dialog", { name: "사용자 추가" });
  await add.getByRole("radio", { name: "임시 비밀번호 만들기" }).click();
  await add.getByLabel("이름").fill("임시 확인");
  await add.getByLabel("이메일").fill("temp.e2e@openpms.local");
  await axe(page, "S35 사용자 추가");
  await add.getByRole("button", { name: "만들기" }).click();

  const shown = page.getByRole("dialog", { name: "임시 확인님의 임시 비밀번호" });
  const password = await shown.getByLabel("임시 비밀번호").inputValue();
  expect(password).toHaveLength(16);
  await axe(page, "S35 임시 비밀번호");
  await shown.getByRole("button", { name: "전달용 문구 복사" }).click();
  await expect(shown.getByText("전달용 문구를 복사했습니다")).toBeVisible();
  const message = await page.evaluate(() => navigator.clipboard.readText());
  expect(message).toContain("이메일: temp.e2e@openpms.local");
  expect(message).toContain(`임시 비밀번호: ${password}`);
  expect(message).toContain("/login");
  await shown.getByRole("button", { name: "확인" }).click();
  const row = page.getByRole("row").filter({ hasText: "temp.e2e@openpms.local" });
  await expect(row.getByText(/임시 비밀번호 · .+까지/)).toBeVisible();

  await logout(page);
  await page.goto("/login");
  await page.getByLabel("이메일").fill("temp.e2e@openpms.local");
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  await expect(page.getByRole("heading", { name: "새 비밀번호 정하기" })).toBeVisible();
  await axe(page, "S01 새 비밀번호 정하기");
  await page.getByLabel("새 비밀번호", { exact: true }).fill(password);
  await page.getByLabel("새 비밀번호 확인").fill(password);
  await page.getByRole("button", { name: "비밀번호 저장" }).click();
  await expect(page.getByText("임시 비밀번호와 다른 비밀번호를 정하세요.")).toBeVisible();
  await page.getByLabel("새 비밀번호", { exact: true }).fill(OWN);
  await page.getByLabel("새 비밀번호 확인").fill(OWN);
  await page.getByRole("button", { name: "비밀번호 저장" }).click();
  await page.waitForURL((u) => !u.pathname.startsWith("/login"));

  // the temporary password is spent; the new one works
  await logout(page);
  const refused = await api<number>(page, "try { await api.auth.login(args[0], args[1]); return 200 } catch (e) { return e.status }", "temp.e2e@openpms.local", password);
  expect(refused).toBe(401);
  await login(page, "temp.e2e@openpms.local", OWN);
});

test("F19-T2 someone who never took up the invitation gets one; the administrator looks it up until it is changed", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  await api(page, "await api.admin.users.create({ name: '초대 대기', email: 'waiting.e2e@openpms.local', isAdmin: false })");
  await page.goto("/admin/users");
  const row = page.getByRole("row").filter({ hasText: "waiting.e2e@openpms.local" });
  await expect(row.getByText("초대됨", { exact: true })).toBeVisible();
  await row.getByRole("button", { name: /메뉴$/ }).click();
  await page.getByRole("menuitem", { name: "임시 비밀번호 발급" }).click();
  await page.getByRole("dialog", { name: "임시 비밀번호를 발급할까요?" }).getByRole("button", { name: "발급" }).click();
  const shown = page.getByRole("dialog", { name: "초대 대기님의 임시 비밀번호" });
  const password = await shown.getByLabel("임시 비밀번호").inputValue();
  await shown.getByRole("button", { name: "확인" }).click();
  await expect(row.getByText("활성", { exact: true })).toBeVisible();

  await row.getByRole("button", { name: /메뉴$/ }).click();
  await page.getByRole("menuitem", { name: "임시 비밀번호 보기" }).click();
  const again = page.getByRole("dialog", { name: "초대 대기님의 임시 비밀번호" });
  await expect(again.getByLabel("임시 비밀번호")).toHaveValue(password);
  await expect(again.getByText(/김서연님이 .+에 발급/)).toBeVisible();
  await again.getByRole("button", { name: "확인" }).click();

  // the person sets their own (through the API this time): the look-up is gone, issuing stays
  await logout(page);
  const token = await api<string>(page, "try { await api.auth.login(args[0], args[1]) } catch (e) { return e.current.changeToken }", "waiting.e2e@openpms.local", password);
  await api(page, "await api.auth.changeTemporaryPassword(args[0], args[1])", token, OWN);
  await logout(page);
  await login(page, "seoyeon.kim@openpms.local");
  await page.goto("/admin/users");
  await row.getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 보기" })).toHaveCount(0);
});

test("F19-T3 a user manager who is not an administrator cannot handle an administrator's password", async ({ page }) => {
  await login(page, "seoyeon.kim@openpms.local");
  const users = await api<{ id: string; email: string }[]>(page, "return await api.admin.users.list()");
  const doyoon = users.find((u) => u.email === "doyoon.han@openpms.local")!.id;
  const seoyeon = users.find((u) => u.email === "seoyeon.kim@openpms.local")!.id;
  const role = await api<{ id: string }>(page, "return await api.admin.roles.create({ name: '사용자 관리자(F19)', scope: 'global', permissions: ['global.manage_users'] })");
  await api(page, "await api.admin.users.setGlobalRoles(args[0], [args[1]])", doyoon, role.id);
  await logout(page);
  await login(page, "doyoon.han@openpms.local");
  await page.goto("/admin/users");
  await page.getByRole("row").filter({ hasText: "seoyeon.kim@openpms.local" }).getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem").first()).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.getByRole("row").filter({ hasText: "haneul.lee@openpms.local" }).getByRole("button", { name: /메뉴$/ }).click();
  await expect(page.getByRole("menuitem", { name: "임시 비밀번호 발급" })).toBeVisible();
  await page.keyboard.press("Escape");
  const refused = await api<number>(page, "try { await api.admin.users.issueTemporaryPassword(args[0]); return 200 } catch (e) { return e.status }", seoyeon);
  expect(refused).toBe(403);
});
```

- [ ] **Step 2: 실행**

Run: `cd frontend && npx playwright test e2e/f19-temporary-password.spec.ts`
Expected: 3 passed. 실패하면 Playwright 보고서의 화면·로그를 보고 선택자나 코드를 고친다(선택자가 문구와 다르면 이 계획의 문구가 기준).

- [ ] **Step 3: 커밋**

```bash
git add frontend/e2e/f19-temporary-password.spec.ts
git commit -F /tmp/openpms-scratch/msg-task9.txt
```

메시지:

```
test(e2e): F19 temporary passwords — made, passed on, looked up, changed at the first sign-in

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```

---

### Task 10: 전체 회귀

**Files:** 없음(검증만). 실패가 나오면 해당 Task의 파일을 고치고 그 Task 이름으로 따로 커밋한다.

- [ ] **Step 1: JUnit 전체**

Run: `bash backend/scripts/dev.sh test`
Expected: `BUILD SUCCESSFUL`. 건수는 104 + 새 테스트(단위 1, `TemporaryPasswordIT` 8, `OidcIT` 1) = 114. `NoPinningTest`와 `PerfBudgetIT`도 통과해야 한다.

- [ ] **Step 2: 화면 타입 검사와 빌드**

Run: `cd frontend && npx tsc -b --noEmit && npm run build`
Expected: 오류 없음. 빌드 결과(`backend/src/main/resources/static`)는 git이 무시한다.

- [ ] **Step 3: e2e 전체**

사전 조건: `bash backend/scripts/dev.sh run`, 5174 `npm run dev:api`, `openpms-idp` 컨테이너.

Run: `cd frontend && npx playwright test`
Expected: 136 passed, 1 skipped(PERF-5). 이전 133에서 F19 3건이 늘어난다.

- [ ] **Step 4: 가짜 계층 손 확인**

5173(`npm run dev`)에서 Task 7·8의 손 확인을 한 번 더 한다(재발급으로 로그아웃되는 것 포함: 자기 자신이 아닌 계정이므로 데모 세션은 유지).

---

### Task 11: 산출물·문서 갱신(판 2.2)

**Files:**
- Modify: `docs/deliverables/01`–`10`(해당 문서), `docs/deliverables/README.md`(개정 이력)
- Modify: `replica/features.csv`, `replica/test-plan.md`, `README.md`, `CLAUDE.md`

CLAUDE.md 규칙대로 **백그라운드 서브에이전트**에게 맡기고, 결과를 검토한 뒤 커밋한다.

- [ ] **Step 1: 서브에이전트에게 맡기기**

Agent(general-purpose, 백그라운드) 지시문에 넣을 것:
- 설계 `docs/superpowers/specs/2026-10-12-temporary-password-design.md` 7절의 표가 범위다. 판 번호 2.2, 날짜 2026-10-12.
- 01: REQ-AUTH-005 "관리자가 임시 비밀번호로 사용자 등록·재발급, 첫 로그인 변경, 바꾸기 전까지 다시 보기"(권장, 완료), 개정 이력 2.2. 02: 같은 행(구현 근거: 커밋 해시, 테스트 `TemporaryPasswordIT`·`TemporaryPasswordsTest`·`OidcIT`·e2e F19-T1~T3).
- 03: 인증 흐름에 변경 토큰, `temporary_passwords` 보관·정리, `IdempotencyFilter` 제외, API 토큰 제외. 04: `POST /admin/users`의 `method`, `POST·GET /admin/users/{id}/temporary-password`, 로그인 403·401의 `current`, `POST /auth/password-change`, `AdminUser.temporaryPasswordUntil`. 05: V19 테이블과 `user_tokens.purpose`. 06: 사용자 추가·결과 대화상자·로그인 변경 폼.
- 07: 새 절 **2.8 임시 비밀번호를 받았을 때**(기존 2.1–2.7 번호와 제목은 그대로). 10: 새 절 **9.2.7 임시 비밀번호로 만들기·다시 발급·보기**, 업그레이드 절에 V19(새 번호로 덧붙임), 보안 안내(평문은 바뀌거나 만료될 때까지 DB에 남음, DB 덤프 원칙 5.3, 유효 시간을 짧게). 기존 번호 붙은 제목은 바꾸거나 지우지 않는다(e2e f17-H3).
- 08·09: 새 테스트 사례와 건수(JUnit 114, e2e 136 + 1 건너뜀 — Task 10의 실제 숫자로).
- `replica/features.csv`: 새 행(사내 개선, could, yes, yes, 근거와 빌드 요약). `replica/test-plan.md`: F19 절, "확인할 것"에 남은 손 확인(실제 메신저로 전달, 가짜 계층의 재발급).
- `README.md`·`CLAUDE.md`: 스키마 V1–V19, 테스트 건수, "지금까지"에 이 기능 한 줄.
- 줄바꿈: 03·04·05·10과 `replica/` 문서는 CRLF, 01·02·06·07·08·09와 README는 LF. 고칠 때 Python으로 `newline` 을 지켜 쓴다. prettier 금지. 리뷰·인용을 지어내지 않는다.
- 커밋하지 말 것(검토 뒤 메인이 커밋).

- [ ] **Step 2: 검토**

```bash
git diff --stat
for f in docs/deliverables/0{3,4,5}-*.md docs/deliverables/10-*.md; do python3 -I -c "import sys; d=open(sys.argv[1],'rb').read(); print(sys.argv[1], d.count(b'\r\n'), d.count(b'\n'))" "$f"; done
grep -n -E '^### 2\.[0-9]+|^#### 9\.2\.[0-9]+' docs/deliverables/07-*.md docs/deliverables/10-*.md
cd frontend && npx playwright test e2e/f17-help.spec.ts
```

Expected: CRLF 파일은 두 숫자가 같고, 기존 절 번호(2.1–2.7, 9.2.1–9.2.6)가 그대로이며 2.8·9.2.7이 새로 있고, f17 통과.

- [ ] **Step 3: 커밋**

```bash
git add docs/deliverables replica/features.csv replica/test-plan.md README.md CLAUDE.md
git status --short | grep -v '^??'
git commit -F /tmp/openpms-scratch/msg-task11.txt
```

`git add docs/deliverables` 전에 `git config core.precomposeunicode`가 `true`인지 확인한다. 아니면 이름이 자모로 분리된 산출물 파일이 따로 추가될 수 있으므로, 사용자에게 `git config core.precomposeunicode true`를 먼저 물어본다.

메시지:

```
docs: deliverables v2.2 (임시 비밀번호: REQ-AUTH-005, API, V19, screens, manuals 2.8 and 9.2.7), tests 114/136+1

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
```
