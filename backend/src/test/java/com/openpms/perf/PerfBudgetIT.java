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
    MockHttpSession requester;
    MockHttpSession approver;
    java.util.List<java.util.UUID> otherDesks;
    UUID projectId;
    UUID deskId;
    long requesterTicket;
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
        deskId = db.select(PROJECTS.ID).from(PROJECTS).where(PROJECTS.IDENTIFIER.eq("perf-sd")).fetchSingle().value1();
        requesterTicket = db.select(WORK_PACKAGES.NUMBER).from(WORK_PACKAGES)
                .where(WORK_PACKAGES.PROJECT_ID.eq(deskId), WORK_PACKAGES.SUBJECT.like("% (1)")).fetchSingle().value1();
        assertThat(db.fetchCount(WORK_PACKAGES, WORK_PACKAGES.PROJECT_ID.eq(deskId))).isEqualTo(200);
        // The portal catalog reads every live desk, and the other tests leave one desk each behind: archive them for the run (and
        // the dev desk with them) so the budget measures one desk with three items, not the leftovers of other tests. cleanUp undoes it.
        otherDesks = db.fetch("update projects set archived_at = now() where 'service_desk' = any(enabled_modules) and archived_at is null and identifier <> 'perf-sd' returning id")
                .getValues("id", java.util.UUID.class);

        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seoyeon.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());

        // the requester of the 200 desk tickets signs in through the portal (not a member of the project)
        requester = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(requester).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"minji.choi@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());

        // the approver of the 50 pending catalog requests (not a member either)
        approver = new MockHttpSession();
        mvc.perform(post("/api/v1/auth/login").session(approver).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"alex.kim@openpms.local\",\"password\":\"openpms!\"}")).andExpect(status().isOk());

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

    @AfterAll
    void cleanUp() throws Exception {
        if (before != null) db.configuration().set(before);
        db.deleteFrom(PROJECTS).where(PROJECTS.IDENTIFIER.in("perf-1000", "perf-sd")).execute();
        if (otherDesks != null && !otherDesks.isEmpty()) db.update(PROJECTS).setNull(PROJECTS.ARCHIVED_AT).where(PROJECTS.ID.in(otherDesks)).execute();
        report();
    }

    MockHttpServletRequestBuilder query(int pageSize, String spec) {
        return query(projectId, pageSize, spec);
    }

    MockHttpServletRequestBuilder query(UUID project, int pageSize, String spec) {
        return post("/api/v1/work-packages/query").contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":\"" + project + "\",\"page\":1,\"pageSize\":" + pageSize + ",\"spec\":" + spec + "}");
    }

    /** The service desk list: the open tickets of the desk project with the requester and the SLA columns. */
    MockHttpServletRequestBuilder serviceDeskQuery(int pageSize) {
        return query(deskId, pageSize, SERVICE_DESK);
    }

    static final String COLUMNS = "[\"number\",\"subject\",\"type\",\"status\",\"assignee\",\"priority\",\"dueDate\"]";
    /** 목록 기본 보기(모두 열림): hierarchy, number order, the default columns */
    static final String OPEN = "{\"v\":1,\"filters\":[{\"field\":\"status\",\"op\":\"open\",\"values\":[]}],\"columns\":" + COLUMNS
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":true}";
    /** 모든 작업, grouped by status with sums */
    static final String GROUPED = "{\"v\":1,\"filters\":[],\"columns\":[\"number\",\"subject\",\"status\",\"assignee\",\"work\",\"remainingWork\"]"
            + ",\"sort\":[{\"field\":\"dueDate\",\"dir\":\"asc\"}],\"groupBy\":\"status\",\"hierarchy\":false,\"sums\":true}";
    /** 서비스 데스크 목록: 열린 티켓, 요청자·해결 기한·SLA 열 */
    static final String SERVICE_DESK = "{\"v\":1,\"filters\":[{\"field\":\"status\",\"op\":\"open\",\"values\":[]}]"
            + ",\"columns\":[\"number\",\"subject\",\"status\",\"assignee\",\"requester\",\"slaDue\",\"slaState\"]"
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":false}";
    /** Gantt: every item, the tree, the timeline (relations come with it) */
    static final String GANTT = "{\"v\":1,\"filters\":[],\"columns\":[\"number\",\"subject\",\"startDate\",\"dueDate\"]"
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":true,\"timeline\":{\"visible\":true,\"zoom\":\"auto\"}}";
    /** a quick search on the list */
    static final String SEARCH = "{\"v\":1,\"filters\":[],\"columns\":" + COLUMNS
            + ",\"sort\":[{\"field\":\"number\",\"dir\":\"asc\"}],\"groupBy\":null,\"hierarchy\":false,\"text\":\"API 연동\"}";

    Measured measure(Budget budget, List<MockHttpServletRequestBuilder> requests) throws Exception {
        return measure(budget, requests, admin);
    }

    Measured measure(Budget budget, List<MockHttpServletRequestBuilder> requests, MockHttpSession session) throws Exception {
        long[] took = new long[RUNS];
        int statements = 0;
        int bytes = 0;
        for (int run = -WARM_UP; run < RUNS; run++) {
            STATEMENTS.get()[0] = 0;
            int size = 0;
            long t0 = System.nanoTime();
            for (var r : requests) {
                var res = mvc.perform(r.session(session).with(csrf())).andReturn().getResponse();
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
                // SQL budgets are the measured count + 2 (desk list 20, portal list 6, portal detail 16): the first desk list is the
                // ordinary list plus the ticket join, the portal list and detail are separate endpoints with their own lookups
                measure(new Budget("서비스 데스크 목록", "티켓 20건 · SLA 열", 250, 22, 60), List.of(serviceDeskQuery(20))),
                measure(new Budget("포털 내 요청", "요청자 티켓 200건", 200, 8, 80), List.of(get("/api/v1/portal/tickets")), requester),
                measure(new Budget("포털 요청 상세", "공개 이력 · 첨부", 150, 18, 8), List.of(get("/api/v1/portal/tickets/" + requesterTicket)), requester),
                // the catalog (one desk, three items; measured 14 SQL) and the approval inbox (50 pending rows; measured 4 SQL): measured + 2 SQL like the portal budgets above
                measure(new Budget("포털 카탈로그", "서비스 데스크 · 항목 3개", 150, 16, 8), List.of(get("/api/v1/portal/desks")), requester),
                measure(new Budget("내 승인함", "승인 대기 50건", 150, 6, 20), List.of(get("/api/v1/portal/approvals")), approver));
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
                get("/api/v1/work-packages/filters").param("projectId", projectId.toString()), get(n), get(n + "/activities"), get(n + "/relations"), serviceDeskQuery(20));
        var portal = List.of(get("/api/v1/portal/tickets"), get("/api/v1/portal/tickets/" + requesterTicket), get("/api/v1/portal/desks"));
        var approvals = List.of(get("/api/v1/portal/approvals"));
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
                        for (var r : portal) {
                            var res = mvc.perform(r.session(requester).with(csrf())).andReturn().getResponse();
                            assertThat(res.getStatus()).isBetween(200, 299);
                        }
                        for (var r : approvals) {
                            var res = mvc.perform(r.session(approver).with(csrf())).andReturn().getResponse();
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
