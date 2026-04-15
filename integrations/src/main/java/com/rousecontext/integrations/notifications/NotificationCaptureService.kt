package com.rousecontext.integrations.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.rousecontext.notifications.FieldEncryptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dao: NotificationDao by lazy {
        NotificationDatabase.create(applicationContext).notificationDao()
    }

    private val encryptor: FieldEncryptor by lazy {
        FieldEncryptor(applicationContext)
    }

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
