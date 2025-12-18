package com.smart

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DB_NAME = "smart_config.db"
private const val DB_VERSION = 10

class SmartConfigDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE agent_rule_config (
                key TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE app_rule_config (
                key TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL,
                selected_apps TEXT,
                version INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE agent_rules (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                value TEXT,
                tunnel_name TEXT NOT NULL,
                tunnel_file TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                position INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE tunnels (
                file TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                content TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE debug_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                message TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE doh_config (
                key TEXT PRIMARY KEY,
                enabled INTEGER NOT NULL,
                doh_url TEXT
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No compatibility needed; rebuild schema
        db.execSQL("DROP TABLE IF EXISTS agent_rule_config")
        db.execSQL("DROP TABLE IF EXISTS app_rule_config")
        db.execSQL("DROP TABLE IF EXISTS agent_rules")
        db.execSQL("DROP TABLE IF EXISTS tunnels")
        db.execSQL("DROP TABLE IF EXISTS logs")
        db.execSQL("DROP TABLE IF EXISTS debug_logs")
        db.execSQL("DROP TABLE IF EXISTS doh_config")
        onCreate(db)
    }

    fun load(json: Json): Triple<AgentRuleConfig, AppRuleConfig, DohConfig> {
        val db = readableDatabase
        val agentConfigEnabled = db.rawQuery("SELECT enabled FROM agent_rule_config WHERE key = 'default' LIMIT 1", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) == 1 else false
        }

        val appRule = db.rawQuery(
            "SELECT enabled, selected_apps, version FROM app_rule_config WHERE key = 'default' LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val enabled = cursor.getInt(0) == 1
                val selectedAppsJson = cursor.getString(1)
                val selectedApps = selectedAppsJson?.let {
                    runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
                } ?: emptyList()
                val version = cursor.getInt(2)
                Triple(enabled, selectedApps, version)
            } else {
                Triple(false, emptyList(), 0)
            }
        }

        val tunnelRecords = listTunnelRecords()
        val tunnelNames = tunnelRecords.associate { it.file to it.name }

        val rules = db.rawQuery(
            "SELECT id, type, value, tunnel_name, tunnel_file, enabled FROM agent_rules ORDER BY position ASC",
            null
        ).use { cursor ->
            val list = mutableListOf<AgentRule>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val type = cursor.getString(1)
                val value = cursor.getString(2)
                val tunnelNameRaw = cursor.getString(3)
                val tunnelFile = cursor.getString(4)
                val enabled = cursor.getInt(5) == 1
                runCatching {
                    list.add(
                        AgentRule(
                            id = id,
                            type = RuleType.valueOf(type),
                            value = value,
                            tunnelFile = tunnelFile,
                            tunnelName = tunnelNames[tunnelFile] ?: tunnelNameRaw,
                            enabled = enabled
                        )
                    )
                }
            }
            list.toList()
        }

        val agent = AgentRuleConfig(
            enabled = agentConfigEnabled,
            rules = rules
        )
        val appRuleConfig = AppRuleConfig(
            enabled = appRule.first,
            selectedApps = appRule.second,
            version = appRule.third
        )
        val dohConfig = db.rawQuery(
            "SELECT enabled, doh_url FROM doh_config WHERE key = 'default' LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val enabled = cursor.getInt(0) == 1
                val dohUrl = cursor.getString(1) ?: ""
                DohConfig(enabled = enabled, dohUrl = dohUrl)
            } else {
                DohConfig()
            }
        }
        return Triple(agent, appRuleConfig, dohConfig)
    }

    data class TunnelRecord(val name: String, val file: String, val content: String)

    fun upsertTunnel(name: String, file: String, content: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("file", file)
            put("name", name)
            put("content", content)
        }
        db.insertWithOnConflict("tunnels", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun listTunnelRecords(): List<TunnelRecord> {
        val db = readableDatabase
        return db.rawQuery("SELECT name, file, content FROM tunnels", null).use { cursor ->
            val list = mutableListOf<TunnelRecord>()
            while (cursor.moveToNext()) {
                list.add(
                    TunnelRecord(
                        name = cursor.getString(0),
                        file = cursor.getString(1),
                        content = cursor.getString(2)
                    )
                )
            }
            list.toList()
        }
    }

    fun deleteTunnel(file: String) {
        val db = writableDatabase
        db.delete("tunnels", "file = ?", arrayOf(file))
    }

    fun getTunnelContent(file: String): String? {
        val db = readableDatabase
        return db.rawQuery("SELECT content FROM tunnels WHERE file = ? LIMIT 1", arrayOf(file)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun pruneMissing(existingFiles: Set<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT file FROM tunnels", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val file = cursor.getString(0)
                    if (!existingFiles.contains(file)) {
                        db.delete("tunnels", "file = ?", arrayOf(file))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAgentConfig(agent: AgentRuleConfig, json: Json) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("agent_rule_config", null, null)
            db.delete("agent_rules", null, null)

            val cfg = ContentValues().apply {
                put("key", "default")
                put("enabled", if (agent.enabled) 1 else 0)
            }
            db.insert("agent_rule_config", null, cfg)

            agent.rules.forEachIndexed { index, rule ->
                val values = ContentValues().apply {
                    put("id", rule.id)
                    put("type", rule.type.name)
                    put("value", rule.value)
                    put("tunnel_name", rule.tunnelName)
                    put("tunnel_file", rule.tunnelFile)
                    put("enabled", if (rule.enabled) 1 else 0)
                    put("position", index)
                }
                db.insert("agent_rules", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAppRuleConfig(appRule: AppRuleConfig, json: Json) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("app_rule_config", null, null)
            val appRuleCfg = ContentValues().apply {
                put("key", "default")
                put("enabled", if (appRule.enabled) 1 else 0)
                put("selected_apps", json.encodeToString(appRule.selectedApps))
                put("version", appRule.version)
            }
            db.insert("app_rule_config", null, appRuleCfg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveDohConfig(config: DohConfig) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("doh_config", null, null)
            val values = ContentValues().apply {
                put("key", "default")
                put("enabled", if (config.enabled) 1 else 0)
                put("doh_url", config.dohUrl)
            }
            db.insert("doh_config", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun insertLog(timestamp: Long, message: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("message", message)
        }
        db.insert("logs", null, values)
        // Keep log size reasonable
        db.execSQL(
            """
            DELETE FROM logs WHERE id NOT IN (
                SELECT id FROM logs ORDER BY id DESC LIMIT 300
            )
            """.trimIndent()
        )
    }

    fun insertDebugLog(timestamp: Long, message: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("timestamp", timestamp)
            put("message", message)
        }
        db.insert("debug_logs", null, values)
        db.execSQL(
            """
            DELETE FROM debug_logs WHERE id NOT IN (
                SELECT id FROM debug_logs ORDER BY id DESC LIMIT 500
            )
            """.trimIndent()
        )
    }

    fun getLogs(limit: Int = 10, offset: Int = 0): List<Pair<Long, String>> {
        val db = readableDatabase
        return db.rawQuery(
            "SELECT timestamp, message FROM logs ORDER BY id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        ).use { cursor ->
            val list = mutableListOf<Pair<Long, String>>()
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) to cursor.getString(1))
            }
            list.toList()
        }
    }

    fun getDebugLogs(limit: Int = 10, offset: Int = 0): List<Pair<Long, String>> {
        val db = readableDatabase
        return db.rawQuery(
            "SELECT timestamp, message FROM debug_logs ORDER BY id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        ).use { cursor ->
            val list = mutableListOf<Pair<Long, String>>()
            while (cursor.moveToNext()) {
                list.add(cursor.getLong(0) to cursor.getString(1))
            }
            list.toList()
        }
    }

    fun clearLogs() {
        val db = writableDatabase
        db.delete("logs", null, null)
        db.delete("debug_logs", null, null)
    }
}
