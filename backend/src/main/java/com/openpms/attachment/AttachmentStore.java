package com.openpms.attachment;

import static com.openpms.db.Tables.ATTACHMENTS;

import com.openpms.common.ApiException;
import com.openpms.common.SettingsRepo;
import com.openpms.core.Ctx;
import com.openpms.db.tables.records.AttachmentsRecord;
import com.openpms.wp.Journals;
import com.openpms.wp.WorkPackageService;
import com.openpms.webhook.Webhooks;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.tika.Tika;
import org.jooq.DSLContext;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * Saving and serving an attachment, after the caller decided who may: the work package files tab (AttachmentController)
 * and the requester's portal share this. The type comes from the content (Tika), not the name; only plain image types
 * open in the browser, everything else downloads, and downloads are sandboxed.
 */
@Component
public class AttachmentStore {
    /** Shown inline (thumbnails, opening in a tab). SVG is not here: it can carry script. */
    private static final Set<String> INLINE = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");
    private static final Tika TIKA = new Tika();

    private final DSLContext db;
    private final FileStorage storage;
    private final SettingsRepo settings;
    private final Journals journals;
    private final Webhooks webhooks;

    public AttachmentStore(DSLContext db, FileStorage storage, SettingsRepo settings, Journals journals, Webhooks webhooks) {
        this.db = db;
        this.storage = storage;
        this.settings = settings;
        this.journals = journals;
        this.webhooks = webhooks;
    }

    /** "C:\\fakepath\\보고서.pdf" → "보고서.pdf"; control characters out; at most 255 characters. */
    static String cleanName(String original) {
        var name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty()) name = "file";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    /** Stores the bytes and the row, writes the journal and the webhook. The caller owns the transaction and the permission check. */
    public AttachmentsRecord save(Ctx ctx, WorkPackageService.Found f, MultipartFile file) throws IOException {
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
            webhooks.enqueue(ctx, f.wp(), "work_package.updated", Map.of("changes", Webhooks.changes(journals.toDto(ctx, j, f.project(), changes))));
            return a;
        } catch (RuntimeException e) {
            storage.delete(key); // the row did not make it: do not keep the bytes
            throw e;
        }
    }

    static List<Object> listOf(Object a, Object b) {
        var l = new java.util.ArrayList<>(2);
        l.add(a);
        l.add(b);
        return l;
    }

    public ResponseEntity<InputStreamResource> serve(AttachmentsRecord a) throws IOException {
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
}
