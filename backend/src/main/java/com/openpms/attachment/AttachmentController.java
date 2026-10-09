package com.openpms.attachment;

import static com.openpms.db.Tables.ATTACHMENTS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.SettingsRepo;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.db.tables.records.AttachmentsRecord;
import com.openpms.wp.Journals;
import com.openpms.wp.WorkPackageService;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.tika.Tika;
import org.jooq.DSLContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
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

    /** Shown inline (thumbnails, opening in a tab). SVG is not here: it can carry script. */
    private static final Set<String> INLINE = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Tika TIKA = new Tika();

    private final Current current;
    private final DSLContext db;
    private final WorkPackageService workPackages;
    private final FileStorage storage;
    private final SettingsRepo settings;
    private final Journals journals;

    public AttachmentController(Current current, DSLContext db, WorkPackageService workPackages, FileStorage storage, SettingsRepo settings, Journals journals) {
        this.current = current;
        this.db = db;
        this.workPackages = workPackages;
        this.storage = storage;
        this.settings = settings;
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

    /** "C:\\fakepath\\보고서.pdf" → "보고서.pdf"; control characters out; at most 255 characters. */
    static String cleanName(String original) {
        var name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "file";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    @PostMapping(value = "/work-packages/{number}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<Attachment> upload(@PathVariable long number, @RequestParam("file") MultipartFile file) throws IOException {
        var ctx = current.ctx();
        var f = workPackages.find(ctx, number);
        ctx.require(f.project(), f.wp().getId(), "wp.attach", "파일을 첨부할 권한이 없습니다.");
        long max = settings.integer("attachments.max_bytes", 26_214_400);
        if (file.isEmpty()) throw ApiException.field("file", "빈 파일은 올릴 수 없습니다.");
        if (file.getSize() > max) throw new ApiException(413, "파일이 너무 큽니다.", "파일 하나는 " + (max / 1024 / 1024) + "MB까지 올릴 수 있습니다.");
        var name = cleanName(file.getOriginalFilename());
        String type;
        try (var head = file.getInputStream()) {
            type = TIKA.detect(head.readNBytes(64 * 1024), name);
        }
        var key = storage.newKey();
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (var in = new DigestInputStream(file.getInputStream(), sha)) {
            storage.put(key, in);
        }
        try {
            var a = db.newRecord(ATTACHMENTS);
            a.setWorkPackageId(f.wp().getId());
            a.setFileName(name);
            a.setContentType(type);
            a.setByteSize(file.getSize());
            a.setSha256(sha.digest());
            a.setStorageKey(key);
            a.setAuthorId(ctx.userId());
            a.store();
            a.refresh();
            var changes = new LinkedHashMap<String, List<Object>>();
            changes.put("attachment:" + a.getId(), listOf(null, name)); // one key per file, so merged entries keep every name
            var j = journals.write(ctx, f.wp(), changes, "", null, false);
            journals.fanOut(ctx, f.wp(), j, List.of());
            return ResponseEntity.status(HttpStatus.CREATED).body(toDto(ctx, a, true));
        } catch (RuntimeException e) {
            storage.delete(key); // the row did not make it: do not keep the bytes
            throw e;
        }
    }

    private static List<Object> listOf(Object a, Object b) {
        var l = new java.util.ArrayList<>(2);
        l.add(a);
        l.add(b);
        return l;
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
        var ctx = current.ctx();
        var a = find(ctx, id).attachment();
        boolean inline = INLINE.contains(a.getContentType());
        var encoded = URLEncoder.encode(a.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(inline ? MediaType.parseMediaType(a.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(a.getByteSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "sandbox; default-src 'none'; img-src 'self'")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(new InputStreamResource(storage.open(a.getStorageKey())));
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
        changes.put("attachment:" + a.getId(), listOf(a.getFileName(), null));
        journals.write(ctx, found.wp().wp(), changes, "", null, false);
        storage.delete(a.getStorageKey());
        return ResponseEntity.noContent().build();
    }
}
