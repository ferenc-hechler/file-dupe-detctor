package com.filedupedetector.scan;

/** Reason a file/path could not be treated as a normal regular file for hashing purposes. */
public enum InaccessibleReason {
    CLOUD_PLACEHOLDER,
    SYMLINK_SKIPPED,
    OTHER
}
