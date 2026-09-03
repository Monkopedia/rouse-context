package com.rousecontext.integrations.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.annotation.VisibleForTesting
import com.rousecontext.notifications.FieldEncryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Captures device notifications and persists them to Room for MCP history queries.
 *
 * Requires the user to explicitly grant notification access in
 * Settings > Apps > Special access > Notification access.
 *
 * Excludes notifications from our own package (com.rousecontext.*) to avoid
 * recursion with audit/tunnel notifications.
 */
class NotificationCaptureService : NotificationListenerService() {

    /**
     * Scope every capture coroutine runs in.
     *
     * Not private only so tests can launch into it directly to build coroutine
     * arrangements the callbacks alone cannot produce — see [joinCaptureWork].
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao: NotificationDao by inject()

    private val encryptor: FieldEncryptor by inject()

    override fun onListenerConnected() {
        instance = this
        seedActiveNotifications()
    }

    /**
     * Captures all currently active notifications into Room when the listener
     * first connects. Without this, notifications posted before the service
     * started would never appear in history queries.
     */
    private fun seedActiveNotifications() {
        serviceScope.launch {
            try {
                val active = activeNotifications ?: return@launch
                val candidates = active.filter { !isOwnPackage(it.packageName) }
                for (sbn in candidates) {
                    val existing = dao.findByPackageAndTime(sbn.packageName, sbn.postTime)
                    if (existing != null) continue

                    val extras = sbn.notification.extras
                    val record = NotificationRecord(
                        packageName = sbn.packageName,
                        title = encryptor.encrypt(
                            extras.getCharSequence("android.title")?.toString()
                        ),
                        text = encryptor.encrypt(
                            extras.getCharSequence("android.text")?.toString()
                        ),
                        postedAt = sbn.postTime,
                        category = sbn.notification.category,
                        ongoing = sbn.isOngoing
                    )
                    dao.insert(record)
                }
            } catch (_: Exception) {
                // Best-effort seeding - don't crash the listener service
            }
        }
    }

    override fun onListenerDisconnected() {
        if (instance === this) {
            instance = null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isOwnPackage(sbn.packageName)) return

        val extras = sbn.notification.extras
        val record = NotificationRecord(
            packageName = sbn.packageName,
            title = encryptor.encrypt(extras.getCharSequence("android.title")?.toString()),
            text = encryptor.encrypt(extras.getCharSequence("android.text")?.toString()),
            postedAt = sbn.postTime,
            category = sbn.notification.category,
            ongoing = sbn.isOngoing
        )

        serviceScope.launch {
            try {
                dao.insert(record)
            } catch (_: Exception) {
                // Best-effort persistence - don't crash the listener service
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isOwnPackage(sbn.packageName)) return

        val packageName = sbn.packageName
        val postedAt = sbn.postTime
        serviceScope.launch {
            try {
                val existing = dao.findByPackageAndTime(packageName, postedAt)
                if (existing != null) {
                    dao.markRemoved(existing.id, System.currentTimeMillis())
                }
            } catch (_: Exception) {
                // Best-effort persistence
            }
        }
    }

    /**
     * Drains [serviceScope]: returns once the scope holds no live coroutine.
     *
     * [kotlinx.coroutines.launch] registers its child job with the scope
     * *synchronously*, before the launching call returns. So a caller on the
     * thread that just invoked [onNotificationPosted], [onNotificationRemoved]
     * or [onListenerConnected] sees any coroutine those callbacks started, and
     * joining it establishes a happens-after point: when this returns, that
     * work has run to completion — or was never started at all.
     *
     * Tests that assert *nothing* was persisted need exactly that. There is no
     * signal to await on a write that by design never happens, and a fixed
     * sleep would let an unfinished write pass as an empty table.
     *
     * The children are re-read on every pass rather than snapshotted once. A
     * single snapshot covers nested launches (a job does not complete until its
     * children do) but not a *sibling* launched into this same scope from
     * inside another of its coroutines: that one registers after the snapshot
     * and would be missed, silently returning the guarantee above to the state
     * the fixed sleep was in.
     *
     * The loop is deliberately unbounded. It terminates for every coroutine
     * this service starts — each is a finite, best-effort DAO write — but a
     * coroutine that never completes, or one that keeps re-launching into the
     * scope, would hang it. That is the right failure for a test-only helper:
     * a hung test names itself in a thread dump, whereas a bounded drain would
     * quietly return early and hand back exactly the false green this join
     * exists to prevent.
     */
    @VisibleForTesting
    internal suspend fun joinCaptureWork() {
        while (true) {
            val children = serviceScope.coroutineContext.job.children.toList()
            if (children.isEmpty()) return
            children.forEach { it.join() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val OWN_PACKAGE_PREFIX = "com.rousecontext"

        /**
         * Reference to the connected service instance, if any.
         * Used by [NotificationMcpProvider] to access active notifications.
         */
        @Volatile
        var instance: NotificationCaptureService? = null
            private set

        fun isOwnPackage(packageName: String): Boolean = packageName.startsWith(OWN_PACKAGE_PREFIX)
    }
}
