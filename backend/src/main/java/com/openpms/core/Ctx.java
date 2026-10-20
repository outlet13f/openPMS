package com.openpms.core;

import com.openpms.common.ApiException;
import com.openpms.common.Dates;
import com.openpms.core.Snapshot.ProjectRow;
import com.openpms.core.Snapshot.UserRow;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One request: who is asking and the snapshot their permissions are computed from (architecture.md §3.1).
 * Invisible and missing resources are the same 404, so nothing can be enumerated.
 */
public final class Ctx {
    public static final List<String> GLOBAL_PERMISSIONS = List.of("global.create_projects", "global.manage_users", "global.manage_cicd");

    /** What each share role allows on its one work package (frontend: fake/permissions.ts SHARE_PERMISSIONS). */
    public static final Map<String, Set<String>> SHARE_PERMISSIONS = Map.of(
            "view", Set.of("wp.view"),
            "comment", Set.of("wp.view", "wp.comment", "wp.edit_own_comments"),
            "edit", Set.of("wp.view", "wp.comment", "wp.edit_own_comments", "wp.edit", "wp.attach"));

    /** Permissions that need a project module: switched-off modules take them away. */
    private static final Map<String, String> MODULE_OF = Map.ofEntries(Map.entry("board.view", "boards"), Map.entry("board.manage", "boards"),
            Map.entry("time.log", "time_tracking"), Map.entry("time.view", "time_tracking"), Map.entry("time.manage", "time_tracking"),
            Map.entry("cicd.view", "cicd"), Map.entry("cicd.link", "cicd"), Map.entry("cicd.manage", "cicd"), Map.entry("release.verify", "cicd"));

    private Snapshot snapshot;
    private final java.util.function.Supplier<Snapshot> reload;
    private final UUID userId;
    private final Map<UUID, Set<String>> permsCache = new HashMap<>();

    public Ctx(UUID userId, java.util.function.Supplier<Snapshot> reload) {
        this.userId = userId;
        this.reload = reload;
        this.snapshot = reload.get();
    }

    public Snapshot s() {
        return snapshot;
    }

    /** After changing people, projects, members, roles or settings, read them again for the response. */
    public void refresh() {
        snapshot = reload.get();
        permsCache.clear();
    }

    public UUID userId() {
        return userId;
    }

    public UserRow user() {
        return snapshot.user(userId).orElseThrow(() -> new ApiException(401, "로그인이 필요합니다."));
    }

    public boolean isAdmin() {
        return user().isAdmin();
    }

    public LocalDate today() {
        return Dates.today(user().timeZone());
    }

    /* ---------- permissions ---------- */

    public Set<String> projectPermissions(UserRow u, ProjectRow p) {
        Set<String> perms = new LinkedHashSet<>();
        if (u.isAdmin()) {
            snapshot.roles().stream().filter(r -> "project".equals(r.scope())).forEach(r -> perms.addAll(r.permissions()));
        } else {
            var roleIds = snapshot.memberRoleIds(u.id(), p.id());
            if (!roleIds.isEmpty()) {
                snapshot.roles().stream().filter(r -> roleIds.contains(r.id())).forEach(r -> perms.addAll(r.permissions()));
            } else if (p.isPublic()) {
                snapshot.roles().stream().filter(r -> "non_member".equals(r.builtin())).findFirst().ifPresent(r -> perms.addAll(r.permissions()));
            }
        }
        // someone with an item shared from this project sees the project (its name, the shared items), nothing more
        if (!perms.contains("project.view") && snapshot.shares(u.id()).projectIds().contains(p.id())) perms.add("project.view");
        if (p.archivedAt() != null) perms.removeIf(x -> !(x.endsWith(".view") || x.equals("project.view_members")));
        MODULE_OF.forEach((perm, module) -> {
            if (!p.enabledModules().contains(module)) perms.remove(perm);
        });
        return perms;
    }

    public Set<String> perms(ProjectRow p) {
        return permsCache.computeIfAbsent(p.id(), id -> projectPermissions(user(), p));
    }

    public boolean can(ProjectRow p, String perm) {
        return perms(p).contains(perm);
    }

    public boolean can(UserRow u, ProjectRow p, String perm) {
        return u.id().equals(userId) ? can(p, perm) : projectPermissions(u, p).contains(perm);
    }

    /** Permission on one work package: the project's roles plus a share of that item. */
    public boolean can(ProjectRow p, UUID wpId, String perm) {
        return can(p, perm) || shareAllows(user(), p, wpId, perm);
    }

    /** The same for someone else (who gets a notification, who can watch …). */
    public boolean can(UserRow u, ProjectRow p, UUID wpId, String perm) {
        return can(u, p, perm) || shareAllows(u, p, wpId, perm);
    }

    private boolean shareAllows(UserRow u, ProjectRow p, UUID wpId, String perm) {
        var role = snapshot.shares(u.id()).roles().get(wpId);
        if (role == null) return false;
        var allowed = SHARE_PERMISSIONS.getOrDefault(role, Set.of()).contains(perm);
        return p.archivedAt() == null ? allowed : allowed && perm.endsWith(".view");
    }

    /** My share role on an item, or null. */
    public String shareRole(UUID wpId) {
        return snapshot.shares(userId).roles().get(wpId);
    }

    public void require(ProjectRow p, UUID wpId, String perm, String message) {
        if (!can(p, wpId, perm)) throw ApiException.forbidden(message);
    }

    public void require(ProjectRow p, String perm, String message) {
        if (!can(p, perm)) throw ApiException.forbidden(message);
    }

    public void require(ProjectRow p, String perm) {
        require(p, perm, "이 작업을 할 권한이 없습니다.");
    }

    public List<String> globalPermissions() {
        if (isAdmin()) return GLOBAL_PERMISSIONS;
        return globalPermissionsOf(userId);
    }

    private List<String> globalPermissionsOf(UUID id) {
        var assigned = snapshot.globalRoleIds(id);
        return snapshot.roles().stream().filter(r -> "global".equals(r.scope()) && assigned.contains(r.id()))
                .flatMap(r -> r.permissions().stream()).distinct().toList();
    }

    public List<ProjectRow> visibleProjects(String perm) {
        return snapshot.projects().values().stream().filter(p -> can(p, perm)).toList();
    }

    public ProjectRow findProject(String idOrIdentifier) {
        return snapshot.projectByIdOrIdentifier(idOrIdentifier).filter(p -> can(p, "project.view"))
                .orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
    }

    public ProjectRow findProject(UUID id) {
        return findProject(id.toString());
    }
}
