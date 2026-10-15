package com.openpms.itsm;

import java.util.Set;
import java.util.UUID;

/** Rows V20__service_desk.sql seeds with fixed ids (backend/scripts/ids.py). */
public final class ItsmIds {
    public static final UUID TYPE_INCIDENT = UUID.fromString("2dcd4691-d4a9-5bf0-8908-e4740b42616e");
    public static final UUID TYPE_SERVICE_REQUEST = UUID.fromString("a3af2ccf-05c6-5b1c-84bc-4d815791e4eb");
    public static final Set<UUID> TICKET_TYPES = Set.of(TYPE_INCIDENT, TYPE_SERVICE_REQUEST);

    public static final UUID STATUS_RECEIVED = UUID.fromString("198d200e-a1ba-5692-ae3c-6ba0bc4319ad");
    public static final UUID STATUS_HANDLING = UUID.fromString("d34fe673-69b6-56f9-85fd-2cb8198cfba5");
    public static final UUID STATUS_WAITING_REQUESTER = UUID.fromString("b42e3256-ffde-5d57-a323-43c8be0d70bd");
    public static final UUID STATUS_RESOLVED = UUID.fromString("8201e445-a46a-5135-a972-3f83ee10c3ba");
    public static final UUID STATUS_CLOSED = UUID.fromString("69baa129-0283-56e0-be38-b73ce5db2b64");
    public static final UUID STATUS_CANCELLED = UUID.fromString("8ab8a2d1-0d29-5b49-a263-6284c74a7680");

    /** Seeded by V21__service_catalog.sql. */
    public static final UUID STATUS_AWAITING_APPROVAL = UUID.fromString("3705cad8-6d55-5d45-b841-37385b44f605");
    public static final UUID STATUS_REJECTED = UUID.fromString("4be1e617-0250-552c-9d28-4bde834daa49");

    public static final UUID ROLE_REQUESTER = UUID.fromString("a9137727-cb80-51db-98d8-8a9a04f84802");

    public static final String MODULE = "service_desk";

    private ItsmIds() {}
}
