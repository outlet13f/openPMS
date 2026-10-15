package com.openpms.cicd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdMigrationIT {
    @Autowired DSLContext db;

    List<String> perms(String id) {
        return List.of(db.fetchOne("select permissions from roles where id = ?::uuid", id).get(0, String[].class));
    }

    @Test void rolesGetTheCicdPermissions() {
        // 프로젝트 관리자 · 멤버 · 참관자
        assertThat(perms("0329397e-d933-5ac1-b27c-95889ba1a893")).contains("cicd.view", "cicd.link", "cicd.manage");
        assertThat(perms("6d4333fb-e975-5213-8ebc-552cca26e1d4")).contains("cicd.view", "cicd.link").doesNotContain("cicd.manage");
        assertThat(perms("f957f36a-72ca-5379-b8b9-c0e7dabe2d1e")).contains("cicd.view").doesNotContain("cicd.link", "cicd.manage");
        // 비멤버 · 요청자: 없음
        assertThat(perms("21f83874-5c75-520e-aa32-76a829a19e30")).noneMatch(p -> p.startsWith("cicd."));
        assertThat(perms("a9137727-cb80-51db-98d8-8a9a04f84802")).noneMatch(p -> p.startsWith("cicd."));
    }

    @Test void devLeadIsMemberPlusManage() {
        var lead = perms("8e9c194d-fcdf-5c47-b081-ab2b2b61c89a");
        assertThat(lead).containsAll(perms("6d4333fb-e975-5213-8ebc-552cca26e1d4").stream().filter(p -> !p.equals("cicd.manage")).toList());
        assertThat(lead).contains("cicd.manage");
        assertThat(db.fetchOne("select name from roles where id = '8e9c194d-fcdf-5c47-b081-ab2b2b61c89a'").get(0, String.class)).isEqualTo("개발 리더");
        assertThat(db.fetchOne("select scope from roles where id = 'bae8d8f2-850d-5250-9c69-25b1631be394'").get(0, String.class)).isEqualTo("global");
        assertThat(perms("bae8d8f2-850d-5250-9c69-25b1631be394")).containsExactly("global.manage_cicd");
    }

    @Test void moduleIsKnown() {
        assertThat(db.fetchOne("select pg_get_constraintdef(oid) from pg_constraint where conname = 'projects_known_modules'").get(0, String.class))
                .contains("cicd");
    }
}
