package com.openpms.cicd;

/**
 * One thing that happened in a code host, in the shape both GitLab and GitHub are turned into. kind: "commit" or
 * "merge_request" (the other record is null). deliveryKey is made from the content alone (never from a webhook delivery id),
 * so a webhook and the polling job produce the same key for the same fact.
 */
public record CicdEvent(String kind, String deliveryKey, String repoExternalId, Commit commit, MergeRequest mr) {
    public record Commit(String sha, String title, String message, String authorName, String authorEmail,
                         java.time.OffsetDateTime committedAt, String webUrl) {}

    /** state: opened | draft | merged | closed (GitLab) or open | draft | merged | closed (GitHub). Branches are plain names. */
    public record MergeRequest(long iid, String title, String description, String state, String sourceBranch, String targetBranch,
                               String authorName, String authorEmail, java.time.OffsetDateTime mergedAt,
                               java.time.OffsetDateTime updatedAt, String webUrl) {}
}
