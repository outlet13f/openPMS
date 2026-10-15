package com.openpms.cicd;

import static com.openpms.db.Tables.WORK_PACKAGES;
import static com.openpms.db.Tables.WORK_PACKAGE_CICD_LINKS;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * Links commits and merge requests to work packages by number. Only work packages in the repository's project and its
 * sub-projects count; a link that exists (even a dismissed one) is never touched, and no journal or notification is made.
 */
@Service
public class CicdLinker {
    private final DSLContext db;

    public CicdLinker(DSLContext db) {
        this.db = db;
    }

    /** The project and all of its sub-projects. */
    public List<UUID> projectTree(UUID projectId) {
        return db.fetch("with recursive t as (select id from projects where id = ? union "
                + "select p.id from projects p join t on p.parent_id = t.id) select id from t", projectId).getValues(0, UUID.class);
    }

    /** Exactly one of commitId / mrId is set. Returns how many links are new. */
    public int link(UUID repositoryId, UUID projectId, List<RefParser.Ref> refs, UUID commitId, UUID mrId) {
        if (refs.isEmpty()) return 0;
        var numbers = refs.stream().map(RefParser.Ref::number).distinct().toList();
        var tree = projectTree(projectId);
        var found = db.select(WORK_PACKAGES.ID, WORK_PACKAGES.NUMBER).from(WORK_PACKAGES)
                .where(WORK_PACKAGES.NUMBER.in(numbers), WORK_PACKAGES.PROJECT_ID.in(tree)).fetch();
        int added = 0;
        var seen = new HashSet<Long>();
        for (var ref : refs) {
            if (!seen.add(ref.number())) continue;
            for (var w : found) {
                if (w.value2() != ref.number()) continue;
                // two partial unique indexes, so "on conflict do nothing" without a target
                added += db.insertInto(WORK_PACKAGE_CICD_LINKS)
                        .set(WORK_PACKAGE_CICD_LINKS.WORK_PACKAGE_ID, w.value1())
                        .set(WORK_PACKAGE_CICD_LINKS.COMMIT_ID, commitId)
                        .set(WORK_PACKAGE_CICD_LINKS.MERGE_REQUEST_ID, mrId)
                        .set(WORK_PACKAGE_CICD_LINKS.SOURCE, ref.source())
                        .onConflictDoNothing().execute();
            }
        }
        return added;
    }
}
