package com.openpms.cicd;

import static com.openpms.db.Tables.CICD_COMMITS;
import static com.openpms.db.Tables.USERS;

import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;

/** Stores commits of an attached repository; a new commit is linked to the work packages its message names. */
@Service
public class CicdCommitStore {
    private final DSLContext db;
    private final CicdLinker linker;

    public CicdCommitStore(DSLContext db, CicdLinker linker) {
        this.db = db;
        this.linker = linker;
    }

    /** The commit's id; a commit already known is left as it is (its links were made then). */
    UUID upsert(CicdRepoRef repo, CicdEvent.Commit c) {
        var row = db.insertInto(CICD_COMMITS)
                .set(CICD_COMMITS.REPOSITORY_ID, repo.id()).set(CICD_COMMITS.SHA, c.sha())
                .set(CICD_COMMITS.TITLE, c.title() == null ? "" : c.title())
                .set(CICD_COMMITS.AUTHOR_NAME, c.authorName() == null ? "" : c.authorName())
                .set(CICD_COMMITS.AUTHOR_EMAIL, CicdProcessor.email(c.authorEmail()))
                .set(CICD_COMMITS.USER_ID, userByEmail(c.authorEmail()))
                .set(CICD_COMMITS.COMMITTED_AT, c.committedAt())
                .set(CICD_COMMITS.WEB_URL, CicdProcessor.safeUrl(c.webUrl(), repo.webUrl()))
                .onConflict(CICD_COMMITS.REPOSITORY_ID, CICD_COMMITS.SHA).doNothing()
                .returning(CICD_COMMITS.ID).fetchOne();
        if (row == null)
            return db.select(CICD_COMMITS.ID).from(CICD_COMMITS).where(CICD_COMMITS.REPOSITORY_ID.eq(repo.id()), CICD_COMMITS.SHA.eq(c.sha()))
                    .fetchOne(CICD_COMMITS.ID);
        linker.link(repo.id(), repo.projectId(), RefParser.inText(c.message(), "message"), row.getId(), null);
        return row.getId();
    }

    /** The active user with this address (ignoring case), or null. */
    UUID userByEmail(String email) {
        email = CicdProcessor.email(email);
        if (email.isEmpty()) return null;
        var r = db.select(USERS.ID).from(USERS).where(DSL.lower(USERS.EMAIL).eq(email.toLowerCase()), USERS.STATUS.notIn("locked", "deleted")).fetchOne();
        return r == null ? null : r.value1();
    }
}
