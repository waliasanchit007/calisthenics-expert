package com.calisthenics.diagnostic.preview

import java.util.concurrent.atomic.AtomicBoolean

/** A camera stop fences new work; only its quiescence callback permits native disposal. */
internal fun releaseViewerResources(
    stopSource: (((() -> Unit)) -> Unit)?,
    dispose: () -> Unit,
    shutdown: () -> Unit,
) {
    val released = AtomicBoolean(false)
    val release = {
        if (released.compareAndSet(false, true)) {
            try { dispose() } finally { shutdown() }
        }
    }
    if (stopSource == null) release() else stopSource(release)
}
