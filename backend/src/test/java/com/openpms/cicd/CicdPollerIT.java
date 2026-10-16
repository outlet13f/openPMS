package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_CONNECTIONS;
import static com.openpms.db.Tables.CICD_EVENTS;
import static com.openpms.db.Tables.CICD_REPOSITORIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = "openpms.jobs.enabled=false")
@ActiveProfiles("dev")
class CicdPollerIT {
    static final String COMMITS = "[ { \"id\": \"aaaaaaa1111111111111111111111111111111aa\", \"title\": \"하나\", \"message\": \"하나\","
            + " \"author_name\": \"박준호\", \"author_email\": \"junho.park@openpms.local\", \"committed_date\": \"2026-10-15T10:00:00.000+09:00\","
            + " \"web_url\": \"https://gitlab.example.com/g/p/-/commit/aaaaaaa\" },"
            + " { \"id\": \"bbbbbbb2222222222222222222222222222222bb\", \"title\": \"둘\", \"message\": \"둘\","
            + " \"author_name\": \"박준호\", \"author_email\": \"junho.park@openpms.local\", \"committed_date\": \"2026-10-15T10:30:00.000+09:00\","
            + " \"web_url\": \"https://gitlab.example.com/g/p/-/commit/bbbbbbb\" } ]";

    @Autowired WebApplicationContext context;
    @Autowired DSLContext db;
    @Autowired CicdPoller poller;
    @Autowired Adapters adapters;
    CicdFixture f;
    MockHttpSession admin;
    FakeGitServer git;
    String project;
    int seq = 7100 + Math.floorMod(System.nanoTime(), 800) * 10;

    @BeforeEach void setUp() throws Exception {
        f = new CicdFixture(MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build());
        admin = f.login(CicdFixture.ADMIN);
        git = new FakeGitServer();
        project = f.newProject(admin, "work_packages", "cicd");
    }

    @AfterEach void tearDown() {
        git.close();
    }

    private UUID connection(String kind, String token) throws Exception {
        return UUID.fromString(f.newConnection(admin, kind, git.baseUrl(), token)[0]);
    }

    private String repo(UUID connection) {
        var external = String.valueOf(seq++);
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.CONNECTION_ID, connection).set(CICD_REPOSITORIES.EXTERNAL_ID, external)
                .set(CICD_REPOSITORIES.FULL_PATH, "g/" + external).set(CICD_REPOSITORIES.WEB_URL, "https://gitlab.example.com/g/" + external)
                .set(CICD_REPOSITORIES.PROJECT_ID, db.select(com.openpms.db.Tables.PROJECTS.ID).from(com.openpms.db.Tables.PROJECTS)
                        .where(com.openpms.db.Tables.PROJECTS.IDENTIFIER.eq(project)).fetchOne().value1())
                .execute();
        return external;
    }

    private void gitlabServes(String external) {
        git.respond("/api/v4/projects/" + external + "/repository/commits", 200, COMMITS);
        git.respond("/api/v4/projects/" + external + "/merge_requests", 200, "cicd/gitlab-mrs.json");
        git.respond("/api/v4/projects/" + external + "/pipelines", 200, "[]");
        git.respond("/api/v4/projects/" + external + "/deployments", 200, "[]");
    }

    private int eventsOf(UUID connection) {
        return db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(connection));
    }

    @Test void pollsCommitsAndMergeRequestsSinceTheCursor() throws Exception {
        var c = connection("gitlab", "glpat-secret-token-1");
        var ext = repo(c);
        gitlabServes(ext);
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(3);
        var reqs = git.requests().stream().filter(r -> r.path().equals("/api/v4/projects/" + ext + "/repository/commits")
                || r.path().equals("/api/v4/projects/" + ext + "/merge_requests")).toList();
        assertThat(reqs).hasSize(2);
        assertThat(reqs.get(0).query()).contains("since=");
        assertThat(reqs.get(1).query()).contains("updated_after=");
        assertThat(reqs.get(0).headers()).containsEntry("private-token", "glpat-secret-token-1");
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).fetchOne();
        assertThat(row.getLastPolledAt()).isNotNull();
        // the cursor is the newest event time (the merge request, updated at 2026-10-15T01:30:00.123Z)
        assertThat(row.getPollCursor().toInstant()).isEqualTo(OffsetDateTime.parse("2026-10-15T01:30:00.123Z").toInstant());
        // the next request asks from ten minutes before the cursor
        poller.poll(Instant.now());
        var second = git.requests().stream().filter(r -> r.path().endsWith("/repository/commits")).toList().get(1);
        assertThat(second.query()).contains("since=2026-10-15T01%3A20%3A00.123Z");
    }

    @Test void aPipelinesListTheTokenMayNotReadStillStoresCommitsAndMovesTheCursor() throws Exception {
        var c = connection("gitlab", "glpat-403");
        var ext = repo(c);
        gitlabServes(ext);
        git.respond("/api/v4/projects/" + ext + "/pipelines", 403, "{}");
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(3);
        assertThat(db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).fetchOne().getPollCursor()).isNotNull();
    }

    @Test void secondPollStoresNothingNew() throws Exception {
        var c = connection("gitlab", "glpat-t2");
        var ext = repo(c);
        gitlabServes(ext);
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(3);
        var before = eventsOf(c);
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(before);
    }

    @Test void githubEnterpriseUsesApiV3() throws Exception {
        var c = connection("github", "ghp-t3");
        db.insertInto(CICD_REPOSITORIES).set(CICD_REPOSITORIES.CONNECTION_ID, c).set(CICD_REPOSITORIES.EXTERNAL_ID, "9001")
                .set(CICD_REPOSITORIES.FULL_PATH, "o/r").set(CICD_REPOSITORIES.WEB_URL, "https://ghe.example.com/o/r")
                .set(CICD_REPOSITORIES.PROJECT_ID, db.select(com.openpms.db.Tables.PROJECTS.ID).from(com.openpms.db.Tables.PROJECTS)
                        .where(com.openpms.db.Tables.PROJECTS.IDENTIFIER.eq(project)).fetchOne().value1())
                .execute();
        git.respond("/api/v3/repositories/9001/commits", 200, "cicd/github-commits.json");
        git.respond("/api/v3/repositories/9001/pulls", 200, "cicd/github-pulls.json");
        git.respond("/api/v3/repositories/9001/actions/runs", 200, "cicd/github-runs.json");
        git.respond("/api/v3/repositories/9001/deployments", 200, "cicd/github-deployments.json");
        git.respond("/api/v3/repositories/9001/deployments/710692/statuses", 200, "cicd/github-deployment-statuses.json");
        git.respond("/api/v3/repositories/9001/deployments/710600/statuses", 200, "[]");
        poller.poll(Instant.now());
        assertThat(git.requests()).extracting(FakeGitServer.Recorded::path).contains("/api/v3/repositories/9001/commits",
                "/api/v3/repositories/9001/actions/runs", "/api/v3/repositories/9001/deployments");
        assertThat(eventsOf(c)).isGreaterThanOrEqualTo(1);
        assertThat(db.fetchCount(CICD_EVENTS, CICD_EVENTS.CONNECTION_ID.eq(c).and(CICD_EVENTS.KIND.eq("pipeline")))).isEqualTo(2);   // deployments are cut by the clock, so only the runs are counted
    }

    @Test void oneFailingRepositoryDoesNotStopOthers() throws Exception {
        var c = connection("gitlab", "glpat-t4");
        var a = repo(c);
        var b = repo(c);
        git.respond("/api/v4/projects/" + a + "/", 500, "{}");
        gitlabServes(b);
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(3);
        var rowA = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c).and(CICD_REPOSITORIES.EXTERNAL_ID.eq(a))).fetchOne();
        assertThat(rowA.getLastPolledAt()).isNull();
        assertThat(rowA.getPollCursor()).isNull();
    }

    @Test void connectionsWithoutTokenOrInactiveAreSkipped() throws Exception {
        var none = connection("gitlab", null);
        var off = connection("gitlab", "glpat-t5");
        db.update(CICD_CONNECTIONS).set(CICD_CONNECTIONS.ACTIVE, false).where(CICD_CONNECTIONS.ID.eq(off)).execute();
        var a = repo(none);
        var b = repo(off);
        gitlabServes(a);
        gitlabServes(b);
        poller.poll(Instant.now());
        assertThat(git.requests()).isEmpty();
    }

    @Test void cleanupKeepsPendingAndRecent() throws Exception {
        var c = connection("gitlab", null);
        var now = Instant.now();
        var old1 = insertEvent(c, "done", now.minusSeconds(91L * 86400));
        var old2 = insertEvent(c, "pending", now.minusSeconds(91L * 86400));
        var recent = insertEvent(c, "done", now.minusSeconds(86400));
        assertThat(poller.cleanup(now)).isGreaterThanOrEqualTo(1);
        assertThat(db.fetchExists(CICD_EVENTS, CICD_EVENTS.ID.eq(old1))).isFalse();
        assertThat(db.fetchExists(CICD_EVENTS, CICD_EVENTS.ID.eq(old2))).isTrue();
        assertThat(db.fetchExists(CICD_EVENTS, CICD_EVENTS.ID.eq(recent))).isTrue();
    }

    /** from..to-1 as GitLab commit JSON with distinct shas. */
    private static String commits(int from, int to, String date) {
        return IntStream.range(from, to).mapToObj(i -> "{ \"id\": \"" + String.format("%040x", i + 0x1000000L) + "\", \"title\": \"c" + i
                + "\", \"message\": \"c" + i + "\", \"author_name\": \"x\", \"author_email\": \"x@x.io\", \"committed_date\": \"" + date
                + "\", \"web_url\": \"https://gitlab.example.com/c/" + i + "\" }").collect(Collectors.joining(",", "[", "]"));
    }

    @Test void paginatesPastTheFirstHundred() throws Exception {
        var c = connection("gitlab", "glpat-t7");
        var ext = repo(c);
        git.respondPage("/api/v4/projects/" + ext + "/repository/commits", 1, 200, commits(0, 100, "2026-10-15T10:00:00Z"));
        git.respondPage("/api/v4/projects/" + ext + "/repository/commits", 2, 200, commits(100, 103, "2026-10-15T10:00:00Z"));
        git.respond("/api/v4/projects/" + ext + "/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/pipelines", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/deployments", 200, "[]");
        poller.poll(Instant.now());
        assertThat(eventsOf(c)).isEqualTo(103);
    }

    @Test void tenFullPagesAreTruncatedButTheCursorStillAdvances() throws Exception {
        var c = connection("gitlab", "glpat-t8");
        var ext = repo(c);
        git.respond("/api/v4/projects/" + ext + "/repository/commits", 200, commits(0, 100, "2026-10-15T10:00:00Z"));
        git.respond("/api/v4/projects/" + ext + "/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/pipelines", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/deployments", 200, "[]");
        var polled = adapters.of("gitlab").poll(new CicdAdapter.Remote(git.baseUrl(), "glpat-t8"), ext, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(polled.truncated()).isTrue();
        long before = git.requests().stream().filter(r -> r.path().endsWith("/repository/commits")).count();
        poller.poll(Instant.now());
        assertThat(git.requests().stream().filter(r -> r.path().endsWith("/repository/commits")).count() - before).isEqualTo(10);
        assertThat(db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).fetchOne().getPollCursor()).isNotNull();
        assertThat(eventsOf(c)).isEqualTo(100);
    }

    @Test void aFutureDatedCommitDoesNotPushTheCursorAhead() throws Exception {
        var c = connection("gitlab", "glpat-t9");
        var ext = repo(c);
        git.respond("/api/v4/projects/" + ext + "/repository/commits", 200, commits(0, 1, "2099-01-01T00:00:00Z"));
        git.respond("/api/v4/projects/" + ext + "/merge_requests", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/pipelines", 200, "[]");
        git.respond("/api/v4/projects/" + ext + "/deployments", 200, "[]");
        var now = Instant.now();
        poller.poll(now);
        var cursor = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).fetchOne().getPollCursor();
        assertThat(cursor.toInstant()).isBeforeOrEqualTo(now.plusMillis(1)); // the column keeps microseconds
    }

    @Test void aFailingRepositoryKeepsItsCursorAndLastPolledAt() throws Exception {
        var c = connection("gitlab", "glpat-t10");
        var ext = repo(c);
        var cursor = OffsetDateTime.parse("2026-10-10T00:00:00Z");
        var polledAt = OffsetDateTime.parse("2026-10-10T00:05:00Z");
        db.update(CICD_REPOSITORIES).set(CICD_REPOSITORIES.POLL_CURSOR, cursor).set(CICD_REPOSITORIES.LAST_POLLED_AT, polledAt)
                .where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).execute();
        git.respond("/api/v4/projects/" + ext + "/", 500, "{}");
        poller.poll(Instant.now());
        var row = db.selectFrom(CICD_REPOSITORIES).where(CICD_REPOSITORIES.CONNECTION_ID.eq(c)).fetchOne();
        assertThat(row.getPollCursor().toInstant()).isEqualTo(cursor.toInstant());
        assertThat(row.getLastPolledAt().toInstant()).isEqualTo(polledAt.toInstant());
    }

    @Test void anUnopenableTokenSkipsThatRepositoryOnly() throws Exception {
        var bad = connection("gitlab", "glpat-t11");
        var good = connection("gitlab", "glpat-t12");
        db.update(CICD_CONNECTIONS).set(CICD_CONNECTIONS.API_TOKEN_ENC, "garbage").where(CICD_CONNECTIONS.ID.eq(bad)).execute();
        var a = repo(bad);
        var b = repo(good);
        gitlabServes(a);
        gitlabServes(b);
        poller.poll(Instant.now());
        assertThat(eventsOf(bad)).isEqualTo(0);
        assertThat(eventsOf(good)).isEqualTo(3);
        assertThat(git.requests().stream().filter(r -> r.path().startsWith("/api/v4/projects/" + a + "/"))).isEmpty();
    }

    @Test void anUnreachableHostIsAskedOncePerRun() throws Exception {
        int dead;
        try (var socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            dead = socket.getLocalPort();   // closed again below: connections are refused
        }
        var down = UUID.fromString(f.newConnection(admin, "gitlab", "http://127.0.0.1:" + dead, "glpat-t13")[0]);
        var up = connection("gitlab", "glpat-t14");
        repo(down);
        repo(down);
        repo(down);
        var b = repo(up);
        gitlabServes(b);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CicdPoller.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            poller.poll(Instant.now());
        } finally {
            logger.detachAppender(appender);
        }
        var aboutDown = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(down.toString())).toList();
        assertThat(aboutDown).hasSize(1);
        assertThat(aboutDown.get(0)).contains("unreachable").doesNotContain("127.0.0.1").doesNotContain("glpat");
        // the other connection is polled as usual
        assertThat(eventsOf(up)).isEqualTo(3);
        assertThat(CicdPoller.unreachable(new java.io.IOException("request timed out"))).isTrue();
        assertThat(CicdPoller.unreachable(new java.io.IOException("unexpected status 500"))).isFalse();
    }

    private UUID insertEvent(UUID connection, String status, Instant receivedAt) {
        var id = UUID.randomUUID();
        db.insertInto(CICD_EVENTS).set(CICD_EVENTS.ID, id).set(CICD_EVENTS.CONNECTION_ID, connection).set(CICD_EVENTS.KIND, "commit")
                .set(CICD_EVENTS.DELIVERY_KEY, "k-" + id).set(CICD_EVENTS.PAYLOAD, JSONB.valueOf("{}")).set(CICD_EVENTS.VIA, "poll")
                .set(CICD_EVENTS.STATUS, status).set(CICD_EVENTS.RECEIVED_AT, receivedAt.atOffset(java.time.ZoneOffset.UTC)).execute();
        return id;
    }
}
