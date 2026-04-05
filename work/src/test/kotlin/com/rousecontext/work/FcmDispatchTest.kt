package com.rousecontext.work

import org.junit.Assert.assertEquals
import org.junit.Test

class FcmDispatchTest {

    @Test
    fun `type wake returns StartService action`() {
        val action = FcmDispatch.resolve(mapOf("type" to "wake"))
        assertEquals(FcmAction.StartService, action)
    }

    @Test
    fun `type renew returns EnqueueRenewal action`() {
        val action = FcmDispatch.resolve(mapOf("type" to "renew"))
        assertEquals(FcmAction.EnqueueRenewal, action)
    }

    @Test
    fun `unknown type returns Ignore action`() {
        val action = FcmDispatch.resolve(mapOf("type" to "foo"))
        assertEquals(FcmAction.Ignore("foo"), action)
    }

    @Test
    fun `missing type returns Ignore action`() {
        val action = FcmDispatch.resolve(mapOf("other" to "value"))
        assertEquals(FcmAction.Ignore(null), action)
    }

    @Test
    fun `empty data returns Ignore action`() {
        val action = FcmDispatch.resolve(emptyMap())
        assertEquals(FcmAction.Ignore(null), action)
    }
}
