package com.openpms.wp;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpms.core.Snapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class WorkflowRolesTest {
    static final UUID TYPE = UUID.randomUUID(), ROLE = UUID.randomUUID(), OTHER_ROLE = UUID.randomUUID();
    static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();
    static final UUID ME = UUID.randomUUID(), SOMEONE = UUID.randomUUID();

    Snapshot snapshot() {
        var s = Mockito.mock(Snapshot.class);
        Mockito.when(s.workflows()).thenReturn(List.of(
                new Snapshot.WorkflowRow(TYPE, ROLE, A, B, "author"),
                new Snapshot.WorkflowRow(TYPE, ROLE, A, C, "always"),
                new Snapshot.WorkflowRow(TYPE, OTHER_ROLE, A, C, "always")));
        return s;
    }

    @Test void theAuthorRuleUsesTheGivenAuthor() {
        assertThat(Assembler.allowedFor(snapshot(), Set.of(ROLE), ME, TYPE, A, ME, List.of())).containsExactlyInAnyOrder(B, C);
        assertThat(Assembler.allowedFor(snapshot(), Set.of(ROLE), ME, TYPE, A, SOMEONE, List.of())).containsExactly(C);
    }

    @Test void onlyTheGivenRolesCount() {
        assertThat(Assembler.allowedFor(snapshot(), Set.of(), ME, TYPE, A, ME, List.of())).isEmpty();
    }
}
