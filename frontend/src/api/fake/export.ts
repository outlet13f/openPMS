// S12 내보내기 in the fake data layer: CSV and a minimal XLSX (one sheet, inline strings, stored zip), from the
// same query engine as the list. backend: export/ExportController.java writes the same columns and headers.
import type { ColumnKey, ExportInput, WorkPackage } from "../types";
import { EXPORT_LIMIT } from "../types";
import { ApiError } from "../errors";
import { db, type DbUser } from "./db";
import { can, findProject } from "./permissions";
import { runQuery } from "./query";

const HEADERS: Record<string, string> = {
  number: "번호", subject: "제목", type: "유형", status: "상태", priority: "우선순위", assignee: "담당자", responsible: "책임자",
  author: "작성자", version: "버전", startDate: "시작일", dueDate: "완료일", duration: "기간(일)", doneRatio: "완료 %", updatedAt: "최근 수정",
  project: "프로젝트",
};

export function header(column: ColumnKey): string {
  if (column.startsWith("cf:")) return db.customFields.find((f) => `cf:${f.id}` === column)?.name ?? column;
  return HEADERS[column] ?? column;
}

const plain = (html: string) => html.replace(/<[^>]*>/g, " ").replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&").replace(/\s+/g, " ").trim();

/** "yyyy-MM-dd HH:mm" in the person's zone. */
function localDateTime(iso: string, zone: string) {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: zone, year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date(iso));
  const get = (t: string) => parts.find((p) => p.type === t)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")} ${get("hour")}:${get("minute")}`;
}

/** One cell: numbers stay numbers (Excel can sum them), everything else is text, empty is null. */
export function cell(wp: WorkPackage, column: ColumnKey, zone: string): string | number | null {
  switch (column) {
    case "number": return wp.number;
    case "subject": return wp.subject;
    case "type": return wp.type.name;
    case "status": return wp.status.name;
    case "priority": return wp.priority.name;
    case "assignee": return wp.assignee?.name ?? null;
    case "responsible": return wp.responsible?.name ?? null;
    case "author": return wp.author.name;
    case "version": return wp.version?.name ?? null;
    case "startDate": return wp.startDate;
    case "dueDate": return wp.dueDate;
    case "duration": return wp.duration;
    case "doneRatio": return wp.doneRatio;
    case "updatedAt": return localDateTime(wp.updatedAt, zone);
    case "project": return wp.project.name;
    default: {
      const id = column.startsWith("cf:") ? column.slice(3) : null;
      return (id && wp.customValues[id]?.text) || null;
    }
  }
}

function csv(rows: (string | number | null)[][]): Blob {
  const esc = (v: string | number | null) => {
    // text that a spreadsheet would run as a formula gets a leading apostrophe (CSV injection)
    const s = v === null ? "" : typeof v === "string" && /^[=+\-@\t\r]/.test(v) ? `'${v}` : String(v);
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
  };
  // BOM so Excel opens UTF-8 (Korean) correctly
  return new Blob(["﻿" + rows.map((r) => r.map(esc).join(",")).join("\r\n") + "\r\n"], { type: "text/csv;charset=utf-8" });
}

/* ---------- a minimal XLSX: stored zip of five parts ---------- */

const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();
const crc32 = (b: Uint8Array) => {
  let c = 0xffffffff;
  for (const x of b) c = CRC[(c ^ x) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
};

function zip(files: [string, string][]): Blob {
  const enc = new TextEncoder();
  const chunks: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset = 0;
  for (const [name, text] of files) {
    const data = enc.encode(text);
    const nameBytes = enc.encode(name);
    const crc = crc32(data);
    const local = new DataView(new ArrayBuffer(30));
    local.setUint32(0, 0x04034b50, true);
    local.setUint16(4, 20, true);
    local.setUint16(6, 0x0800, true); // UTF-8 names
    local.setUint16(8, 0, true); // stored
    local.setUint32(14, crc, true);
    local.setUint32(18, data.length, true);
    local.setUint32(22, data.length, true);
    local.setUint16(26, nameBytes.length, true);
    chunks.push(new Uint8Array(local.buffer), nameBytes, data);
    const dir = new DataView(new ArrayBuffer(46));
    dir.setUint32(0, 0x02014b50, true);
    dir.setUint16(4, 20, true);
    dir.setUint16(6, 20, true);
    dir.setUint16(8, 0x0800, true);
    dir.setUint32(16, crc, true);
    dir.setUint32(20, data.length, true);
    dir.setUint32(24, data.length, true);
    dir.setUint16(28, nameBytes.length, true);
    dir.setUint32(42, offset, true);
    central.push(new Uint8Array(dir.buffer), nameBytes);
    offset += 30 + nameBytes.length + data.length;
  }
  const size = central.reduce((n, c) => n + c.length, 0);
  const end = new DataView(new ArrayBuffer(22));
  end.setUint32(0, 0x06054b50, true);
  end.setUint16(8, files.length, true);
  end.setUint16(10, files.length, true);
  end.setUint32(12, size, true);
  end.setUint32(16, offset, true);
  return new Blob([...chunks, ...central, new Uint8Array(end.buffer)] as BlobPart[], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" });
}

const xml = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "");
const colName = (i: number) => {
  let s = "";
  for (let n = i + 1; n > 0; n = Math.floor((n - 1) / 26)) s = String.fromCharCode(65 + ((n - 1) % 26)) + s;
  return s;
};

function xlsx(rows: (string | number | null)[][]): Blob {
  const sheetRows = rows
    .map((r, ri) => {
      const cells = r
        .map((v, ci) => {
          const ref = `${colName(ci)}${ri + 1}`;
          if (v === null) return "";
          if (typeof v === "number") return `<c r="${ref}"><v>${v}</v></c>`;
          return `<c r="${ref}" t="inlineStr"${ri === 0 ? ' s="1"' : ""}><is><t xml:space="preserve">${xml(v)}</t></is></c>`;
        })
        .join("");
      return `<row r="${ri + 1}">${cells}</row>`;
    })
    .join("");
  const head = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>';
  const ns = 'xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"';
  return zip([
    ["[Content_Types].xml", `${head}<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>`],
    ["_rels/.rels", `${head}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>`],
    ["xl/workbook.xml", `${head}<workbook ${ns} xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="작업" sheetId="1" r:id="rId1"/></sheets></workbook>`],
    ["xl/_rels/workbook.xml.rels", `${head}<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>`],
    ["xl/styles.xml", `${head}<styleSheet ${ns}><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0"/><xf fontId="1" applyFont="1"/></cellXfs></styleSheet>`],
    ["xl/worksheets/sheet1.xml", `${head}<worksheet ${ns}><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" state="frozen"/></sheetView></sheetViews><sheetData>${sheetRows}</sheetData></worksheet>`],
  ]);
}

const today = (zone: string) => new Intl.DateTimeFormat("en-CA", { timeZone: zone }).format(new Date()).replace(/-/g, "");

export function exportWorkPackages(user: DbUser, input: ExportInput): { blob: Blob; fileName: string } {
  if (input.projectId) {
    const p = findProject(user, input.projectId);
    if (!can(user, p, "wp.export")) throw new ApiError(403, "내보낼 권한이 없습니다.");
  }
  if (!["csv", "xlsx"].includes(input.format)) throw new ApiError(422, "형식을 고르세요.", "CSV 또는 Excel만 내보낼 수 있습니다.");
  if (!input.columns.length) throw new ApiError(422, "열을 하나 이상 고르세요.");
  const first = runQuery(user, { ...input.spec, hierarchy: false }, { projectId: input.projectId, page: 1, pageSize: 1000 });
  if (first.total > EXPORT_LIMIT) throw new ApiError(422, "내보낼 작업이 너무 많습니다.", `한 번에 ${EXPORT_LIMIT.toLocaleString()}건까지 내보낼 수 있습니다. 필터로 나눠 내보내세요.`);
  const wps = [...first.rows];
  for (let page = 2; wps.length < first.total; page++) wps.push(...runQuery(user, { ...input.spec, hierarchy: false }, { projectId: input.projectId, page, pageSize: 1000 }).rows);
  // across projects, only items of projects where I may export
  const allowed = wps.map((r) => r.wp).filter((wp) => {
    const p = db.projects.find((x) => x.id === wp.project.id);
    return !!p && can(user, p, "wp.export");
  });
  const rows: (string | number | null)[][] = [
    [...input.columns.map(header), ...(input.includeDescription ? ["설명"] : [])],
    ...allowed.map((wp) => [...input.columns.map((c) => cell(wp, c, user.timeZone)), ...(input.includeDescription ? [plain(wp.descriptionHtml) || null] : [])]),
  ];
  const safe = (input.title || "작업").replace(/[\\/:*?"<>|]+/g, " ").trim().slice(0, 80) || "작업";
  return { blob: input.format === "csv" ? csv(rows) : xlsx(rows), fileName: `${safe}-${today(user.timeZone)}.${input.format}` };
}
