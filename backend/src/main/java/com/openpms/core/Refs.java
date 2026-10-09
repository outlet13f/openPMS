package com.openpms.core;

import com.openpms.api.Dto;
import com.openpms.core.Snapshot.ProjectRow;
import java.util.UUID;

/** Small DTO references built from the snapshot (dto.ts userRef, typeRef, statusRef …). */
public final class Refs {
    private Refs() {}

    public static Dto.UserRef user(Snapshot s, UUID id) {
        if (id == null) return null;
        return s.user(id).map(u -> new Dto.UserRef(u.id(), u.displayName())).orElse(new Dto.UserRef(id, "알 수 없는 사용자"));
    }

    public static Dto.ProjectRef project(ProjectRow p) {
        return new Dto.ProjectRef(p.id(), p.identifier(), p.name());
    }

    public static Dto.ProjectRef project(Snapshot s, UUID id) {
        return s.project(id).map(Refs::project).orElse(null);
    }

    public static Dto.TypeRef type(Snapshot s, UUID id) {
        return s.type(id).map(t -> new Dto.TypeRef(t.id(), t.name(), t.color(), t.isMilestone())).orElse(null);
    }

    public static Dto.StatusRef status(Snapshot s, UUID id) {
        return s.status(id).map(Refs::status).orElse(null);
    }

    public static Dto.StatusRef status(Snapshot.StatusRow x) {
        return new Dto.StatusRef(x.id(), x.name(), x.color(), x.isClosed(), x.isReadonly());
    }

    public static Dto.PriorityRef priority(Snapshot s, UUID id) {
        return s.priority(id).map(p -> new Dto.PriorityRef(p.id(), p.name(), p.color())).orElse(null);
    }

    public static Dto.IdName version(Snapshot s, UUID id) {
        return s.version(id).map(v -> new Dto.IdName(v.id(), v.name())).orElse(null);
    }

    public static Dto.IdName category(Snapshot s, UUID id) {
        return s.category(id).map(c -> new Dto.IdName(c.id(), c.name())).orElse(null);
    }
}
