package com.openpms.cicd;

/**
 * One thing that happened in a code host or build tool, in the shape every adapter turns it into. kind: "commit",
 * "merge_request", "pipeline" or "deployment" (the other records are null). deliveryKey is made from the content alone (never
 * from a webhook delivery id), so a webhook and the polling job produce the same key for the same fact. repoPath: only from
 * the standard adapters (repoExternalId is then null).
 */
public record CicdEvent(String kind, String deliveryKey, String repoExternalId, Commit commit, MergeRequest mr,
                        Pipeline pipeline, Deployment deployment, String repoPath) {
    public CicdEvent(String kind, String deliveryKey, String repoExternalId, Commit commit, MergeRequest mr) {
        this(kind, deliveryKey, repoExternalId, commit, mr, null, null, null);
    }


    public record Commit(String sha, String title, String message, String authorName, String authorEmail,
                         java.time.OffsetDateTime committedAt, String webUrl) {}

    /** state: opened | draft | merged | closed (GitLab) or open | draft | merged | closed (GitHub). Branches are plain names. */
    public record MergeRequest(long iid, String title, String description, String state, String sourceBranch, String targetBranch,
                               String authorName, String authorEmail, java.time.OffsetDateTime mergedAt,
                               java.time.OffsetDateTime updatedAt, String webUrl, String mergeCommitSha) {
        public MergeRequest(long iid, String title, String description, String state, String sourceBranch, String targetBranch,
                            String authorName, String authorEmail, java.time.OffsetDateTime mergedAt,
                            java.time.OffsetDateTime updatedAt, String webUrl) {
            this(iid, title, description, state, sourceBranch, targetBranch, authorName, authorEmail, mergedAt, updatedAt, webUrl, null);
        }
    }

    /** status: running | success | failed | canceled (adapters normalize). sha: lower-case hex. name: "" when the tool has none. */
    public record Pipeline(String externalId, String name, String sha, String ref, String status, String webUrl,
                           java.time.OffsetDateTime startedAt, java.time.OffsetDateTime finishedAt, java.time.OffsetDateTime updatedAt) {}

    /** status: running | success | failed. sha: lower-case hex 7-64 or null (then image may give it). environment: the tool's name. */
    public record Deployment(String externalId, String sha, String image, String environment, String status, String webUrl,
                             java.time.OffsetDateTime deployedAt) {}
}
