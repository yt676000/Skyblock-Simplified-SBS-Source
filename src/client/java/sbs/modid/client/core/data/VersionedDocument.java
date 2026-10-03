/*
 * SPDX-License-Identifier: GPL-3.0-or-later WITH additional permission
 * Copyright (c) 2026 SkyBlock Simplified GbR
 *
 * This file is part of SkyBlock Simplified. See the LICENSE file in the
 * project root for license terms and the Minecraft linking exception.
 */

package sbs.modid.client.core.data;

/**
 * A data document that ships in the jar, caches on disk and updates from the backend - the shape
 * {@link VersionedDataStore} needs to compare two copies of the same dataset and pick the better one.
 *
 * <p>The two numbers do different jobs and must not be conflated:
 * <ul>
 *   <li>{@link #schemaVersion()} – <b>can this build read the file at all?</b> A document from the
 *       future is refused outright rather than parsed into half-populated objects.</li>
 *   <li>{@link #dataVersion()} – <b>which copy is newer?</b> Compared only between documents that
 *       passed the schema gate.</li>
 * </ul>
 */
public interface VersionedDocument {

    /** Format version. A document whose schema this build does not support is discarded. */
    int schemaVersion();

    /** Monotonic content version. Higher wins between two readable copies of the same dataset. */
    int dataVersion();

    /**
     * Whether the parsed content is usable at all, beyond the version numbers.
     *
     * <p>Partial coverage is <b>not</b> a failure - a pad file with three islands in it is a normal
     * first pass, not a broken download. This only rejects a document that carries nothing.
     */
    boolean valid();
}
