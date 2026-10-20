package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_DEPLOYMENTS;
import static com.openpms.db.Tables.CICD_ENVIRONMENT_HEADS;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * The signals that say an environment's state is not settled: a deployment still being compared (checking), a failed head, the
 * cutoff of a first deployment that could not look further back, and partial checks. Shared by the development tab and the
 * release board so both judge the same way. One query for any number of environments.
 */
@Component
public class CicdEnvironmentSignals {
    /** One deployment that matters: checking, a first one with a cutoff, or a failed one that is the head of its environment. */
    public record Signal(UUID environmentId, UUID repositoryId, String inclusion, OffsetDateTime firstCutoffAt) {}

    /** firstCutoffs: repository to the earliest cutoff of its first deployments. */
    public record Signals(boolean checking, boolean failed, Map<UUID, OffsetDateTime> firstCutoffs) {}

    private final DSLContext db;

    public CicdEnvironmentSignals(DSLContext db) {
        this.db = db;
    }

    /**
     * The signal rows of these environments, looking only at the given repositories. Detached repositories are not filtered
     * here: callers must pass only attached ones (detached_at is null) whose current project is the environments' project.
     */
    public List<Signal> rows(Collection<UUID> environments, Collection<UUID> repositories) {
        return db.select(CICD_DEPLOYMENTS.ENVIRONMENT_ID, CICD_DEPLOYMENTS.REPOSITORY_ID, CICD_DEPLOYMENTS.INCLUSION, CICD_DEPLOYMENTS.FIRST_CUTOFF_AT)
                .from(CICD_DEPLOYMENTS).where(CICD_DEPLOYMENTS.ENVIRONMENT_ID.in(environments), CICD_DEPLOYMENTS.REPOSITORY_ID.in(repositories),
                        CICD_DEPLOYMENTS.INCLUSION.eq("checking")
                                .or(CICD_DEPLOYMENTS.INCLUSION.eq("first").and(CICD_DEPLOYMENTS.FIRST_CUTOFF_AT.isNotNull()))
                                .or(CICD_DEPLOYMENTS.INCLUSION.eq("failed").and(DSL.exists(DSL.selectOne().from(CICD_ENVIRONMENT_HEADS)
                                        .where(CICD_ENVIRONMENT_HEADS.DEPLOYMENT_ID.eq(CICD_DEPLOYMENTS.ID))))))
                .fetch().map(r -> new Signal(r.value1(), r.value2(), r.value3(), r.value4()));
    }

    /** What the rows say about one environment, looking only at the given repositories. */
    public static Signals summarize(List<Signal> rows, UUID environment, Set<UUID> repositories) {
        boolean checking = false, failed = false;
        var cutoffs = new HashMap<UUID, OffsetDateTime>();
        for (var s : rows) {
            if (!environment.equals(s.environmentId()) || !repositories.contains(s.repositoryId())) continue;
            switch (s.inclusion()) {
                case "checking" -> checking = true;
                case "failed" -> failed = true;
                case "first" -> cutoffs.merge(s.repositoryId(), s.firstCutoffAt(), (a, b) -> a.isBefore(b) ? a : b);
                default -> { }
            }
        }
        return new Signals(checking, failed, cutoffs);
    }

    /**
     * The environments (of the given ones) that hold a commit brought in by a check that saw only part of the history. As with
     * {@link #rows}, callers must pass only attached repositories (detached_at is null) of the environments' current project.
     */
    public Set<UUID> partial(Collection<UUID> environments, Collection<UUID> repositories) {
        return Set.copyOf(db.fetch("select distinct ec.environment_id from cicd_environment_commits ec join cicd_deployments d on d.id = ec.deployment_id "
                + "where ec.environment_id = any(?::uuid[]) and ec.repository_id = any(?::uuid[]) and d.inclusion = 'partial'",
                environments.toArray(UUID[]::new), repositories.toArray(UUID[]::new)).getValues(0, UUID.class));
    }
}
