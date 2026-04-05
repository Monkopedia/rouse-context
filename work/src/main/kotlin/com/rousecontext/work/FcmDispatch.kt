package com.rousecontext.work

/**
 * Pure dispatch logic for FCM message handling.
 * Separated from [FcmReceiver] for testability.
 */
object FcmDispatch {

    /**
     * Resolve an FCM data payload to an [FcmAction].
     */
    fun resolve(data: Map<String, String>): FcmAction {
        return when (val type = data["type"]) {
            "wake" -> FcmAction.StartService
            "renew" -> FcmAction.EnqueueRenewal
            else -> FcmAction.Ignore(type)
        }
    }
}

/**
 * Actions that can result from an incoming FCM message.
 */
sealed interface FcmAction {
    /** Start the tunnel foreground service. */
    data object StartService : FcmAction

    /** Enqueue a certificate renewal via WorkManager. */
    data object EnqueueRenewal : FcmAction

    /** Unknown or missing message type; log and ignore. */
    data class Ignore(val type: String?) : FcmAction
}
