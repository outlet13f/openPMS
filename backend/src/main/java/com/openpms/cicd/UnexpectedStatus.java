package com.openpms.cicd;

import java.io.IOException;

/** A code host answered with a non-2xx status. The message is fixed text (no URL, query or token). */
public final class UnexpectedStatus extends IOException {
    private final int status;

    public UnexpectedStatus(int status) {
        super("unexpected status " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
