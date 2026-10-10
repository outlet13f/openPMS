package com.openpms.board;

import static com.openpms.db.Tables.BOARDS;
import static com.openpms.db.Tables.BOARD_CARDS;
import static com.openpms.db.Tables.BOARD_COLUMNS;
import static com.openpms.db.Tables.WORK_PACKAGES;

import com.openpms.api.Dto;
import com.openpms.common.ApiException;
import com.openpms.common.In;
import com.openpms.core.Ctx;
import com.openpms.core.Current;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.BoardsRecord;
import com.openpms.db.tables.records.WorkPackagesRecord;
import com.openpms.wp.Assembler;
import com.openpms.wp.WorkPackageService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.web.bind.annotation.RequestParam;
import org.jooq.JSONB;
import com.openpms.query.QueryEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Boards (F06): columns stand for a status, person or version, and moving a card changes that attribute; or, on a
 * basic board, columns are named lists the team fills by hand (adding, ordering and removing cards changes nothing
 * on the work packages). Saved filters narrow every column.
 */
@RestController
@RequestMapping("/api/v1")
public class BoardController {
    private static final Set<String> KINDS = Set.of("basic", "status", "assignee", "version");

    private final Current current;
    private final DSLContext db;
    private final Assembler assembler;
    private final WorkPackageService workPackages;
    private final QueryEngine engine;
    private final JsonMapper json;

    public BoardController(Current current, DSLContext db, Assembler assembler, WorkPackageService workPackages, QueryEngine engine, JsonMapper json) {
        this.current = current;
        this.db = db;
        this.assembler = assembler;
        this.workPackages = workPackages;
        this.engine = engine;
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> savedFilters(BoardsRecord b) {
        var list = json.readValue(b.getFilters().data(), List.class);
        return list == null ? List.of() : (List<Map<String, Object>>) list;
    }

    private record Col(UUID id, int position, String name, UUID valueId) {}

    private List<Col> columns(UUID boardId, String kind) {
        return db.selectFrom(BOARD_COLUMNS).where(BOARD_COLUMNS.BOARD_ID.eq(boardId)).orderBy(BOARD_COLUMNS.POSITION).fetch().stream()
                .map(c -> new Col(c.getId(), c.getPosition(), c.getName(), switch (kind) {
                    case "status" -> c.getStatusId();
                    case "assignee" -> c.getUserId();
                    case "version" -> c.getVersionId();
                    default -> null;
                })).toList();
    }

    /** Cards of each column, in saved order (fractional positions), falling back to the number; only those the filters let through. */
    private Map<UUID, List<WorkPackagesRecord>> cardsByColumn(Ctx ctx, BoardsRecord b, List<Col> cols, List<QueryEngine.Filter> filters) {
        var s = ctx.s();
        var wps = db.selectFrom(WORK_PACKAGES).where(WORK_PACKAGES.PROJECT_ID.eq(b.getProjectId()), engine.where(ctx, filters)).fetch();
        var order = db.select(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.POSITION).from(BOARD_CARDS).where(BOARD_CARDS.BOARD_ID.eq(b.getId()))
                .fetchMap(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.POSITION);
        var placed = db.select(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.COLUMN_ID).from(BOARD_CARDS)
                .where(BOARD_CARDS.BOARD_ID.eq(b.getId()), BOARD_CARDS.COLUMN_ID.isNotNull()).fetchMap(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.COLUMN_ID);
        var out = new LinkedHashMap<UUID, List<WorkPackagesRecord>>();
        for (var c : cols) {
            var cards = new ArrayList<WorkPackagesRecord>();
            // a basic board's column holds the cards put there by hand
            if ("basic".equals(b.getKind())) for (var w : wps) {
                if (c.id().equals(placed.get(w.getId()))) cards.add(w);
            }
            else if (c.valueId() != null) for (var w : wps) {
                boolean in = switch (b.getKind()) {
                    case "status" -> c.valueId().equals(w.getStatusId());
                    case "assignee" -> c.valueId().equals(w.getAssigneeId()) && !s.status(w.getStatusId()).map(Snapshot.StatusRow::isClosed).orElse(false);
                    case "version" -> c.valueId().equals(w.getVersionId());
                    default -> false;
                };
                if (in) cards.add(w);
            }
            cards.sort(Comparator.comparingDouble(w -> order.containsKey(w.getId()) ? order.get(w.getId()) : (double) w.getNumber()));
            out.put(c.id(), cards);
        }
        return out;
    }

    private Dto.Board toBoard(Ctx ctx, BoardsRecord b) {
        return toBoard(ctx, b, null);
    }

    /** @param override filters asked for in the request (a look without saving), or null for the saved ones */
    private Dto.Board toBoard(Ctx ctx, BoardsRecord b, List<Map<String, Object>> override) {
        var s = ctx.s();
        var project = s.project(b.getProjectId()).orElseThrow();
        var cols = columns(b.getId(), b.getKind());
        var filters = override != null ? override : savedFilters(b);
        var cards = cardsByColumn(ctx, b, cols, QueryEngine.filters(filters));
        var all = cards.values().stream().flatMap(List::stream).toList();
        var dtos = new HashMap<UUID, Dto.WorkPackage>();
        assembler.many(ctx, all).forEach(d -> dtos.put(d.id(), d));
        var out = cols.stream().map(c -> {
            String title = c.name() == null ? "" : c.name();
            String color = null;
            if (c.valueId() != null) switch (b.getKind()) {
                case "status" -> {
                    var st = s.status(c.valueId());
                    title = st.map(Snapshot.StatusRow::name).orElse("삭제된 상태");
                    color = st.map(Snapshot.StatusRow::color).orElse(null);
                }
                case "assignee" -> title = Refs.user(s, c.valueId()).name();
                case "version" -> title = s.version(c.valueId()).map(Snapshot.VersionRow::name).orElse("");
                default -> { }
            }
            return new Dto.BoardColumn(c.id(), c.position(), title, color, c.valueId(), cards.get(c.id()).stream().map(w -> dtos.get(w.getId())).toList());
        }).toList();
        return new Dto.Board(b.getId(), b.getName(), b.getKind(), b.getIsPublic(), b.getCreatedAt(), b.getProjectId(), out, ctx.can(project, "board.manage"),
                filters);
    }

    private BoardsRecord visibleBoard(Ctx ctx, UUID id) {
        var b = db.selectFrom(BOARDS).where(BOARDS.ID.eq(id)).fetchOne();
        var p = b == null ? null : ctx.s().project(b.getProjectId()).orElse(null);
        if (b == null || p == null || !ctx.can(p, "board.view")) throw ApiException.notFound("보드를 찾을 수 없습니다.");
        return b;
    }

    @GetMapping("/projects/{projectId}/boards")
    public List<Dto.BoardSummary> list(@PathVariable String projectId) {
        var ctx = current.ctx();
        var p = ctx.findProject(projectId);
        ctx.require(p, "board.view", "보드를 볼 권한이 없습니다.");
        return db.selectFrom(BOARDS).where(BOARDS.PROJECT_ID.eq(p.id())).orderBy(BOARDS.CREATED_AT).fetch().stream()
                .map(b -> new Dto.BoardSummary(b.getId(), b.getName(), b.getKind(), b.getIsPublic(), b.getCreatedAt())).toList();
    }

    @PostMapping("/projects/{projectId}/boards")
    @Transactional
    public ResponseEntity<Dto.Board> create(@PathVariable String projectId, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var s = ctx.s();
        var p = ctx.findProject(projectId);
        ctx.require(p, "board.manage", "보드를 만들 권한이 없습니다.");
        var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
        if (name.isEmpty()) throw ApiException.field("name", "보드 이름을 입력하세요.");
        var kind = In.str(in, "kind");
        if (!KINDS.contains(kind)) throw ApiException.field("kind", "이 종류의 보드는 아직 만들 수 없습니다.");
        List<UUID> values = switch (kind) {
            case "status" -> s.statuses().stream().filter(x -> !x.isReadonly()).map(Snapshot.StatusRow::id).toList();
            case "assignee" -> s.projectUsers(p.id()).stream().limit(6).map(Snapshot.UserRow::id).toList();
            case "version" -> s.versions().stream().filter(v -> v.projectId().equals(p.id())).map(Snapshot.VersionRow::id).toList();
            default -> List.of(); // basic: starts with one empty list
        };
        var b = db.insertInto(BOARDS).set(BOARDS.PROJECT_ID, p.id()).set(BOARDS.NAME, name).set(BOARDS.KIND, kind).set(BOARDS.CREATED_BY, ctx.userId())
                .returning().fetchOne();
        if (values.isEmpty()) {
            db.insertInto(BOARD_COLUMNS).set(BOARD_COLUMNS.BOARD_ID, b.getId()).set(BOARD_COLUMNS.POSITION, 0).set(BOARD_COLUMNS.NAME, "할 일").execute();
        }
        for (int i = 0; i < values.size(); i++) {
            var col = switch (kind) {
                case "status" -> BOARD_COLUMNS.STATUS_ID;
                case "assignee" -> BOARD_COLUMNS.USER_ID;
                default -> BOARD_COLUMNS.VERSION_ID;
            };
            db.insertInto(BOARD_COLUMNS).set(BOARD_COLUMNS.BOARD_ID, b.getId()).set(BOARD_COLUMNS.POSITION, i).set(col, values.get(i)).execute();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(toBoard(ctx, b));
    }

    /** @param filters JSON filters to look through instead of the saved ones (not saved; anyone who sees the board) */
    @GetMapping("/boards/{id}")
    public Dto.Board get(@PathVariable UUID id, @RequestParam(required = false) String filters) {
        var ctx = current.ctx();
        var b = visibleBoard(ctx, id);
        if (filters == null) return toBoard(ctx, b);
        Object raw;
        try {
            raw = json.readValue(filters, List.class);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("필터를 확인하세요.", "필터 형식이 올바르지 않습니다.");
        }
        return toBoard(ctx, b, normalized(raw));
    }

    /** Filters kept in the stored shape [{field, op, values}]. */
    private static List<Map<String, Object>> normalized(Object raw) {
        return QueryEngine.filters(raw).stream()
                .map(f -> Map.<String, Object>of("field", f.field(), "op", f.op(), "values", f.values() == null ? List.of() : f.values())).toList();
    }

    @DeleteMapping("/boards/{id}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        var ctx = current.ctx();
        var b = visibleBoard(ctx, id);
        ctx.require(ctx.s().project(b.getProjectId()).orElseThrow(), "board.manage");
        b.delete();
        return ResponseEntity.noContent().build();
    }

    private BoardsRecord managedBoard(Ctx ctx, UUID id) {
        var b = visibleBoard(ctx, id);
        ctx.require(ctx.s().project(b.getProjectId()).orElseThrow(), "board.manage", "보드를 바꿀 권한이 없습니다.");
        return b;
    }

    /** Name and visibility (S15). */
    @PatchMapping("/boards/{id}")
    @Transactional
    public Dto.Board update(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var b = managedBoard(ctx, id);
        if (In.has(in, "name")) {
            var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
            if (name.isEmpty()) throw ApiException.field("name", "보드 이름을 입력하세요.");
            if (name.length() > 100) throw ApiException.field("name", "보드 이름은 100자까지 입력할 수 있습니다.");
            b.setName(name);
        }
        if (In.has(in, "isPublic")) b.setIsPublic(Boolean.TRUE.equals(In.bool(in, "isPublic")));
        if (In.has(in, "filters")) {
            var filters = normalized(in.get("filters"));
            engine.where(ctx, QueryEngine.filters(filters)); // refuses unknown fields and operators (422)
            b.setFilters(JSONB.valueOf(json.writeValueAsString(filters)));
        }
        b.store();
        return toBoard(ctx, b);
    }

    /** Adds a column at the end: a status, a project member or a version of the project, not already on the board; on a basic board, a named list. */
    @PostMapping("/boards/{id}/columns")
    @Transactional
    public Dto.Board addColumn(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var s = ctx.s();
        var b = managedBoard(ctx, id);
        if ("basic".equals(b.getKind())) {
            var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
            if (name.isEmpty() || name.length() > 60) throw ApiException.field("name", "열 이름을 1~60자로 입력하세요.");
            var cols = columns(b.getId(), b.getKind());
            if (cols.size() >= 30) throw ApiException.field("name", "열은 30개까지 만들 수 있습니다.");
            int last = cols.stream().mapToInt(Col::position).max().orElse(-1);
            db.insertInto(BOARD_COLUMNS).set(BOARD_COLUMNS.BOARD_ID, b.getId()).set(BOARD_COLUMNS.POSITION, last + 1).set(BOARD_COLUMNS.NAME, name).execute();
            return toBoard(ctx, b);
        }
        var valueId = In.uuid(in, "valueId", "valueId");
        if (valueId == null) throw ApiException.field("valueId", "추가할 열을 고르세요.");
        boolean ok = switch (b.getKind()) {
            case "status" -> s.status(valueId).isPresent();
            case "assignee" -> s.projectUsers(b.getProjectId()).stream().anyMatch(u -> u.id().equals(valueId));
            case "version" -> s.version(valueId).filter(v -> v.projectId().equals(b.getProjectId())).isPresent();
            default -> false;
        };
        if (!ok) throw ApiException.field("valueId", "이 보드에 넣을 수 없는 값입니다.");
        var cols = columns(b.getId(), b.getKind());
        if (cols.stream().anyMatch(c -> valueId.equals(c.valueId()))) throw ApiException.field("valueId", "이미 있는 열입니다.");
        if (cols.size() >= 30) throw ApiException.field("valueId", "열은 30개까지 만들 수 있습니다.");
        var field = switch (b.getKind()) {
            case "status" -> BOARD_COLUMNS.STATUS_ID;
            case "assignee" -> BOARD_COLUMNS.USER_ID;
            default -> BOARD_COLUMNS.VERSION_ID;
        };
        int last = cols.stream().mapToInt(Col::position).max().orElse(-1);
        db.insertInto(BOARD_COLUMNS).set(BOARD_COLUMNS.BOARD_ID, b.getId()).set(BOARD_COLUMNS.POSITION, last + 1).set(field, valueId).execute();
        return toBoard(ctx, b);
    }

    private BoardsRecord boardOfColumn(Ctx ctx, UUID columnId) {
        var c = db.selectFrom(BOARD_COLUMNS).where(BOARD_COLUMNS.ID.eq(columnId)).fetchOne();
        if (c == null) throw ApiException.notFound("열을 찾을 수 없습니다.");
        return managedBoard(ctx, c.getBoardId());
    }

    /** Moves a column to a place (0 = first) and/or renames a column without a value (the 할 일 column of an empty board). */
    @PatchMapping("/board-columns/{id}")
    @Transactional
    public Dto.Board updateColumn(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var b = boardOfColumn(ctx, id);
        var cols = new ArrayList<>(columns(b.getId(), b.getKind()));
        var col = cols.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        if (In.has(in, "name")) {
            if (col.valueId() != null) throw ApiException.field("name", "상태·담당자·버전 열의 이름은 그 값의 이름을 따릅니다.");
            var name = In.str(in, "name") == null ? "" : In.str(in, "name").trim();
            if (name.isEmpty() || name.length() > 60) throw ApiException.field("name", "열 이름을 1~60자로 입력하세요.");
            db.update(BOARD_COLUMNS).set(BOARD_COLUMNS.NAME, name).where(BOARD_COLUMNS.ID.eq(id)).execute();
        }
        var position = In.integer(in, "position", "position");
        if (position != null) {
            cols.remove(col);
            cols.add(Math.max(0, Math.min(position, cols.size())), col);
            for (int i = 0; i < cols.size(); i++) db.update(BOARD_COLUMNS).set(BOARD_COLUMNS.POSITION, i).where(BOARD_COLUMNS.ID.eq(cols.get(i).id())).execute();
        }
        return toBoard(ctx, b);
    }

    /** Removes a column; its work packages stay as they are. */
    @DeleteMapping("/board-columns/{id}")
    @Transactional
    public Dto.Board removeColumn(@PathVariable UUID id) {
        var ctx = current.ctx();
        var b = boardOfColumn(ctx, id);
        db.deleteFrom(BOARD_COLUMNS).where(BOARD_COLUMNS.ID.eq(id)).execute();
        return toBoard(ctx, b);
    }

    /** Move a card: change the column's attribute through the normal work package rules, then place it before another card. */
    @PostMapping("/boards/{id}/moves")
    @Transactional
    public Dto.Board move(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var b = visibleBoard(ctx, id);
        var colId = In.uuid(in, "toColumnId", "toColumnId");
        var cols = columns(b.getId(), b.getKind());
        var col = cols.stream().filter(c -> c.id().equals(colId)).findFirst().orElseThrow(() -> ApiException.notFound("컬럼을 찾을 수 없습니다."));
        var number = In.integer(in, "number", "number");
        if (number == null) throw ApiException.field("number", "옮길 작업을 고르세요.");
        var wp = workPackages.find(ctx, number).wp();
        if ("basic".equals(b.getKind())) {
            // a hand-made list: only the board changes, so it takes the right to manage the board
            ctx.require(ctx.s().project(b.getProjectId()).orElseThrow(), "board.manage", "보드를 바꿀 권한이 없습니다.");
            if (db.fetchCount(BOARD_CARDS, BOARD_CARDS.BOARD_ID.eq(b.getId()), BOARD_CARDS.WORK_PACKAGE_ID.eq(wp.getId())) == 0)
                throw ApiException.notFound("이 보드에 없는 카드입니다.");
            place(ctx, b, cols, col, wp, In.integer(in, "beforeNumber", "beforeNumber"));
            return toBoard(ctx, b);
        }
        var lockVersion = In.integer(in, "lockVersion", "lockVersion");
        Map<String, Object> patch = new HashMap<>();
        patch.put("lockVersion", lockVersion);
        if ("status".equals(b.getKind()) && col.valueId() != null && !col.valueId().equals(wp.getStatusId())) patch.put("statusId", col.valueId().toString());
        if ("assignee".equals(b.getKind()) && col.valueId() != null && !col.valueId().equals(wp.getAssigneeId())) patch.put("assigneeId", col.valueId().toString());
        if ("version".equals(b.getKind()) && !java.util.Objects.equals(col.valueId(), wp.getVersionId())) patch.put("versionId", col.valueId() == null ? null : col.valueId().toString());
        if (patch.size() > 1) workPackages.update(ctx, number, patch);
        place(ctx, b, cols, col, wp, In.integer(in, "beforeNumber", "beforeNumber"));
        return toBoard(ctx, b);
    }

    /**
     * Puts a card in a column before another card (null: at the end), at a fractional position between its neighbours.
     * On a basic board the column is stored with the card; on the others the column follows the attribute.
     */
    private void place(Ctx ctx, BoardsRecord b, List<Col> cols, Col col, WorkPackagesRecord wp, Integer before) {
        var cards = cardsByColumn(ctx, b, cols, List.of()).get(col.id()).stream().filter(w -> !w.getId().equals(wp.getId())).toList();
        var order = db.select(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.POSITION).from(BOARD_CARDS).where(BOARD_CARDS.BOARD_ID.eq(b.getId()))
                .fetchMap(BOARD_CARDS.WORK_PACKAGE_ID, BOARD_CARDS.POSITION);
        java.util.function.Function<WorkPackagesRecord, Double> pos = w -> order.containsKey(w.getId()) ? order.get(w.getId()) : (double) w.getNumber();
        int idx = before == null ? cards.size() : indexOf(cards, before);
        Double prev = idx > 0 ? pos.apply(cards.get(idx - 1)) : null;
        Double next = idx >= 0 && idx < cards.size() ? pos.apply(cards.get(idx)) : null;
        double p = prev == null && next == null ? 1 : prev == null ? next - 1 : next == null ? prev + 1 : (prev + next) / 2;
        UUID columnId = "basic".equals(b.getKind()) ? col.id() : null;
        db.insertInto(BOARD_CARDS).set(BOARD_CARDS.BOARD_ID, b.getId()).set(BOARD_CARDS.WORK_PACKAGE_ID, wp.getId()).set(BOARD_CARDS.POSITION, p)
                .set(BOARD_CARDS.COLUMN_ID, columnId)
                .onConflict(BOARD_CARDS.BOARD_ID, BOARD_CARDS.WORK_PACKAGE_ID).doUpdate().set(BOARD_CARDS.POSITION, p).set(BOARD_CARDS.COLUMN_ID, columnId).execute();
    }

    private Col column(BoardsRecord b, Object raw) {
        var colId = In.uuidOrNull(raw == null ? null : String.valueOf(raw));
        return columns(b.getId(), b.getKind()).stream().filter(c -> c.id().equals(colId)).findFirst().orElseThrow(() -> ApiException.notFound("컬럼을 찾을 수 없습니다."));
    }

    /** Basic board: put an existing work package of the project in a list (at the end), or move it there if it is on another list. */
    @PostMapping("/boards/{id}/cards")
    @Transactional
    public Dto.Board addCard(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var b = managedBoard(ctx, id);
        if (!"basic".equals(b.getKind())) throw ApiException.unprocessable("기본 보드에서만 작업을 직접 넣을 수 있습니다.", null);
        var col = column(b, in.get("columnId"));
        var number = In.integer(in, "number", "number");
        if (number == null) throw ApiException.field("number", "넣을 작업을 고르세요.");
        var wp = workPackages.find(ctx, number).wp();
        if (!wp.getProjectId().equals(b.getProjectId())) throw ApiException.field("number", "이 프로젝트의 작업만 넣을 수 있습니다.");
        place(ctx, b, columns(b.getId(), b.getKind()), col, wp, null);
        return toBoard(ctx, b);
    }

    /** Basic board: take a card off the board; the work package stays as it is. */
    @DeleteMapping("/boards/{id}/cards/{number}")
    @Transactional
    public Dto.Board removeCard(@PathVariable UUID id, @PathVariable long number) {
        var ctx = current.ctx();
        var b = managedBoard(ctx, id);
        if (!"basic".equals(b.getKind())) throw ApiException.unprocessable("기본 보드에서만 카드를 뺄 수 있습니다.", null);
        var wp = workPackages.find(ctx, number).wp();
        db.deleteFrom(BOARD_CARDS).where(BOARD_CARDS.BOARD_ID.eq(b.getId()), BOARD_CARDS.WORK_PACKAGE_ID.eq(wp.getId())).execute();
        return toBoard(ctx, b);
    }

    /**
     * A new work package made from a column: it gets the column's status, assignee or version (a basic board puts it in
     * the list), and the project's default type unless one is given. The normal create rules and permissions apply.
     */
    @PostMapping("/boards/{id}/cards/new")
    @Transactional
    public ResponseEntity<Dto.BoardCardCreated> createCard(@PathVariable UUID id, @RequestBody Map<String, Object> in) {
        var ctx = current.ctx();
        var s = ctx.s();
        var b = visibleBoard(ctx, id);
        var project = s.project(b.getProjectId()).orElseThrow();
        var cols = columns(b.getId(), b.getKind());
        var col = column(b, in.get("columnId"));
        if ("basic".equals(b.getKind())) ctx.require(project, "board.manage", "보드를 바꿀 권한이 없습니다.");
        Map<String, Object> input = new HashMap<>();
        input.put("subject", In.str(in, "subject"));
        var typeId = In.str(in, "typeId");
        if (typeId == null) {
            // the default type when the project uses it, else the project's first type
            typeId = s.types().stream().filter(t -> project.typeIds().contains(t.id()))
                    .sorted(Comparator.comparing((Snapshot.TypeRow t) -> !t.isDefault()).thenComparingInt(Snapshot.TypeRow::position))
                    .map(t -> t.id().toString()).findFirst().orElse(null);
        }
        input.put("typeId", typeId);
        if (col.valueId() != null) switch (b.getKind()) {
            case "status" -> input.put("statusId", col.valueId().toString());
            case "assignee" -> input.put("assigneeId", col.valueId().toString());
            case "version" -> input.put("versionId", col.valueId().toString());
            default -> { }
        }
        var created = workPackages.create(ctx, project.id().toString(), input);
        var wp = workPackages.find(ctx, created.number()).wp();
        place(ctx, b, cols, col, wp, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(new Dto.BoardCardCreated(toBoard(ctx, b), created));
    }

    private static int indexOf(List<WorkPackagesRecord> cards, int number) {
        for (int i = 0; i < cards.size(); i++) if (cards.get(i).getNumber() == number) return i;
        return cards.size();
    }
}
