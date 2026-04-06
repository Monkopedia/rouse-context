package com.rousecontext.app.cert

import android.content.Context
import com.rousecontext.tunnel.WebSocketFactory
import com.rousecontext.tunnel.WebSocketHandle
import com.rousecontext.tunnel.WebSocketListener

/**
 * A [WebSocketFactory] that defers creation of the real mTLS-configured factory
 * until the first [connect] call. This avoids failing at Koin init time when
 * cert files don't exist yet (e.g., before onboarding).
 *
 * Call [invalidate] after cert files change (onboarding, cert renewal) so the
 * next [connect] picks up the new credentials.
 */
class LazyWebSocketFactory(private val context: Context) : WebSocketFactory {

    @Volatile
    private var delegate: WebSocketFactory? = null

    override fun connect(url: String, listener: WebSocketListener): WebSocketHandle {
        val factory = delegate ?: MtlsWebSocketFactory.create(context).also { delegate = it }
        return factory.connect(url, listener)
    }

    /**
     * Clear the cached factory so it is recreated on next [connect].
     * Call this after onboarding completes or certificates are renewed.
     */
    fun invalidate() {
        delegate = null
    }
}
