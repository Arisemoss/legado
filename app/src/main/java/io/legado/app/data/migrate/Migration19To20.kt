package io.legado.app.data.migrate

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库 19→20：新增 AI 会话与消息两张表。
 */
val migration_19_20 = object : Migration(19, 20) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS aiSessions(
               id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
               title TEXT NOT NULL,
               createdAt INTEGER NOT NULL,
               updatedAt INTEGER NOT NULL,
               archived INTEGER NOT NULL DEFAULT 0,
               model TEXT,
               lastSummaryAt INTEGER NOT NULL DEFAULT 0)"""
        )
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS aiMessages(
               id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
               sessionId INTEGER NOT NULL,
               seq INTEGER NOT NULL,
               kind TEXT NOT NULL,
               role TEXT NOT NULL,
               content TEXT NOT NULL,
               payload TEXT,
               toolName TEXT,
               quotaBilled INTEGER,
               flags TEXT,
               createdAt INTEGER NOT NULL)"""
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS idx_aiMessages_session_seq ON aiMessages(sessionId, seq)")
    }
}