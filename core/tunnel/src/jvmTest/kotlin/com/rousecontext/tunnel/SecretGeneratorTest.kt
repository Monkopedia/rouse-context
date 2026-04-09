package com.rousecontext.tunnel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretGeneratorTest {

    @Test
    fun `generate produces adjective-integration format`() {
        val secret = SecretGenerator.generate("health")
        assertTrue(secret.endsWith("-health"))
        val adjective = secret.removeSuffix("-health")
        assertTrue(adjective in SecretGenerator.ADJECTIVES)
    }

    @Test
    fun `generate with fixed random is deterministic`() {
        val random1 = Random(42)
        val random2 = Random(42)
        val secret1 = SecretGenerator.generate("health", random1)
        val secret2 = SecretGenerator.generate("health", random2)
        assertEquals(secret1, secret2)
    }

    @Test
    fun `generateAll produces secrets for all integrations`() {
        val secrets = SecretGenerator.generateAll(listOf("health", "notifications"))
        assertEquals(2, secrets.size)
        assertTrue(secrets["health"]!!.endsWith("-health"))
        assertTrue(secrets["notifications"]!!.endsWith("-notifications"))
    }

    @Test
    fun `generateAll with empty list returns empty map`() {
        val secrets = SecretGenerator.generateAll(emptyList())
        assertTrue(secrets.isEmpty())
    }

    @Test
    fun `adjective list has sufficient variety`() {
        assertTrue(SecretGenerator.ADJECTIVES.size >= 200)
        // No duplicates
        assertEquals(
            SecretGenerator.ADJECTIVES.size,
            SecretGenerator.ADJECTIVES.toSet().size
        )
    }
}
