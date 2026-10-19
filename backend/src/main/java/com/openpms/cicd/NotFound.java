package com.openpms.cicd;

import java.io.IOException;

/**
 * The ancestor check was answered 404 (GitHub also 422): the commit is gone (a force push, no common ancestor) or the token can
 * no longer read the repository. The host does not say which, so the caller asks for the repository itself (CicdFirstDeploy).
 * The message is fixed text like UnexpectedStatus's (no URL, query or token).
 */
public final class NotFound extends IOException {
    private final int status;

    public NotFound(int status) {
        super("unexpected status " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
