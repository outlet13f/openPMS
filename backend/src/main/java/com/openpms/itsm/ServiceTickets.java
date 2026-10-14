package com.openpms.itsm;

import static com.openpms.db.Tables.SERVICE_TICKETS;

import com.openpms.api.Dto;
import com.openpms.core.Refs;
import com.openpms.core.Snapshot;
import com.openpms.db.tables.records.ServiceTicketsRecord;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/** service_tickets, read in bulk for lists (like wp/Assignees). */
@Component
public class ServiceTickets {
    private final DSLContext db;

    public ServiceTickets(DSLContext db) {
        this.db = db;
    }

    public Map<UUID, ServiceTicketsRecord> of(Collection<UUID> ids) {
        var out = new HashMap<UUID, ServiceTicketsRecord>();
        if (ids.isEmpty()) return out;
        db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.in(ids)).fetch().forEach(t -> out.put(t.getWorkPackageId(), t));
        return out;
    }

    public Optional<ServiceTicketsRecord> of(UUID id) {
        return Optional.ofNullable(db.selectFrom(SERVICE_TICKETS).where(SERVICE_TICKETS.WORK_PACKAGE_ID.eq(id)).fetchOne());
    }

    public static Dto.Sla sla(ServiceTicketsRecord t) {
        return new Dto.Sla(t.getFirstResponseDueAt(), t.getFirstRespondedAt(), t.getResolveDueAt(), t.getResolvedAt(), t.getPausedSince() != null,
                t.getPausedTotalSeconds(), t.getResponseBreached(), t.getResolveBreached());
    }

    public static Dto.ServiceTicket dto(Snapshot s, ServiceTicketsRecord t) {
        if (t == null) return null;
        var group = t.getAssignmentGroupId() == null ? null
                : Optional.ofNullable(s.groups().get(t.getAssignmentGroupId())).map(g -> new Dto.IdName(g.id(), g.name())).orElse(null);
        return new Dto.ServiceTicket(Refs.user(s, t.getRequesterId()), t.getChannel(), t.getImpact(), t.getUrgency(), group, sla(t));
    }
}
