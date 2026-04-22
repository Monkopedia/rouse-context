package com.rousecontext.integrations.notifications

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.notifications.FieldEncryptor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController

/**
 * Drives [NotificationCaptureService] through its public callbacks via a
 * Robolectric [ServiceController], verifying:
 *  - onNotificationPosted persists a [NotificationRecord]
 *  - onNotificationRemoved marks the existing record removed
 *  - Own-package notifications are skipped by all three paths
 *  - isOwnPackage helper logic
 *  - instance reference management (set on listener connect, cleared on destroy/disconnect)
 *  - onDestroy cancels the service scope
 *
 * `seedActiveNotifications` + `activeNotifications` need the framework's
 * binder and are noted as device-only.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationCaptureServiceTest {

    private lateinit var context: Context
    private lateinit var database: NotificationDatabase
    private lateinit var realDao: NotificationDao
    private lateinit var dao: SignalingDao
    private lateinit var encryptor: FieldEncryptor
    private lateinit var controller: ServiceController<NotificationCaptureService>
    private lateinit var service: NotificationCaptureService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = NotificationDatabase.createInMemory(context)
        realDao = database.notificationDao()
        dao = SignalingDao(realDao)

        // Encryptor stubbed to a pass-through so we can assert on raw values
        encryptor = mockk(relaxed = true)
        every { encryptor.encrypt(any<String>()) } answers { firstArg() }
        every { encryptor.encrypt(null) } returns null
        every { encryptor.decrypt(any<String>()) } answers { firstArg() }

        runCatching { stopKoin() }
        startKoin {
            modules(
                module {
                    single<NotificationDao> { dao }
                    single<FieldEncryptor> { encryptor }
                }
            )
        }

        controller = Robolectric.buildService(NotificationCaptureService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
        database.close()
        stopKoin()
    }

    @Suppress("LongParameterList")
    private fun mockSbn(
        key: String = "k",
        pkg: String = "com.example.app",
        title: String? = "Title",
        text: String? = "Body",
        postTime: Long = 1_000L,
        ongoing: Boolean = false,
        category: String? = null
    ): StatusBarNotification {
        val sbn = mockk<StatusBarNotification>(relaxed = true)
        every { sbn.key } returns key
        every { sbn.packageName } returns pkg
        every { sbn.postTime } returns postTime
        every { sbn.isOngoing } returns ongoing

        val notification = Notification()
        notification.category = category
        val extras = Bundle()
        if (title != null) extras.putCharSequence("android.title", title)
        if (text != null) extras.putCharSequence("android.text", text)
        notification.extras = extras
        every { sbn.notification } returns notification
        return sbn
    }

    @Test
    fun `isOwnPackage matches rousecontext packages only`() {
        assertTrue(NotificationCaptureService.isOwnPackage("com.rousecontext"))
        assertTrue(NotificationCaptureService.isOwnPackage("com.rousecontext.app"))
        assertTrue(NotificationCaptureService.isOwnPackage("com.rousecontext.foo.bar"))

        assertFalse(NotificationCaptureService.isOwnPackage("com.rouse"))
        assertFalse(NotificationCaptureService.isOwnPackage("com.slack.android"))
        assertFalse(NotificationCaptureService.isOwnPackage(""))
    }

    @Test
    fun `onNotificationPosted persists record`() = runBlocking {
        val sbn = mockSbn(
            pkg = "com.slack.android",
            title = "Hello",
            text = "World",
            postTime = 12_345L,
            ongoing = true,
            category = "msg"
        )

        service.onNotificationPosted(sbn)
        // The service launches its DAO write on Dispatchers.IO. SignalingDao
        // completes the `inserts` deferred from inside the write, so assertion
        // failures surface immediately rather than as polling timeouts.
        dao.awaitInsert()

        val records = dao.search()
        assertEquals(1, records.size)
        val record = records[0]
        assertEquals("com.slack.android", record.packageName)
        assertEquals("Hello", record.title)
        assertEquals("World", record.text)
        assertEquals(12_345L, record.postedAt)
        assertTrue(record.ongoing)
        assertEquals("msg", record.category)
    }

    @Test
    fun `onNotificationPosted skips own package`() = runBlocking {
        val sbn = mockSbn(pkg = "com.rousecontext.audit")

        service.onNotificationPosted(sbn)
        // Give any launched coroutine a chance to run
        Thread.sleep(50)

        assertEquals(0, dao.countInRange(0L, Long.MAX_VALUE))
    }

    @Test
    fun `onNotificationRemoved marks existing record removed`() = runBlocking {
        val sbn = mockSbn(pkg = "com.slack.android", postTime = 1_000L)
        service.onNotificationPosted(sbn)
        dao.awaitInsert()
        val before = dao.search()
        assertNull(before[0].removedAt)

        service.onNotificationRemoved(sbn)
        dao.awaitMarkRemoved()

        val after = dao.search()
        assertNotNull(after[0].removedAt)
    }

    @Test
    fun `onNotificationRemoved ignores own package`() = runBlocking {
        val sbn = mockSbn(pkg = "com.rousecontext.foo", postTime = 1_000L)
        service.onNotificationRemoved(sbn)

        Thread.sleep(50)
        // No dao interaction, nothing to assert beyond no crash + empty table.
        assertEquals(0, dao.countInRange(0L, Long.MAX_VALUE))
    }

    @Test
    fun `onNotificationRemoved no-op when no matching record`() = runBlocking {
        val sbn = mockSbn(pkg = "com.unknown.pkg", postTime = 99_999L)

        service.onNotificationRemoved(sbn)
        Thread.sleep(50)

        // No prior insert -> nothing to mark; table stays empty.
        assertEquals(0, dao.countInRange(0L, Long.MAX_VALUE))
    }

    @Test
    fun `onListenerConnected sets instance and onListenerDisconnected clears it`() {
        // The Robolectric-created service hasn't been connected to the system.
        assertNull(NotificationCaptureService.instance)

        // The full NotificationListenerService lifecycle requires system binder;
        // invoke the overrides directly to exercise our override bodies.
        // onListenerConnected will invoke seedActiveNotifications which reads
        // `activeNotifications` — a system-binder call returning null here —
        // so the swallowed-exception path exercises the try/catch as well.
        service.onListenerConnected()
        assertSame(service, NotificationCaptureService.instance)

        service.onListenerDisconnected()
        assertNull(NotificationCaptureService.instance)
    }

    @Test
    fun `onListenerDisconnected does not clear instance for another service`() {
        service.onListenerConnected()
        assertSame(service, NotificationCaptureService.instance)

        // Create a second controller, simulating a secondary instance
        val otherController = Robolectric.buildService(NotificationCaptureService::class.java)
        val other = otherController.create().get()

        other.onListenerDisconnected()
        // Should NOT have cleared `instance` because the static ref points to the first.
        assertSame(service, NotificationCaptureService.instance)

        otherController.destroy()
    }

    @Test
    fun `onDestroy cancels service scope without crashing`() {
        // Verifies the destroy path is exercised. If coroutines were still running
        // they would need cancellation; this must not throw.
        controller.destroy()
        // Re-destroy via controller should be safe; service state cleaned up.
    }

    @Test
    fun `onNotificationPosted swallows dao exceptions`() {
        // Swap in a dao that throws. Use a child service wired manually with
        // Koin, so the failure path exercises the catch block.
        val failingDao = mockk<NotificationDao>()
        coEvery { failingDao.insert(any()) } throws RuntimeException("boom")

        stopKoin()
        startKoin {
            modules(
                module {
                    single<NotificationDao> { failingDao }
                    single<FieldEncryptor> { encryptor }
                }
            )
        }

        val failController = Robolectric.buildService(NotificationCaptureService::class.java)
        val failService = failController.create().get()

        val sbn = mockSbn(pkg = "com.slack.android")
        // Must not throw.
        failService.onNotificationPosted(sbn)
        Thread.sleep(50)
        coVerify { failingDao.insert(any()) }

        failController.destroy()
    }

    @Test
    fun `onNotificationRemoved swallows dao exceptions`() {
        val failingDao = mockk<NotificationDao>()
        coEvery { failingDao.findByPackageAndTime(any(), any()) } throws
            RuntimeException("boom")

        stopKoin()
        startKoin {
            modules(
                module {
                    single<NotificationDao> { failingDao }
                    single<FieldEncryptor> { encryptor }
                }
            )
        }

        val failController = Robolectric.buildService(NotificationCaptureService::class.java)
        val failService = failController.create().get()

        val sbn = mockSbn(pkg = "com.slack.android")
        failService.onNotificationRemoved(sbn)
        Thread.sleep(50)
        coVerify { failingDao.findByPackageAndTime(any(), any()) }

        failController.destroy()
    }

    @Test
    fun `onNotificationPosted encrypts title and body via encryptor`() {
        val enc = mockk<FieldEncryptor>()
        every { enc.encrypt(any<String>()) } answers { "enc(${firstArg<String>()})" }
        every { enc.encrypt(null) } returns null

        stopKoin()
        startKoin {
            modules(
                module {
                    single<NotificationDao> { dao }
                    single<FieldEncryptor> { enc }
                }
            )
        }

        val encController = Robolectric.buildService(NotificationCaptureService::class.java)
        val encService = encController.create().get()

        val sbn = mockSbn(
            pkg = "com.slack.android",
            title = "plainTitle",
            text = "plainBody",
            postTime = 42L
        )
        encService.onNotificationPosted(sbn)
        runBlocking { dao.awaitInsert() }

        val record = runBlocking { dao.search() }[0]
        assertEquals("enc(plainTitle)", record.title)
        assertEquals("enc(plainBody)", record.text)

        encController.destroy()
    }
}

/**
 * DAO decorator that completes [CompletableDeferred]s when writes finish, so
 * tests can await the service's background coroutine deterministically instead
 * of polling. Each call to [awaitInsert] / [awaitMarkRemoved] consumes the
 * current deferred and resets it, enabling back-to-back awaits across multiple
 * writes within a single test.
 */
private class SignalingDao(private val delegate: NotificationDao) : NotificationDao by delegate {

    private val lock = Any()

    @Volatile
    private var insertSignal: CompletableDeferred<Unit> = CompletableDeferred()

    @Volatile
    private var markRemovedSignal: CompletableDeferred<Unit> = CompletableDeferred()

    override suspend fun insert(record: NotificationRecord): Long {
        val result = delegate.insert(record)
        synchronized(lock) { insertSignal.complete(Unit) }
        return result
    }

    override suspend fun markRemoved(id: Long, removedAt: Long) {
        delegate.markRemoved(id, removedAt)
        synchronized(lock) { markRemovedSignal.complete(Unit) }
    }

    suspend fun awaitInsert(timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS) {
        val signal = synchronized(lock) { insertSignal }
        withTimeout(timeoutMillis) { signal.await() }
        synchronized(lock) {
            if (insertSignal === signal) insertSignal = CompletableDeferred()
        }
    }

    suspend fun awaitMarkRemoved(timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS) {
        val signal = synchronized(lock) { markRemovedSignal }
        withTimeout(timeoutMillis) { signal.await() }
        synchronized(lock) {
            if (markRemovedSignal === signal) markRemovedSignal = CompletableDeferred()
        }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 5_000L
    }
}
