package io.webagent4j.action;

/** Explicit behavior when a download destination already exists. */
public enum DownloadCollisionPolicy {
    FAIL,
    RENAME,
    REPLACE
}
