package com.openpms.cicd;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** What one kind of code host (GitLab, GitHub) can do for us: check a webhook, read it, and ask it for what we missed. */
public interface CicdAdapter {
    /** "gitlab" or "github". */
    String kind();

    /** Is this webhook from the holder of the secret? headers: lower-case keys. A missing header is false. */
    boolean verify(Map<String, String> headers, byte[] body, String secret);

    /** The events in a webhook; an event kind we do not use gives an empty list. */
    List<CicdEvent> parse(Map<String, String> headers, byte[] body);

    List<RemoteRepo> listRepositories(Remote remote, String query) throws IOException;

    /** One repository by its external id; null when the host says it does not exist (or is not visible to the token). */
    RemoteRepo repository(Remote remote, String externalId) throws IOException;

    /** Pages of 100 read per list; a list still full after this many pages is cut off (Polled.truncated). */
    int MAX_PAGES = 10;
    int PAGE_SIZE = 100;

    /** Commits and merge requests changed since the given time (all recent ones when null), up to MAX_PAGES pages per list. */
    Polled poll(Remote remote, String repoExternalId, OffsetDateTime since) throws IOException;

    /** truncated: there were more commits than the limit (or more pages than MAX_PAGES). */
    record Comparison(List<CicdEvent.Commit> commits, boolean truncated) {}

    /** Commits head has and base does not (git log base..head), newest first, at most limit; truncated when there were more. */
    default Comparison compare(Remote remote, String repoExternalId, String base, String head, int limit) throws IOException {
        throw new UnsupportedOperationException("no code host API");
    }

    /** Is commit the same as head or one of its ancestors? */
    default boolean contains(Remote remote, String repoExternalId, String head, String commit) throws IOException {
        throw new UnsupportedOperationException("no code host API");
    }

    /** Asks the host who the token belongs to; returns the HTTP status code. */
    int check(Remote remote) throws IOException;

    /** token is plain here, and is never printed: toString hides it. */
    record Remote(String baseUrl, String token) {
        @Override public String toString() { return "Remote[baseUrl=" + baseUrl + ", token=***]"; }
    }

    /** truncated: a list was still full after MAX_PAGES pages, so older items may be missing. */
    record Polled(List<CicdEvent> events, boolean truncated) {}

    record RemoteRepo(String externalId, String fullPath, String webUrl) {}
}
