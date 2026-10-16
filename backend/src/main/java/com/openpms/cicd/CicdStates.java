package com.openpms.cicd;

import java.time.OffsetDateTime;
import java.util.Collection;

/** Order of build and deployment notices, and the one badge for several workflows of one commit or branch. */
final class CicdStates {
    private CicdStates() {}

    /** running comes before an end (success, failed, canceled). */
    static int rank(String status) {
        return "running".equals(status) ? 0 : 1;
    }

    /** Does a notice (newStatus at newAt) replace what is stored? Later wins; at the same instant an end beats running. */
    static boolean replaces(String oldStatus, OffsetDateTime oldAt, String newStatus, OffsetDateTime newAt) {
        if (oldStatus == null || oldAt == null) return true;
        if (newAt == null) return false;
        int c = newAt.toInstant().compareTo(oldAt.toInstant());
        if (c != 0) return c > 0;
        return rank(newStatus) > rank(oldStatus);
    }

    /** latestPerName: the status of the newest build of each workflow name. Any failure shows; then running; all canceled is canceled. */
    static String summarize(Collection<String> latestPerName) {
        if (latestPerName.isEmpty()) return null;
        if (latestPerName.contains("failed")) return "failed";
        if (latestPerName.contains("running")) return "running";
        if (latestPerName.stream().allMatch("canceled"::equals)) return "canceled";
        return "success";
    }
}
