package com.openpms.perf;

import static com.openpms.db.Tables.BOARDS;
import static com.openpms.db.Tables.PROJECTS;
import static com.openpms.db.Tables.WORK_PACKAGES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.assertj.core.api.SoftAssertions;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.ExecuteListenerProvider;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 성능 예산 (replica/fixes.md 4번): 작업 1,000건짜리 프로젝트(perf/perf-1000.sql)에서 목록·Gantt·보드·작업 상세가 서버에서 받는 요청을
 * 여러 번 보내, 응답 시간(p95)과 요청 하나에 실행되는 SQL 수가 예산 안인지 봅니다. 시간 예산은 느린 기계에서도 넘지 않을 만큼 넉넉하게 잡고
 * (회귀를 잡는 선), SQL 수와 응답 크기는 기계와 상관없이 같으므로 거의 그대로 잡습니다 — 작업 수만큼 쿼리가 늘어나는 실수(N+1)는 SQL 수로,
 * 응답에 필드를 마구 붙이는 실수는 크기로 걸립니다.
 * 잰 값은 build/reports/perf-budget.md에 남습니다.
 */
@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerfBudgetIT {
    static final int WARM_UP = 5;
    static final int RUNS = 20;

    /** One screen's request(s): the time budget for the 95th percentile, the SQL statements one round may run, the answer's size (uncompressed). */
    record Budget(String screen, String what, long p95Millis, int maxStatements, int maxKb) {}

    record Measured(Budget budget, double medianMillis, double p95Millis, int statements, int bytes) {}

    @Autowired
    WebApplicationContext context;
    @Autowired
    DSLContext db;
    @Autowired
    DataSource dataSource;

    MockMvc mvc;
    MockHttpSession admin;
    UUID projectId;
    long detail;
    UUID boardId;
    ExecuteListenerProvider[] before;
    final List<Measured> results = new ArrayList<>();

    /** SQL statements run on this thread (MockMvc answers on the caller's thread). */
    static final ThreadLocal<int[]> STATEMENTS = ThreadLocal.withInitial(() -> new int[1]);

    @BeforeAll
    void seed() throws Exception {
        var sql = new ClassPathResource("perf/perf-1000.sql").getContentAsString(StandardCharsets.UTF_8);
        try (var c = dataSource.getConnection(); var st = c.createStatement()) {
            c.setAutoCommit(true);
            st.execute(sql);
        }
        projectId = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("perf-1000")).fetchSingle().value1();
        detail = db.select(WORK_PACKAGES.NUMBER).from(WORK_PACKAGES)
                .where(WORK_PACKAGES.PROJECT_ID.eq(projectId), WORK_PACKAGES.SUBJECT.eq("상세 화면 측정용 작업")).fetchSingle().value1();
        boardId = db.select(BOARDS.ID).from(BOARDS).where(BOARDS.PROJECT_ID.eq(projectId)).fetchSingle().value1();
        assertThat(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(projectId))).isEqualTo(1000);
        seedDevTab();
        seedReleaseBoard();

        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());

        // count statements without touching the application's own listeners
        before = db.configuration().executeListenerProviders();
        var counting = new DefaultExecuteListenerProvider(new ExecuteListener() {
            @Override
            public void executeStart(ExecuteContext ctx) {
                STATEMENTS.get()[0]++;
            }
        });
        var all = Arrays.copyOf(before, before.length + 1);
        all[before.length] = counting;
        db.configuration().set(all);
    }

    /** The development tab of the detail work package: the module on, one repository, 20 merge requests and 50 commits linked. */
    void seedDevTab() {
        db.execute("delete from cicd_connections where name = 'perf-cicd'");
        db.execute("update projects set enabled_modules = enabled_modules || array['cicd']::text[] where id = ? and not ('cicd' = any (enabled_modules))", projectId);
        db.execute("insert into cicd_connections (kind, name, base_url, hook_key, active) values ('gitlab', 'perf-cicd', 'https://gitlab.example.com', 'perf-cicd-key', false)");
        db.execute("insert into cicd_repositories (connection_id, external_id, full_path, web_url, project_id) "
                + "select id, 'perf-1', 'perf/web', 'https://gitlab.example.com/perf/web', ? from cicd_connections where name = 'perf-cicd'", projectId);
        db.execute("insert into cicd_merge_requests (repository_id, iid, title, state, source_branch, target_branch, author_name, user_id, updated_at, web_url) "
                + "select r.id, g, 'MR ' || g, 'open', 'topic-' || g, 'main', '박준호', (select id from users where email = 'junho.park@openpms.local'), now() - g * interval '1 hour', "
                + "r.web_url || '/-/merge_requests/' || g from cicd_repositories r, generate_series(1, 20) g where r.external_id = 'perf-1' and r.project_id = ?", projectId);
        db.execute("insert into cicd_commits (repository_id, sha, title, author_name, user_id, committed_at, web_url) "
                + "select r.id, lpad(to_hex(g), 40, '0'), '커밋 ' || g, '박준호', (select id from users where email = 'junho.park@openpms.local'), now() - g * interval '1 minute', "
                + "r.web_url || '/-/commit/' || g from cicd_repositories r, generate_series(1, 50) g where r.external_id = 'perf-1' and r.project_id = ?", projectId);
        db.execute("insert into work_package_cicd_links (work_package_id, merge_request_id, source) select w.id, m.id, 'title' from work_packages w, cicd_merge_requests m "
                + "where w.project_id = ? and w.subject = '상세 화면 측정용 작업' and m.repository_id in (select id from cicd_repositories where project_id = ?)", projectId, projectId);
        db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) select w.id, c.id, 'message' from work_packages w, cicd_commits c "
                + "where w.project_id = ? and w.subject = '상세 화면 측정용 작업' and c.repository_id in (select id from cicd_repositories where project_id = ?)", projectId, projectId);
        // the default environments (insert again is harmless: the unique index on project and lower(name))
        db.execute("insert into cicd_environments (project_id, name, position, production) select ?, e.name, e.position, e.production "
                + "from (values ('개발', 1, false), ('스테이징', 2, false), ('운영', 3, true)) as e(name, position, production) "
                + "on conflict (project_id, lower(name)) do nothing", projectId);
        // builds: two workflows per commit, one per merge request branch
        db.execute("insert into cicd_pipelines (repository_id, external_id, name, sha, ref, status, web_url, finished_at, source, updated_at) "
                + "select c.repository_id, 'c-' || n.name || '-' || c.sha, n.name, c.sha, 'main', 'success', r.web_url || '/-/pipelines/1', now(), 'gitlab', now() "
                + "from cicd_commits c join cicd_repositories r on r.id = c.repository_id, (values ('build'), ('lint')) as n(name) where r.external_id = 'perf-1'");
        db.execute("insert into cicd_pipelines (repository_id, external_id, name, sha, ref, status, web_url, source, updated_at) "
                + "select m.repository_id, 'mr-' || m.iid, 'build', lpad(to_hex(1000 + m.iid::int), 40, '0'), m.source_branch, 'running', r.web_url || '/-/pipelines/2', 'gitlab', now() "
                + "from cicd_merge_requests m join cicd_repositories r on r.id = m.repository_id where r.external_id = 'perf-1'");
        // two deployments: 개발 holds all 50 commits, 스테이징 the oldest 30
        db.execute("insert into cicd_deployments (repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion) "
                + "select r.id, e.id, e.name, 'perf-' || e.position, lpad(to_hex(1), 40, '0'), 'success', r.web_url || '/-/jobs/' || e.position, now(), 'gitlab', 'compared' "
                + "from cicd_repositories r join cicd_environments e on e.project_id = r.project_id and e.name in ('개발', '스테이징') where r.external_id = 'perf-1'");
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) "
                + "select d.environment_id, d.repository_id, c.id, d.id from cicd_deployments d join cicd_commits c on c.repository_id = d.repository_id "
                + "where d.external_id like 'perf-%' and d.repository_id in (select id from cicd_repositories where external_id = 'perf-1') "
                + "and (d.environment_name = '개발' or c.committed_at <= (select min(committed_at) + interval '29 minutes' from cicd_commits where repository_id = d.repository_id)) "
                + "on conflict do nothing");
        db.execute("insert into cicd_environment_heads (repository_id, environment_id, sha, deployment_id, deployed_at) "
                + "select d.repository_id, d.environment_id, d.sha, d.id, d.deployed_at from cicd_deployments d "
                + "where d.external_id like 'perf-%' and d.repository_id in (select id from cicd_repositories where external_id = 'perf-1') on conflict do nothing");
        // a verification of the measured work package on 스테이징 (the dev tab's verification line, 3 more queries)
        db.execute("insert into cicd_verifications (work_package_id, environment_id, result, reason, verified_by, verified_at, basis_at) "
                + "select w.id, e.id, 'passed', '', (select id from users where email = 'junho.park@openpms.local'), now(), "
                + "(select max(ec.added_at) from cicd_environment_commits ec where ec.environment_id = e.id) "
                + "from work_packages w, cicd_environments e where w.project_id = ? and w.subject = '상세 화면 측정용 작업' "
                + "and e.project_id = w.project_id and e.name = '스테이징' "
                + "and not exists (select 1 from cicd_verifications v where v.work_package_id = w.id)", projectId);
    }

    /** The release verification board (staging to production): a production deployment holding the oldest 10 commits, so 20 of staging's 30 differ. */
    void seedReleaseBoard() {
        db.execute("insert into cicd_deployments (repository_id, environment_id, environment_name, external_id, sha, status, web_url, deployed_at, source, inclusion) "
                + "select r.id, e.id, e.name, 'perf-3', lpad(to_hex(1), 40, '0'), 'success', r.web_url || '/-/jobs/3', now() - interval '1 day', 'gitlab', 'compared' "
                + "from cicd_repositories r join cicd_environments e on e.project_id = r.project_id and e.name = '운영' where r.external_id = 'perf-1'");
        db.execute("insert into cicd_environment_commits (environment_id, repository_id, commit_id, deployment_id) "
                + "select d.environment_id, d.repository_id, c.id, d.id from cicd_deployments d "
                + "join lateral (select id from cicd_commits where repository_id = d.repository_id order by committed_at limit 10) c on true where d.external_id = 'perf-3' "
                + "and d.repository_id in (select id from cicd_repositories where external_id = 'perf-1') on conflict do nothing");
        db.execute("insert into cicd_environment_heads (repository_id, environment_id, sha, deployment_id, deployed_at) "
                + "select d.repository_id, d.environment_id, d.sha, d.id, d.deployed_at from cicd_deployments d "
                + "where d.external_id = 'perf-3' and d.repository_id in (select id from cicd_repositories where external_id = 'perf-1') on conflict do nothing");
        // each commit also on another work package (50 of them), by commit order
        db.execute("insert into work_package_cicd_links (work_package_id, commit_id, source) "
                + "select w.id, c.id, 'message' from (select id, row_number() over (order by number) rn from work_packages "
                + "where project_id = ? and subject <> '상세 화면 측정용 작업' order by number limit 50) w "
                + "join (select id, row_number() over (order by committed_at desc) rn from cicd_commits "
                + "where repository_id in (select id from cicd_repositories where external_id = 'perf-1')) c on c.rn = w.rn "
                + "on conflict do nothing", projectId);
        // a merged MR on a work package that already has a card, its merge commit one of the differing commits (the merge-commit join runs with data)
        db.execute("insert into cicd_merge_requests (repository_id, iid, title, state, source_branch, target_branch, author_name, updated_at, web_url, merge_commit_sha) "
                + "select r.id, 900, 'MR 900', 'merged', 'topic-900', 'main', '박준호', now(), r.web_url || '/-/merge_requests/900', lpad(to_hex(30), 40, '0') "
                + "from cicd_repositories r where r.external_id = 'perf-1' and r.project_id = ?", projectId);
        db.execute("insert into work_package_cicd_links (work_package_id, merge_request_id, source) "
                + "select l.work_package_id, m.id, 'title' from work_package_cicd_links l join cicd_commits c on c.id = l.commit_id and c.sha = lpad(to_hex(31), 40, '0') "
                + "join cicd_merge_requests m on m.repository_id = c.repository_id and m.iid = 900 "
                + "join work_packages w on w.id = l.work_package_id where w.subject <> '상세 화면 측정용 작업'");
        db.execute("insert into versions (project_id, name, status, end_date) values (?, 'perf-v', 'open', current_date + 10) on conflict do nothing", projectId);
        db.execute("update work_packages set version_id = (select id from versions where project_id = ? and name = 'perf-v') "
                + "where id in (select l.work_package_id from work_package_cicd_links l join cicd_commits c on c.id = l.commit_id "
                + "join work_packages w on w.id = l.work_package_id where w.project_id = ? and w.subject <> '상세 화면 측정용 작업' "
                + "order by c.committed_at desc limit 40)", projectId, projectId);
        // 10 passed and 3 failed among the work packages that differ (staging not in production)
        db.execute("insert into cicd_verifications (work_package_id, environment_id, result, reason, verified_by, verified_at, basis_at) "
                + "select x.wp, e.id, case when x.rn <= 10 then 'passed' else 'failed' end, case when x.rn <= 10 then '' else '실패 사유' end, "
                + "(select id from users where email = 'seoyeon.kim@openpms.local'), now(), now() "
                + "from (select l.work_package_id wp, row_number() over (order by c.committed_at) rn from work_package_cicd_links l join cicd_commits c on c.id = l.commit_id "
                + "join work_packages w on w.id = l.work_package_id where w.project_id = ? and w.subject <> '상세 화면 측정용 작업' "
                + "and c.id in (select commit_id from cicd_environment_commits ec join cicd_environments s on s.id = ec.environment_id and s.name = '스테이징' where s.project_id = ?) "
                + "and c.id not in (select commit_id from cicd_environment_commits ec join cicd_environments s on s.id = ec.environment_id and s.name = '운영' where s.project_id = ?)) x "
                + "join cicd_environments e on e.project_id = ? and e.name = '스테이징' where x.rn <= 13", projectId, projectId, projectId, projectId);
    }

    @AfterAll
    void cleanUp() throws Exception {
        if (before != null) db.configuration().set(before);
        db.deleteFrom(PROJECTS).where(PROJECTS.IDENTIFIER.eq("perf-1000")).execute();
        db.execute("delete from cicd_connections where name = 'perf-cicd'");
        report();
    }

    MockHttpServletRequestBuilder query(int pageSize, String spec) {
        return post("/api/v1/work-packages/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + projectId + "\",\"page\":1,\"pageSize\":" + pageSize + ",\"spec\":" + spec + "}");
    }

    static final String COLUMNS = "[\"number\",\"subject\",\"type\",\"status\",\"assignee\",\"priority\",\"dueDate\"]";
    /** 목록 기본 보기(모두 열림): hierarchy, number order, the default columns */
    static final String OPEN = "{\"v\":1,\"filters\":[{\"field\":\"status\",\"op\":\"open\",\"values\":[]}],\"columns\":" + COLUMNS
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":true}";
    /** 모든 작업, grouped by status with sums */
    static final String GROUPED = "{\"v\":1,\"filters\":[],\"columns\":[\"number\",\"subject\",\"status\",\"assignee\",\"work\",\"remainingWork\"]"
            + ",\"sort\":[{\"field\":\"dueDate\",\"dir\":\"asc\"}],\"groupBy\":\"status\",\"hierarchy\":false,\"sums\":true}";
    /** Gantt: every item, the tree, the timeline (relations come with it) */
    static final String GANTT = "{\"v\":1,\"filters\":[],\"columns\":[\"number\",\"subject\",\"startDate\",\"dueDate\"]"
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":true,\"timeline\":{\"visible\":true,\"zoom\":\"auto\"}}";
    /** a quick search on the list */
    static final String SEARCH = "{\"v\":1,\"filters\":[],\"columns\":" + COLUMNS
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":false,\"text\":\"API 연동\"}";

    Measured measure(Budget budget, List<MockHttpServletRequestBuilder> requests) throws Exception {
        long[] took = new long[RUNS];
        int statements = 0;
        int bytes = 0;
        for (int run = -WARM_UP; run < RUNS; run++) {
            STATEMENTS.get()[0] = 0;
            int size = 0;
            long t0 = System.nanoTime();
            for (var r : requests) {
                var res = mvc.perform(r.session(admin).with(csrf())).andReturn().getResponse();
                assertThat(res.getStatus()).as(budget.what()).isBetween(200, 299);
                size += res.getContentAsByteArray().length;
            }
            long dt = System.nanoTime() - t0;
            if (run >= 0) {
                took[run] = dt;
                statements = Math.max(statements, STATEMENTS.get()[0]);
                bytes = size;
            }
        }
        Arrays.sort(took);
        var m = new Measured(budget, took[RUNS / 2] / 1e6, took[(int) Math.ceil(RUNS * 0.95) - 1] / 1e6, statements, bytes);
        results.add(m);
        return m;
    }

    @Test
    void theMainScreensStayWithinTheirBudgetsOnAThousandWorkPackages() throws Exception {
        var n = "/api/v1/work-packages/" + detail;
        // the seeded board is what the budget line says: 21 cards, 20 differing commits' work packages + the detail one, 40 of 50 in the version
        var seeded = mvc.perform(get("/api/v1/projects/perf-1000/releases/board").session(admin)).andReturn().getResponse().getContentAsString();
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(seeded, "$.cards")).as("board cards").hasSize(21);
        assertThat((List<?>) com.jayway.jsonpath.JsonPath.read(seeded, "$.cards[?(@.last != null)]")).as("verified cards").hasSize(14);
        var measured = List.of(
                measure(new Budget("목록", "모두 열림 · 계층 · 20건", 250, 27, 100), List.of(query(20, OPEN))),
                measure(new Budget("목록", "모두 열림 · 계층 · 100건", 300, 27, 350), List.of(query(100, OPEN))),
                measure(new Budget("목록", "모든 작업 · 상태별 묶음 · 합계 · 100건", 300, 26, 350), List.of(query(100, GROUPED))),
                measure(new Budget("목록", "빠른 검색 \"API 연동\"", 200, 25, 80), List.of(query(20, SEARCH))),
                measure(new Budget("목록", "필터 선택지", 150, 21, 12), List.of(get("/api/v1/work-packages/filters").param("projectId", projectId.toString()))),
                measure(new Budget("Gantt", "계층 · 타임라인 · 200건", 300, 27, 650), List.of(query(200, GANTT))),
                measure(new Budget("보드", "상태 보드 · 카드 1,000장", 600, 29, 3000), List.of(get("/api/v1/boards/" + boardId))),
                measure(new Budget("작업 상세", "작업 · 이력 40건 · 관계 · 지켜보는 사람", 200, 45, 30),
                        List.of(get(n), get(n + "/activities"), get(n + "/relations"), get(n + "/watchers"))),
                measure(new Budget("작업 상세", "개발 탭 · MR 20 · 커밋 50 · 환경 3 · 빌드 120 · 검증 1", 150, 20, 56), List.of(get(n + "/cicd"))),
                measure(new Budget("릴리스", "검증 보드 · 카드 21 · 차이 커밋 20 · 버전 대조", 200, 18, 27),
                        List.of(get("/api/v1/projects/perf-1000/releases/board"))));
        var soft = new SoftAssertions();
        for (var m : measured) {
            var b = m.budget();
            soft.assertThat(m.p95Millis()).as("%s · %s: p95 %.0f ms (예산 %d ms)", b.screen(), b.what(), m.p95Millis(), b.p95Millis())
                    .isLessThanOrEqualTo(b.p95Millis());
            soft.assertThat(m.statements()).as("%s · %s: SQL %d개 (예산 %d개)", b.screen(), b.what(), m.statements(), b.maxStatements())
                    .isLessThanOrEqualTo(b.maxStatements());
            soft.assertThat(m.bytes() / 1024).as("%s · %s: 응답 %,d KB (예산 %,d KB)", b.screen(), b.what(), m.bytes() / 1024, b.maxKb())
                    .isLessThanOrEqualTo(b.maxKb());
        }
        soft.assertAll();
    }

    /**
     * Requests run on virtual threads. One that waits for the database inside a monitor (synchronized, or the lock
     * ConcurrentHashMap.computeIfAbsent takes) keeps its carrier thread, and with as many carriers as CPUs a few such
     * waits stop the whole server — the load check found two (Snapshot's lazy loader and its shares map). JFR reports each
     * such wait as jdk.VirtualThreadPinned: the main screens, asked for by 20 virtual threads at once, must cause none in
     * our code.
     */
    @Test
    void noRequestKeepsItsCarrierThreadWhileWaiting() throws Exception {
        var n = "/api/v1/work-packages/" + detail;
        var requests = List.of(query(20, OPEN), query(100, GROUPED), query(200, GANTT), get("/api/v1/boards/" + boardId),
                get("/api/v1/work-packages/filters").param("projectId", projectId.toString()), get(n), get(n + "/activities"), get(n + "/relations"),
                get("/api/v1/projects/perf-1000/releases/board"));
        var pinned = new CopyOnWriteArrayList<RecordedEvent>();
        try (var rs = new RecordingStream()) {
            rs.enable("jdk.VirtualThreadPinned").withThreshold(java.time.Duration.ZERO).withStackTrace();
            rs.onEvent("jdk.VirtualThreadPinned", pinned::add);
            rs.startAsync();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int t = 0; t < 20; t++) {
                    pool.submit(() -> {
                        for (var r : requests) {
                            var res = mvc.perform(r.session(admin).with(csrf())).andReturn().getResponse();
                            assertThat(res.getStatus()).isBetween(200, 299);
                        }
                        return null;
                    });
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(2, TimeUnit.MINUTES)).isTrue();
            }
            rs.stop();
        }
        var ours = pinned.stream().filter(e -> e.getStackTrace() != null
                && e.getStackTrace().getFrames().stream().anyMatch(f -> f.getMethod().getType().getName().startsWith("com.openpms"))).toList();
        // the message names our frames and the JDK ones that hold the monitor (computeIfAbsent and the like)
        assertThat(ours).as(() -> "pinned while waiting:\n  " + ours.stream().limit(1).map(e -> e.getStackTrace().getFrames().stream()
                .filter(f -> f.getMethod().getType().getName().startsWith("com.openpms") || f.getMethod().getType().getName().startsWith("java.util.concurrent"))
                .limit(12)
                .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName() + ":" + f.getLineNumber())
                .reduce((a, b) -> a + "\n  " + b).orElse("")).findFirst().orElse("")).isEmpty();
    }

    void report() throws Exception {
        var out = new StringBuilder("# 성능 예산 측정 (PerfBudgetIT)\n\n작업 1,000건 프로젝트, 요청마다 예열 " + WARM_UP + "번 뒤 " + RUNS
                + "번. 서버 안에서 잰 시간(MockMvc, 네트워크·브라우저 제외).\n\n| 화면 | 요청 | 중앙값 | p95 | 예산(p95) | SQL | 예산(SQL) | 응답 크기 | 예산(크기) |\n|---|---|---:|---:|---:|---:|---:|---:|---:|\n");
        for (var m : results) {
            var b = m.budget();
            out.append(String.format(Locale.ROOT, "| %s | %s | %.0f ms | %.0f ms | %d ms | %d | %d | %,d KB | %,d KB |%n", b.screen(), b.what(), m.medianMillis(),
                    m.p95Millis(), b.p95Millis(), m.statements(), b.maxStatements(), m.bytes() / 1024, b.maxKb()));
        }
        var file = Path.of("build/reports/perf-budget.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, out);
        System.out.println(out);
    }
}
