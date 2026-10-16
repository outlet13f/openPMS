package com.openpms.cicd;

import java.util.UUID;

/** The attached repository an event belongs to: its id, its current project and its address (the link check's base for code hosts). */
record CicdRepoRef(UUID id, UUID projectId, String webUrl) {}
