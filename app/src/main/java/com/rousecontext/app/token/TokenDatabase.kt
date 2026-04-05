package com.rousecontext.app.token

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for token persistence.
 */
@Database(entities = [TokenEntity::class], version = 1, exportSchema = false)
abstract class TokenDatabase : RoomDatabase() {

    abstract fun tokenDao(): TokenDao

    companion object {
        private const val DB_NAME = "rouse_tokens.db"

        fun create(context: Context): TokenDatabase =
            Room.databaseBuilder(context.applicationContext, TokenDatabase::class.java, DB_NAME)
                .allowMainThreadQueries() // TokenStore interface is synchronous
                .build()
    }
}
