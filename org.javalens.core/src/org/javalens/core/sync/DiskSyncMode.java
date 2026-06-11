package org.javalens.core.sync;

/**
 * Disk-sync integrity contract, chosen at server start via the
 * {@code JAVALENS_DISK_SYNC} environment variable.
 *
 * <ul>
 *   <li>{@link #STRICT} (default) — every query content-verifies the loaded
 *       model against disk and repairs the delta before answering. Answers
 *       are unconditionally true of disk at query time.</li>
 *   <li>{@link #MANUAL} — pre-1.5.0 behavior: the model trusts its last
 *       load and the caller drives synchronization via {@code load_project}.</li>
 * </ul>
 */
public enum DiskSyncMode {
    STRICT,
    MANUAL;

    /** Parse the environment value; anything but "manual" means STRICT. */
    public static DiskSyncMode fromEnvironment(String value) {
        return value != null && value.trim().equalsIgnoreCase("manual") ? MANUAL : STRICT;
    }

    public String toConfigValue() {
        return name().toLowerCase();
    }
}
