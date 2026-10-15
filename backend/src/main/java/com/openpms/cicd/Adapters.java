package com.openpms.cicd;

import java.util.List;
import org.springframework.stereotype.Component;

/** The adapter for a connection's kind. */
@Component
public class Adapters {
    private final List<CicdAdapter> all;

    public Adapters(GitLabAdapter gitlab, GitHubAdapter github) {
        this.all = List.of(gitlab, github);
    }

    /** @throws IllegalArgumentException for a kind we do not know */
    public CicdAdapter of(String kind) {
        return all.stream().filter(a -> a.kind().equals(kind)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown kind"));
    }
}
