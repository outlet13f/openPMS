package com.openpms.query;

import com.openpms.api.Dto;
import com.openpms.common.Dates;
import com.openpms.common.Html;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/**
 * S12 PDF 내보내기: a table (the chosen columns), a report (each item with its attributes and description) or a Gantt
 * chart, on A4 with the Korean font embedded (Pretendard, SIL OFL 1.1, subset; licence in resources/fonts). Characters the
 * font has no glyph for are written as "?" so a file is always produced.
 */
@Component
public class PdfExport {
    private static final PDRectangle LANDSCAPE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 36;
    private static final Color TEXT = new Color(0x16, 0x19, 0x1d);
    private static final Color MUTED = new Color(0x58, 0x61, 0x6c);
    private static final Color LINE = new Color(0xdd, 0xe1, 0xe6);
    private static final Color HEAD_BG = new Color(0xf1, 0xf3, 0xf5);
    private static final Color TODAY = new Color(0xc6, 0x2f, 0x2f);

    /** One document being written: fonts, pages, the current page and its stream. */
    private static final class Doc implements AutoCloseable {
        final PDDocument pdf = new PDDocument();
        final PDType0Font regular;
        final PDType0Font bold;
        final PDRectangle size;
        final String title;
        final String subtitle;
        PDPageContentStream out;
        float y;
        private final Map<Integer, Boolean> glyphs = new HashMap<>();

        Doc(PDRectangle size, String title, String subtitle) throws IOException {
            this.size = size;
            this.title = title;
            this.subtitle = subtitle;
            regular = font("Pretendard-Regular.ttf");
            bold = font("Pretendard-Bold.ttf");
        }

        private PDType0Font font(String name) throws IOException {
            try (InputStream in = PdfExport.class.getResourceAsStream("/fonts/" + name)) {
                if (in == null) throw new IOException("font missing: " + name);
                return PDType0Font.load(pdf, in, true);
            }
        }

        /** Text the font can draw: control characters become spaces, missing glyphs "?". */
        String safe(String s) {
            if (s == null) return "";
            var b = new StringBuilder();
            s.codePoints().forEach(cp -> {
                if (Character.isISOControl(cp)) b.append(' ');
                else if (glyphs.computeIfAbsent(cp, c -> {
                    try {
                        regular.encode(new String(Character.toChars(c)));
                        return true;
                    } catch (IllegalArgumentException | IOException e) {
                        return false;
                    }
                })) b.appendCodePoint(cp);
                else b.append('?');
            });
            return b.toString();
        }

        float width(PDType0Font f, float sizePt, String s) {
            try {
                return f.getStringWidth(s) / 1000f * sizePt;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** The longest start of s that fits in w, with "…" when cut. */
        String fit(PDType0Font f, float sizePt, String s, float w) {
            s = safe(s);
            if (width(f, sizePt, s) <= w) return s;
            int lo = 0, hi = s.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (width(f, sizePt, s.substring(0, mid) + "…") <= w) lo = mid;
                else hi = mid - 1;
            }
            return s.substring(0, lo) + "…";
        }

        /** Lines of s that fit in w (wrapping at spaces where it can, else at any character). */
        List<String> wrap(PDType0Font f, float sizePt, String s, float w) {
            var lines = new ArrayList<String>();
            for (var para : safe(s).split("\n", -1)) {
                var line = new StringBuilder();
                for (var word : para.split("(?<= )")) {
                    if (width(f, sizePt, line + word) <= w) {
                        line.append(word);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        lines.add(line.toString().stripTrailing());
                        line.setLength(0);
                    }
                    for (int i = 0; i < word.length(); i++) {
                        if (width(f, sizePt, line.toString() + word.charAt(i)) > w && !line.isEmpty()) {
                            lines.add(line.toString());
                            line.setLength(0);
                        }
                        line.append(word.charAt(i));
                    }
                }
                lines.add(line.toString().stripTrailing());
            }
            return lines;
        }

        void newPage() throws IOException {
            if (out != null) out.close();
            var page = new PDPage(size);
            pdf.addPage(page);
            out = new PDPageContentStream(pdf, page);
            y = size.getHeight() - MARGIN;
            text(bold, 14, MARGIN, y - 14, title, TEXT);
            text(regular, 8.5f, MARGIN, y - 28, subtitle, MUTED);
            y -= 40;
        }

        void text(PDType0Font f, float sizePt, float x, float yy, String s, Color c) throws IOException {
            out.beginText();
            out.setFont(f, sizePt);
            out.setNonStrokingColor(c);
            out.newLineAtOffset(x, yy);
            out.showText(safe(s));
            out.endText();
        }

        void rect(float x, float yy, float w, float h, Color fill) throws IOException {
            out.setNonStrokingColor(fill);
            out.addRect(x, yy, w, h);
            out.fill();
        }

        void line(float x1, float y1, float x2, float y2, Color c, float widthPt) throws IOException {
            out.setStrokingColor(c);
            out.setLineWidth(widthPt);
            out.moveTo(x1, y1);
            out.lineTo(x2, y2);
            out.stroke();
        }

        /** Page numbers on every page, then the file. */
        byte[] finish() throws IOException {
            if (out != null) out.close();
            out = null;
            int n = pdf.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                try (var s = new PDPageContentStream(pdf, pdf.getPage(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    var label = (i + 1) + " / " + n;
                    s.beginText();
                    s.setFont(regular, 8);
                    s.setNonStrokingColor(MUTED);
                    s.newLineAtOffset(size.getWidth() - MARGIN - width(regular, 8, label), MARGIN / 2);
                    s.showText(label);
                    s.endText();
                }
            }
            var bytes = new ByteArrayOutputStream();
            pdf.save(bytes);
            return bytes.toByteArray();
        }

        @Override
        public void close() throws IOException {
            if (out != null) out.close();
            pdf.close();
        }
    }

    /** 표: the chosen columns; the subject gets the most room; header repeated on each page. */
    public byte[] table(String title, String subtitle, List<String> headers, List<List<Object>> rows) throws IOException {
        try (var d = new Doc(LANDSCAPE, title, subtitle)) {
            float full = LANDSCAPE.getWidth() - 2 * MARGIN;
            int subjectAt = headers.indexOf("제목");
            float[] w = new float[headers.size()];
            float unit = full / (headers.size() + (subjectAt >= 0 ? 3 : 0));
            for (int i = 0; i < w.length; i++) w[i] = i == subjectAt ? unit * 4 : unit;
            float rowH = 16;
            Runnable[] head = new Runnable[1];
            head[0] = () -> {
                try {
                    d.rect(MARGIN, d.y - rowH, full, rowH, HEAD_BG);
                    float x = MARGIN;
                    for (int i = 0; i < headers.size(); i++) {
                        d.text(d.bold, 8, x + 3, d.y - 11, d.fit(d.bold, 8, headers.get(i), w[i] - 6), TEXT);
                        x += w[i];
                    }
                    d.y -= rowH;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            d.newPage();
            head[0].run();
            for (var r : rows) {
                if (d.y - rowH < MARGIN + 12) {
                    d.newPage();
                    head[0].run();
                }
                float x = MARGIN;
                for (int i = 0; i < headers.size(); i++) {
                    var v = i < r.size() && r.get(i) != null ? String.valueOf(r.get(i)) : "";
                    d.text(d.regular, 8, x + 3, d.y - 11, d.fit(d.regular, 8, v, w[i] - 6), TEXT);
                    x += w[i];
                }
                d.line(MARGIN, d.y - rowH, MARGIN + full, d.y - rowH, LINE, 0.5f);
                d.y -= rowH;
            }
            if (rows.isEmpty()) d.text(d.regular, 9, MARGIN, d.y - 14, "내보낼 작업이 없습니다.", MUTED);
            return d.finish();
        }
    }

    /** 보고서: each item with its attributes and description, one after another (portrait). */
    public byte[] report(String title, String subtitle, List<Dto.WorkPackage> items) throws IOException {
        try (var d = new Doc(PDRectangle.A4, title, subtitle)) {
            float full = PDRectangle.A4.getWidth() - 2 * MARGIN;
            d.newPage();
            for (var w : items) {
                var heading = d.wrap(d.bold, 11.5f, "#" + w.number() + " " + w.subject(), full);
                var attrs = new ArrayList<String>();
                attrs.add("유형 " + w.type().name() + " · 상태 " + w.status().name() + " · 우선순위 " + w.priority().name());
                attrs.add("담당자 " + (w.assignee() == null ? "-" : w.assignee().name()) + " · 책임자 " + (w.responsible() == null ? "-" : w.responsible().name())
                        + (w.version() == null ? "" : " · 버전 " + w.version().name()));
                var dates = (w.startDate() == null ? "-" : Dates.format(w.startDate())) + " ~ " + (w.dueDate() == null ? "-" : Dates.format(w.dueDate()));
                attrs.add("기간 " + dates + (w.doneRatio() == null ? "" : " · 완료 " + w.doneRatio() + "%") + " · 프로젝트 " + w.project().name());
                var desc = Html.text(w.descriptionHtml());
                var body = desc.isBlank() ? List.<String>of() : d.wrap(d.regular, 9, desc, full);
                float need = heading.size() * 15 + attrs.size() * 12 + Math.min(body.size(), 6) * 12 + 18;
                if (d.y - need < MARGIN + 12) d.newPage();
                for (var h : heading) {
                    d.text(d.bold, 11.5f, MARGIN, d.y - 12, h, TEXT);
                    d.y -= 15;
                }
                for (var a : attrs) {
                    d.text(d.regular, 8.5f, MARGIN, d.y - 10, d.fit(d.regular, 8.5f, a, full), MUTED);
                    d.y -= 12;
                }
                for (var line : body) {
                    if (d.y - 12 < MARGIN + 12) d.newPage();
                    d.text(d.regular, 9, MARGIN, d.y - 10, line, TEXT);
                    d.y -= 12;
                }
                d.y -= 6;
                d.line(MARGIN, d.y, MARGIN + full, d.y, LINE, 0.5f);
                d.y -= 10;
            }
            if (items.isEmpty()) d.text(d.regular, 9, MARGIN, d.y - 14, "내보낼 작업이 없습니다.", MUTED);
            return d.finish();
        }
    }

    /** Gantt: subjects on the left, bars on a day scale (weeks or months for long plans), today in red (landscape). */
    public byte[] gantt(String title, String subtitle, List<Dto.WorkPackage> items, LocalDate today) throws IOException {
        try (var d = new Doc(LANDSCAPE, title, subtitle)) {
            float full = LANDSCAPE.getWidth() - 2 * MARGIN;
            float left = 200;
            float chartW = full - left;
            LocalDate from = today.minusDays(7), to = today.plusDays(28);
            for (var w : items) {
                if (w.startDate() != null && w.startDate().isBefore(from)) from = w.startDate();
                if (w.dueDate() != null && w.dueDate().isAfter(to)) to = w.dueDate();
            }
            from = from.minusDays(2);
            to = to.plusDays(3);
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            float dayW = chartW / days;
            final LocalDate start = from;
            float rowH = 15;
            Runnable[] head = new Runnable[1];
            head[0] = () -> {
                try {
                    d.rect(MARGIN, d.y - 22, full, 22, HEAD_BG);
                    d.text(d.bold, 8, MARGIN + 3, d.y - 14, "작업", TEXT);
                    for (long i = 0; i < days; i++) {
                        var day = start.plusDays(i);
                        float x = MARGIN + left + i * dayW;
                        if (day.getDayOfMonth() == 1 || i == 0) d.text(d.bold, 7.5f, x + 2, d.y - 9, day.getYear() + "." + day.getMonthValue(), TEXT);
                        boolean tick = dayW >= 9 || day.getDayOfWeek().getValue() == 1;
                        if (tick) d.text(d.regular, 6.5f, x + 1, d.y - 19, String.valueOf(day.getDayOfMonth()), MUTED);
                    }
                    d.y -= 22;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
            d.newPage();
            head[0].run();
            float top = d.y;
            for (var w : items) {
                if (d.y - rowH < MARGIN + 12) {
                    todayLine(d, today, start, dayW, left, top, days);
                    d.newPage();
                    head[0].run();
                    top = d.y;
                }
                d.text(d.regular, 7.5f, MARGIN + 3, d.y - 10.5f, d.fit(d.regular, 7.5f, "#" + w.number() + " " + w.subject(), left - 8), TEXT);
                var color = parse(w.type().color());
                if (w.startDate() != null && w.dueDate() != null) {
                    float x1 = MARGIN + left + ChronoUnit.DAYS.between(start, w.startDate()) * dayW;
                    float x2 = MARGIN + left + (ChronoUnit.DAYS.between(start, w.dueDate()) + 1) * dayW;
                    if (w.type().isMilestone()) {
                        float cx = x1 + dayW / 2, cy = d.y - rowH / 2;
                        d.out.setNonStrokingColor(color);
                        d.out.moveTo(cx, cy + 4.5f);
                        d.out.lineTo(cx + 4.5f, cy);
                        d.out.lineTo(cx, cy - 4.5f);
                        d.out.lineTo(cx - 4.5f, cy);
                        d.out.closePath();
                        d.out.fill();
                    } else {
                        d.rect(x1, d.y - rowH + 3.5f, Math.max(dayW, x2 - x1), rowH - 7, color);
                    }
                }
                d.line(MARGIN, d.y - rowH, MARGIN + full, d.y - rowH, LINE, 0.4f);
                d.y -= rowH;
            }
            todayLine(d, today, start, dayW, left, top, days);
            if (items.isEmpty()) d.text(d.regular, 9, MARGIN, d.y - 14, "내보낼 작업이 없습니다.", MUTED);
            return d.finish();
        }
    }

    private static void todayLine(Doc d, LocalDate today, LocalDate start, float dayW, float left, float top, long days) throws IOException {
        long at = ChronoUnit.DAYS.between(start, today);
        if (at < 0 || at >= days) return;
        float x = MARGIN + left + at * dayW + dayW / 2;
        d.line(x, top, x, d.y, TODAY, 0.8f);
    }

    private static Color parse(String hex) {
        try {
            return Color.decode(hex);
        } catch (RuntimeException e) {
            return new Color(0x1f, 0x5f, 0xc4);
        }
    }
}
