"""Connected ITSM roadmap: dependency diagram (SVG) and hand-off table, shared by every HTML page."""
import html

PHASES = [
    (1, "장애·서비스 요청", "SLA, 포털, 처리 그룹", "2026-10-14-itsm-design"),
    (2, "서비스 카탈로그", "요청 양식, 승인", "2026-10-14-itsm-phase2-catalog-design"),
    (3, "문제 관리", "근본 원인, 알려진 오류", "2026-10-14-itsm-phase3-problem-design"),
    (4, "CMDB", "구성 항목, 영향 분석", "2026-10-14-itsm-phase4-cmdb-design"),
    (5, "변경 관리", "CAB 승인, 실행 계획서", "2026-10-14-itsm-phase5-change-design"),
    (6, "지식 베이스", "포털 검색, 문서 추천", "2026-10-14-itsm-phase6-knowledge-design"),
]

# phase -> (receives, hands off)
HANDOFF = {
    1: ("기존 작업 패키지·워크플로·저널·알림·웹훅",
        "서비스 데스크 프로젝트, `service_tickets`, SLA 시계, 포털, 처리 그룹 → 2~6단계 모두"),
    2: ("1: 포털, SLA 멈춤 상태, 처리 그룹",
        "승인 엔진 `ticket_approvals`, 포털 내 승인함 → 5(CAB 승인)"),
    3: ("1: 장애(`service_tickets`)",
        "알려진 오류·임시 해결책 → 6(문서로 만들기). 장애·문제에서 변경 만들기 → 5"),
    4: ("1: 티켓(CI 연결)",
        "CI와 `work_package_cis` → 5(충돌 막기, CMDB 반영). 업무 서비스 CI → 6(문서 분류), 1(포털 서비스 고르기)"),
    5: ("2: 승인 엔진, 4: CI, 1·3: 장애·문제",
        "CI 변경 반영 → 4(CMDB 갱신). 실패한 변경의 장애 만들기 제안 → 1"),
    6: ("1: 포털, 3: 알려진 오류, 4: 업무 서비스(선택)",
        "포털 자가 해결 → 1(접수 감소), 답변에 문서 넣기 → 1·3"),
}

# node boxes: phase -> (x, y)
W, H = 210, 66
POS = {1: (16, 147), 2: (330, 22), 4: (330, 147), 3: (330, 272), 5: (644, 84), 6: (644, 210)}

# (from, to, label, dashed, y offset at source, y offset at target, label dy)
EDGES = [
    (1, 2, "", False, 33, 33, 0), (1, 4, "", False, 33, 33, 0), (1, 3, "", False, 33, 33, 0),
    (2, 5, "승인 엔진", False, 33, 20, -6),
    (4, 5, "CI · 충돌 확인", False, 14, 34, -6),
    (5, 4, "CMDB 반영", True, 54, 34, 14),
    (4, 6, "업무 서비스", False, 54, 24, -6),
    (3, 6, "알려진 오류", False, 33, 46, -6),
]


def _md_inline(text):
    out, code = [], False
    for part in text.split("`"):
        out.append(f"<code>{html.escape(part)}</code>" if code else html.escape(part))
        code = not code
    return "".join(out)


def diagram(current=0):
    """SVG dependency diagram. current=0 shows everything at full strength."""
    def dim(*phases):
        return current and current not in phases

    g = []
    # edges first so boxes sit on top
    for a, b, label, dashed, oa, ob, ldy in EDGES:
        (ax, ay), (bx, by) = POS[a], POS[b]
        if ax < bx:
            x1, x2, tip = ax + W, bx, -4
        else:
            x1, x2, tip = ax, bx + W, 4
        y1, y2 = ay + oa, by + ob
        mx = (x1 + x2) / 2
        path = f"M{x1},{y1} C{mx},{y1} {mx},{y2} {x2 + tip},{y2}"
        lx, ly = mx, (y1 + y2) / 2 + ldy
        op = ' opacity=".25"' if dim(a, b) else ""
        dash = ' stroke-dasharray="5 4"' if dashed else ""
        g.append(f'<path d="{path}" fill="none" stroke="#625e69" stroke-width="1.6"{dash} marker-end="url(#rm-arr)"{op}/>')
        if label:
            g.append(f'<text x="{lx}" y="{ly}" text-anchor="middle" font-size="11.5" fill="#625e69"'
                     f' paint-order="stroke" stroke="#fff" stroke-width="4"{op}>{html.escape(label)}</text>')
    # label for the 1 -> 2/3/4 fan
    op = ' opacity=".25"' if current and current not in (1, 2, 3, 4) else ""
    g.append(f'<text x="121" y="139" text-anchor="middle" font-size="11.5" fill="#625e69" paint-order="stroke"'
             f' stroke="#fff" stroke-width="4"{op}>티켓 · SLA · 포털 → 2~6단계</text>')
    # nodes
    for no, name, sub, slug in PHASES:
        x, y = POS[no]
        now = no == current
        fill, stroke, sw = ("#f5ecf6", "#8b2f8f", 2.2) if now else ("#ffffff", "#c9c4ce", 1.2)
        op = ' opacity=".35"' if current and not now and not any(
            (a == current and b == no) or (b == current and a == no) for a, b, *_ in EDGES) else ""
        badge = "이 문서" if now else f"구현 {no}번째"
        inner = (f'<rect x="{x}" y="{y}" width="{W}" height="{H}" rx="9" fill="{fill}" stroke="{stroke}" stroke-width="{sw}"/>'
                 f'<circle cx="{x + 22}" cy="{y + 24}" r="13" fill="{"#8b2f8f" if now else "#1e1c22"}"/>'
                 f'<text x="{x + 22}" y="{y + 28.5}" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">{no}</text>'
                 f'<text x="{x + 42}" y="{y + 29}" font-size="14" font-weight="700" fill="#1e1c22">{html.escape(name)}</text>'
                 f'<text x="{x + 14}" y="{y + 53}" font-size="12" fill="#625e69">{html.escape(sub)}</text>'
                 f'<text x="{x + W - 10}" y="{y + 16}" text-anchor="end" font-size="10.5" fill="{"#8b2f8f" if now else "#8a8590"}">{badge}</text>')
        if now:
            g.append(f"<g{op}>{inner}</g>")
        else:
            g.append(f'<a href="{slug}.html"><g{op}>{inner}</g></a>')
    desc = ("1단계가 기반이고 2·3·4단계는 1단계 위에 만든다. 5단계 변경은 2단계 승인 엔진과 4단계 CI를, "
            "6단계 지식 베이스는 3단계 알려진 오류와 4단계 업무 서비스를 쓴다. 5단계는 CI 변경을 4단계 CMDB에 반영한다.")
    return (f'<svg class="rm" viewBox="0 0 870 370" width="100%" role="img" aria-labelledby="rm-t rm-d">'
            f'<title id="rm-t">ITSM 단계 연결도</title><desc id="rm-d">{desc}</desc>'
            '<defs><marker id="rm-arr" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto">'
            '<path d="M0,0 L10,5 L0,10 z" fill="#625e69"/></marker></defs>'
            f'<g font-family="inherit">{"".join(g)}</g></svg>'
            '<p class="rm-legend">실선: 앞 단계의 결과를 쓴다 · 점선: 뒤 단계가 앞 단계 데이터를 갱신한다 · 숫자: 구현 순서 · 상자를 누르면 그 설계서로 간다</p>')


def handoff_table(current=0):
    rows = []
    for no, name, _, slug in PHASES:
        recv, give = HANDOFF[no]
        cls = ' class="current"' if no == current else ""
        title = html.escape(name) if no == current else f'<a href="{slug}.html">{html.escape(name)}</a>'
        rows.append(f"<tr{cls}><td>{no}</td><td>{title}</td><td>{_md_inline(recv)}</td><td>{_md_inline(give)}</td></tr>")
    return ('<table class="handoff"><thead><tr><th style="width:44px">단계</th><th style="width:130px">이름</th>'
            '<th>앞 단계에서 받는 것</th><th>뒤 단계로 넘겨주는 것</th></tr></thead><tbody>' + "".join(rows) + "</tbody></table>")


def handoff_callouts(current):
    recv, give = HANDOFF[current]
    return (f'<div class="grid2"><div class="card"><h4>받는 것</h4><p>{_md_inline(recv)}</p></div>'
            f'<div class="card"><h4>넘겨주는 것</h4><p>{_md_inline(give)}</p></div></div>')


def handoff_markdown():
    lines = ["| 단계 | 앞 단계에서 받는 것 | 뒤 단계로 넘겨주는 것 |", "| --- | --- | --- |"]
    for no, name, _, _ in PHASES:
        recv, give = HANDOFF[no]
        lines.append(f"| {no} {name} | {recv} | {give} |")
    return "\n".join(lines)


EXTRA_CSS = """
  svg.rm { display: block; max-width: 870px; margin: 8px auto 0; }
  svg.rm a:hover rect, svg.rm a:focus rect { stroke: #8b2f8f; stroke-width: 2; }
  .rm-legend { text-align: center; color: var(--muted); font-size: 12.5px; margin: 4px 0 12px; }
  table.handoff td:first-child { font-weight: 700; color: var(--accent); }
  .card p { margin: 0; font-size: 13.5px; }
"""
