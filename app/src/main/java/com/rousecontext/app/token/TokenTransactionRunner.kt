package com.rousecontext.app.token

import androidx.room.RoomDatabase
import java.util.concurrent.Callable

/**
 * Runs a block of DAO calls as one atomic, serialized database transaction.
 *
 * [RoomTokenStore] needs this because refresh-token rotation is not a single
 * statement: it consumes the parent, and then EITHER mints a child OR revokes
 * the family. Those have to commit or fail as a unit and must not interleave
 * with another rotation of the same family — see issue #760.
 *
 * It is an interface rather than a direct [RoomDatabase] dependency so that
 * tests can wrap the DAO in a decorator and still get real transactions.
 */
interface TokenTransactionRunner {

    /** Executes [block] inside a single transaction and returns its result. */
    fun <T> inTransaction(block: () -> T): T
}

/**
 * The production [TokenTransactionRunner]: a real Room transaction.
 *
 * SQLite admits one write transaction at a time and Room opens them with the
 * write lock held from `BEGIN`, so concurrent rotations are serialized rather
 * than merely isolated.
 */
class RoomTransactionRunner(private val db: RoomDatabase) : TokenTransactionRunner {

    override fun <T> inTransaction(block: () -> T): T = db.runInTransaction(Callable { block() })
}
