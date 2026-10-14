package com.openpms.attachment;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.db.tables.records.AttachmentsRecord;
import com.openpms.wp.Journals;
import com.openpms.wp.WorkPackageService;
import com.openpms.webhook.Webhooks;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Work package files (S08 파일 탭). The type comes from the content (Tika), not the name; only plain image types
 * open in the browser, everything else downloads, and downloads are sandboxed.
 */
@RestController
@RequestMapping("/api/v1")
public class AttachmentController {
    public record Attachment(UUID id, String fileName, String contentType, long byteSize, Dto.UserRef author, OffsetDateTime createdAt, String url,
                             boolean canDelete) {}

    private final Current current;
    private final DSLContext db;
    private final WorkPackageService workPackages;
    private final FileStorage storage;
    private final AttachmentStore store;
    private final Journals journals;
    private final Webhooks webhooks;

    public AttachmentController(Current current, DSLContext db, WorkPackageService workPackages, FileStorage storage, AttachmentStore store, Journals journals,
                                Webhooks webhooks) {
        this.webhooks = webhooks;
        this.current = current;
        this.db = db;
        this.workPackages = workPackages;
        this.storage = storage;
        this.store = store;
        this.journals = journals;
    }

    private Attachment toDto(Ctx ctx, AttachmentsRecord a, boolean canAttach) {
        return new Attachment(a.getId(), a.getFileName(), a.getContentType(), a.getByteSize(), Refs.user(ctx.s(), a.getAuthorId()), a.getCreatedAt(),
                "/api/v1/attachments/" + a.getId() + "/content", canAttach);
    }

    @GetMapping("/work-packages/{number}/attachments")
    public List<Attachment> list(@PathVariable long number) {
        var ctx = current.ctx();
        var f = workPackages.find(ctx, number);
        boolean canAttach = ctx.can(f.project(), f.wp().getId(), "wp.attach");
        return db.selectFrom(ATTACHMENTS).where(ATTACHMENTS.WORK_PACKAGE_ID.eq(f.wp().getId())).orderBy(ATTACHMENTS.CREATED_AT).fetch().stream()
                .map(a -> toDto(ctx, a, canAttach)).toList();
    }

    @PostMapping(value = "/work-packages/{number}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Attachment> upload(@PathVariable long number, @RequestParam("file") MultipartFile file) throws IOException {
        var ctx = current.ctx();
        var f = workPackages.find(ctx, number);
        ctx.require(f.project(), f.wp().getId(), "wp.attach", "파일을 첨부할 권한이 없습니다.");
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(ctx, store.save(ctx, f, file), true));
    }

    private record Found(AttachmentsRecord attachment, WorkPackageService.Found wp) {}

    private Found find(Ctx ctx, UUID id) {
        var a = db.selectFrom(ATTACHMENTS).where(ATTACHMENTS.ID.eq(id)).fetchOne();
        if (a == null || a.getWorkPackageId() == null) throw ApiException.notFound("파일을 찾을 수 없습니다.");
        var number = db.select(WORK_PACKAGES.NUMBER).from(WORK_PACKAGES).where(WORK_PACKAGES.ID.eq(a.getWorkPackageId())).fetchOne(WORK_PACKAGES.NUMBER);
        try {
            return new Found(a, workPackages.find(ctx, number));
        } catch (ApiException e) {
            throw ApiException.notFound("파일을 찾을 수 없습니다.");
        }
    }

    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id) throws IOException {
        return store.serve(find(current.ctx(), id).attachment());
    }

    @DeleteMapping("/attachments/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        var ctx = current.ctx();
        var found = find(ctx, id);
        ctx.require(found.wp().project(), found.wp().wp().getId(), "wp.attach", "파일을 지울 권한이 없습니다.");
        var a = found.attachment();
        a.delete();
        var changes = new LinkedHashMap<String, List<Object>>();
        changes.put("attachment:" + a.getId(), AttachmentStore.listOf(a.getFileName(), null));
        var j = journals.write(ctx, found.wp().wp(), changes, "", null, false);
        webhooks.enqueue(ctx, found.wp().wp(), "work_package.updated", Map.of("changes", Webhooks.changes(journals.toDto(ctx, j, found.wp().project(), changes))));
        storage.delete(a.getStorageKey());
        return ResponseEntity.noContent().build();
    }
}
