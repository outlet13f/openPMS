package com.openpms.query;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.Html;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Snapshot;
import com.openpms.customfield.CustomFields;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S12 내보내기: every item of a list (its filters and sort, all pages) as CSV or XLSX, with the chosen columns.
 * Up to LIMIT items, streamed in one answer (architecture.md planned a job above 500; an internal list of a few
 * thousand rows writes in well under a second, so the job is not needed yet). frontend/src/api/fake/export.ts
 * writes the same headers and cells.
 */
@RestController
@RequestMapping("/api/v1")
public class ExportController {
    static final int LIMIT = 5000;
    private static final List<String> BUILT_IN = List.of("number", "subject", "type", "status", "priority", "assignee", "responsible", "author", "version",
            "startDate", "dueDate", "duration", "doneRatio", "updatedAt", "project");
    private static final Map<String, String> HEADERS = Map.ofEntries(
            Map.entry("number", "번호"), Map.entry("subject", "제목"), Map.entry("type", "유형"), Map.entry("status", "상태"), Map.entry("priority", "우선순위"),
            Map.entry("assignee", "담당자"), Map.entry("responsible", "책임자"), Map.entry("author", "작성자"), Map.entry("version", "버전"),
            Map.entry("startDate", "시작일"), Map.entry("dueDate", "완료일"), Map.entry("duration", "기간(일)"), Map.entry("doneRatio", "완료 %"),
            Map.entry("updatedAt", "최근 수정"), Map.entry("project", "프로젝트"));
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final Current current;
    private final QueryEngine engine;

    public ExportController(Current current, QueryEngine engine) {
        this.current = current;
        this.engine = engine;
    }

    /** What a request would export: items matching the list, and of those the ones I may export. */
    private record Selection(long total, List<Dto.WorkPackage> allowed) {}

    /** total > LIMIT: allowed is left empty (not worth loading). */
    private Selection select(Ctx ctx, Map<String, Object> body) {
        var projectId = In.str(body, "projectId");
        var project = projectId == null || projectId.isBlank() ? null : ctx.findProject(projectId);
        if (project != null) ctx.require(project, "wp.export", "내보낼 권한이 없습니다.");
        var spec = QueryController.parse(body.get("spec"));
        var flat = new QueryEngine.Spec(spec.filters(), spec.sort(), false, spec.includeSubprojects(), spec.text(), false, null, false, List.of());
        var first = engine.run(ctx, flat, project, 1, 1000);
        if (first.total() > LIMIT) return new Selection(first.total(), List.of());
        var wps = new ArrayList<Dto.WorkPackage>();
        first.rows().forEach(r -> wps.add(r.wp()));
        for (int page = 2; wps.size() < first.total(); page++) {
            var more = engine.run(ctx, flat, project, page, 1000).rows();
            if (more.isEmpty()) break;
            more.forEach(r -> wps.add(r.wp()));
        }
        // across projects only what I may export; items I only see through a share are left out (share roles do not export)
        var s = ctx.s();
        var allowed = wps.stream().filter(w -> s.project(w.project().id()).map(p -> ctx.can(p, w.id(), "wp.export")).orElse(false)).toList();
        return new Selection(first.total(), allowed);
    }

    public record ExportCount(long total, long count, long excluded, int limit) {}

    /** S12: how many rows the file would have, and how many matching items it leaves out, before downloading. */
    @PostMapping("/work-packages/export/count")
    public ExportCount count(@RequestBody Map<String, Object> body) {
        var sel = select(current.ctx(), body);
        long count = sel.total() > LIMIT ? 0 : sel.allowed().size();
        return new ExportCount(sel.total(), count, sel.total() > LIMIT ? 0 : sel.total() - count, LIMIT);
    }

    @PostMapping("/work-packages/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) throws IOException {
        var ctx = current.ctx();
        var s = ctx.s();
        var format = In.str(body, "format");
        if (!"csv".equals(format) && !"xlsx".equals(format)) throw ApiException.unprocessable("형식을 고르세요.", "CSV 또는 Excel만 내보낼 수 있습니다.");
        var columns = In.strings(body, "columns");
        if (columns == null || columns.isEmpty()) throw ApiException.unprocessable("열을 하나 이상 고르세요.", null);
        for (var c : columns) {
            var cf = CustomFields.idOf(c);
            if (!BUILT_IN.contains(c) && (cf == null || s.customField(cf).isEmpty())) throw ApiException.unprocessable("열을 확인하세요.", "알 수 없는 열입니다: " + c);
        }
        boolean description = Boolean.TRUE.equals(In.bool(body, "includeDescription"));

        var sel = select(ctx, body);
        if (sel.total() > LIMIT)
            throw ApiException.unprocessable("내보낼 작업이 너무 많습니다.", "한 번에 " + String.format("%,d", LIMIT) + "건까지 내보낼 수 있습니다. 필터로 나눠 내보내세요.");
        if (sel.allowed().isEmpty() && sel.total() > 0)
            throw ApiException.unprocessable("내보낼 수 있는 작업이 없습니다.", "목록의 작업이 모두 내보내기 권한이 없는 프로젝트에 있습니다.");
        var allowed = sel.allowed();

        var zone = zone(ctx);
        var rows = new ArrayList<List<Object>>();
        var head = new ArrayList<Object>();
        for (var c : columns) head.add(header(s, c));
        if (description) head.add("설명");
        rows.add(head);
        for (var w : allowed) {
            var row = new ArrayList<Object>();
            for (var c : columns) row.add(cell(w, c, zone));
            if (description) row.add(blankToNull(Html.text(w.descriptionHtml())));
            rows.add(row);
        }

        var title = In.str(body, "title");
        var safe = (title == null ? "" : title).replaceAll("[\\\\/:*?\"<>|]+", " ").trim();
        if (safe.isEmpty()) safe = "작업";
        if (safe.length() > 80) safe = safe.substring(0, 80);
        var fileName = safe + "-" + LocalDate.now(zone).format(DateTimeFormatter.BASIC_ISO_DATE) + "." + format;
        var bytes = "csv".equals(format) ? csv(rows) : xlsx(rows);
        var encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"export." + format + "\"; filename*=UTF-8''" + encoded)
                .contentType("csv".equals(format) ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }

    private static ZoneId zone(Ctx ctx) {
        try {
            return ZoneId.of(ctx.user().timeZone());
        } catch (Exception e) {
            return ZoneId.of("Asia/Seoul");
        }
    }

    private static String header(Snapshot s, String column) {
        var cf = CustomFields.idOf(column);
        if (cf != null) return s.customField(cf).map(Snapshot.CustomFieldRow::name).orElse(column);
        return HEADERS.getOrDefault(column, column);
    }

    private static String blankToNull(String v) {
        return v == null || v.isEmpty() ? null : v;
    }

    /** One cell: numbers stay numbers (Excel can sum them), everything else is text, empty is null. */
    static Object cell(Dto.WorkPackage w, String column, ZoneId zone) {
        return switch (column) {
            case "number" -> w.number();
            case "subject" -> w.subject();
            case "type" -> w.type().name();
            case "status" -> w.status().name();
            case "priority" -> w.priority().name();
            case "assignee" -> w.assignee() == null ? null : w.assignee().name();
            case "responsible" -> w.responsible() == null ? null : w.responsible().name();
            case "author" -> w.author().name();
            case "version" -> w.version() == null ? null : w.version().name();
            case "startDate" -> w.startDate() == null ? null : w.startDate().toString();
            case "dueDate" -> w.dueDate() == null ? null : w.dueDate().toString();
            case "duration" -> w.duration();
            case "doneRatio" -> w.doneRatio();
            case "work" -> w.workHours();
            case "remainingWork" -> w.remainingHours();
            case "updatedAt" -> w.updatedAt().atZoneSameInstant(zone).format(LOCAL);
            case "project" -> w.project().name();
            default -> {
                var id = CustomFields.idOf(column);
                var v = id == null ? null : w.customValues().get(id.toString());
                yield v == null ? null : blankToNull(v.text());
            }
        };
    }

    private static byte[] csv(List<List<Object>> rows) {
        var out = new StringBuilder("﻿"); // BOM so Excel opens UTF-8 (Korean) correctly
        for (var r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) out.append(',');
                var raw = r.get(i);
                var v = raw == null ? "" : String.valueOf(raw);
                // text that a spreadsheet would run as a formula gets a leading apostrophe (CSV injection)
                if (raw instanceof String && !v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
                out.append(v.matches("(?s).*[\",\r\n].*") ? "\"" + v.replace("\"", "\"\"") + "\"" : v);
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xlsx(List<List<Object>> rows) throws IOException {
        try (var wb = new SXSSFWorkbook(200); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("작업");
            var bold = wb.createCellStyle();
            var font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            for (int ri = 0; ri < rows.size(); ri++) {
                var row = sheet.createRow(ri);
                var values = rows.get(ri);
                for (int ci = 0; ci < values.size(); ci++) {
                    var v = values.get(ci);
                    if (v == null) continue;
                    var cell = row.createCell(ci);
                    if (v instanceof Number n) cell.setCellValue(n.doubleValue());
                    else cell.setCellValue(String.valueOf(v));
                    if (ri == 0) cell.setCellStyle(bold);
                }
            }
            int cols = rows.isEmpty() ? 0 : rows.get(0).size();
            for (int ci = 0; ci < cols; ci++) sheet.setColumnWidth(ci, ("제목".equals(rows.get(0).get(ci)) || "설명".equals(rows.get(0).get(ci)) ? 48 : 16) * 256);
            sheet.createFreezePane(0, 1);
            if (cols > 0) sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rows.size() - 1), 0, cols - 1));
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        }
    }
}
