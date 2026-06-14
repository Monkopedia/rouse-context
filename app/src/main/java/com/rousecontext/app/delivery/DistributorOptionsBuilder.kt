package com.rousecontext.app.delivery

/**
 * Pure assembly of the "Background delivery" picker rows from the set of
 * installed UnifiedPush distributors. Kept flavor-agnostic and free of the
 * UnifiedPush library / Android `Context` so the ntfy-only-when-empty rule
 * (issue #463) is unit-testable in the shared source set.
 *
 * The foss [BackgroundDelivery] implementation gathers the installed
 * distributors (and resolves their display names) via the connector library,
 * then delegates row assembly here.
 */
object DistributorOptionsBuilder {

    /** A distributor present on the device, as discovered by UnifiedPush. */
    data class Installed(val id: String, val name: String)

    /** Package id of the ntfy app — the cold-start recommendation. */
    const val NTFY_PACKAGE = "io.heckel.ntfy"

    /**
     * Build the picker rows.
     *
     * - **Nothing installed:** suggest ntfy ([DistributorOption.Kind.INSTALL_NTFY])
     *   plus the generic "install another app" escape hatch. ntfy is suggested
     *   ONLY here.
     * - **One or more installed:** list exactly those distributors (the one
     *   matching [activeId] marked [DistributorOption.Kind.ACTIVE], the rest
     *   [DistributorOption.Kind.INSTALLED]) plus "install another app". Never
     *   inject an "install ntfy" row, even if the only installed distributor is
     *   a non-ntfy one.
     */
    fun build(installed: List<Installed>, activeId: String?): List<DistributorOption> {
        if (installed.isEmpty()) {
            return listOf(
                DistributorOption(
                    id = NTFY_PACKAGE,
                    name = "ntfy",
                    subtitle = "Recommended · not installed",
                    kind = DistributorOption.Kind.INSTALL_NTFY
                ),
                installAnotherRow()
            )
        }
        return buildList {
            installed.forEach { dist ->
                add(
                    DistributorOption(
                        id = dist.id,
                        name = dist.name,
                        subtitle = if (dist.id == activeId) "Active" else "Installed",
                        kind = if (dist.id == activeId) {
                            DistributorOption.Kind.ACTIVE
                        } else {
                            DistributorOption.Kind.INSTALLED
                        }
                    )
                )
            }
            add(installAnotherRow())
        }
    }

    private fun installAnotherRow() = DistributorOption(
        id = "",
        name = "Install another app",
        subtitle = "Browse delivery apps",
        kind = DistributorOption.Kind.INSTALL_OTHER
    )
}
