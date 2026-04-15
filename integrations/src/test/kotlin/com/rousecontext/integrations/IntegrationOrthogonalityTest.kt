package com.rousecontext.integrations

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enforces that integration packages (outreach, usage, health, notifications)
 * remain orthogonal: no source file under one integration sibling may import
 * from another. Integrations should be independent; cross-cutting code belongs
 * in :notifications or :core:mcp, not in a sibling integration package.
 */
class IntegrationOrthogonalityTest {

    private val siblings = listOf("outreach", "usage", "health", "notifications")

    @Test
    fun `no integration package imports from a sibling integration package`() {
        val srcRoots = listOf(
            File("src/main/java/com/rousecontext/integrations"),
            File("src/main/kotlin/com/rousecontext/integrations")
        ).filter { it.exists() }

        val violations = mutableListOf<String>()
        for (root in srcRoots) {
            val ownedBy = root.listFiles()?.filter { it.isDirectory } ?: continue
            for (ownerDir in ownedBy) {
                val owner = ownerDir.name
                if (owner !in siblings) continue
                val others = siblings - owner
                ownerDir.walkTopDown().filter {
                    it.isFile && it.extension == "kt"
                }.forEach { file ->
                    val text = file.readText()
                    for (other in others) {
                        val import = "import com.rousecontext.integrations.$other."
                        if (text.contains(import)) {
                            violations += "${file.path} imports from sibling '$other'"
                        }
                    }
                }
            }
        }

        assertTrue(
            "Integrations must remain orthogonal. Violations:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }
}
