package com.rousecontext.app.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.CertProvisioningFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.koin.core.context.GlobalContext
import org.koin.java.KoinJavaComponent.getKoin

/**
 * Debug-only ContentProvider for host-side e2e test automation.
 *
 * Only accepts calls from adb shell (SHELL_UID) or root.
 *
 * Usage:
 * ```
 * adb shell content call \
 *   --uri content://com.rousecontext.debug.test \
 *   --method enable_integration --extra id:s:outreach
 *
 * adb shell content call \
 *   --uri content://com.rousecontext.debug.test \
 *   --method approve_auth --extra code:s:AB3X-9K2F
 * ```
 */
class TestCommandProvider : ContentProvider() {

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            Log.w(TAG, "Rejected: caller uid $callingUid is not shell or root")
            return Bundle().apply { putString("error", "permission denied") }
        }

        if (GlobalContext.getKoinApplicationOrNull() == null) {
            Log.w(TAG, "Koin not initialized yet")
            return Bundle().apply { putString("error", "app not ready") }
        }

        return when (method) {
            "enable_integration" -> {
                val id = extras?.getString("id")
                    ?: return Bundle().apply { putString("error", "missing 'id' extra") }
                val store = getKoin().get<IntegrationStateStore>()
                runBlocking { store.setUserEnabled(id, true) }
                Log.i(TAG, "Enabled integration: $id")
                Bundle().apply { putString("result", "enabled $id") }
            }
            "disable_integration" -> {
                val id = extras?.getString("id")
                    ?: return Bundle().apply { putString("error", "missing 'id' extra") }
                val store = getKoin().get<IntegrationStateStore>()
                runBlocking { store.setUserEnabled(id, false) }
                Log.i(TAG, "Disabled integration: $id")
                Bundle().apply { putString("result", "disabled $id") }
            }
            "provision_cert" -> {
                val flow = getKoin().get<CertProvisioningFlow>()
                val result = runBlocking {
                    val user = FirebaseAuth.getInstance().signInAnonymously().await().user
                    val token = user?.getIdToken(false)?.await()?.token
                        ?: return@runBlocking "Firebase auth failed"
                    val r = flow.execute(token)
                    r.toString()
                }
                Log.i(TAG, "Cert provisioning result: $result")
                Bundle().apply { putString("result", result) }
            }
            "approve_auth" -> {
                val code = extras?.getString("code")
                    ?: return Bundle().apply { putString("error", "missing 'code' extra") }
                val session = getKoin().get<McpSession>()
                val mgr = session.authorizationCodeManager
                val pending = mgr.pendingRequests()
                Log.i(TAG, "Pending requests: ${pending.map { it.displayCode }}")
                Log.i(TAG, "AuthCodeManager identity: ${System.identityHashCode(mgr)}")
                val approved = mgr.approve(code)
                Log.i(TAG, "Approve auth '$code': $approved")
                Bundle().apply { putString("result", if (approved) "approved" else "not found") }
            }
            else -> {
                Log.w(TAG, "Unknown method: $method")
                Bundle().apply { putString("error", "unknown method: $method") }
            }
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        u: Uri,
        p: Array<String>?,
        s: String?,
        a: Array<String>?,
        o: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int = 0

    companion object {
        private const val TAG = "TestCommandProvider"
    }
}
