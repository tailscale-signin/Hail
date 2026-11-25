package com.aistra.hail.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

internal const val MAX_BACKUP_BYTES = 4 * 1024 * 1024

class BackupContent(
    val apps: List<AppInfo>,
    val tags: List<Pair<String, Int>>,
    val settings: Map<String, Any>
) {
    fun writeTo(output: OutputStream) {
        val bytes = JSONObject().put("format", FORMAT).put("version", 1)
            .put("apps", encodeApps(apps)).put("tags", encodeTags(tags))
            .put("settings", JSONObject(settings)).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BACKUP_BYTES)
        output.write(bytes)
    }

    companion object {
        private const val FORMAT = "com.aistra.hail.backup"
        private val packageId = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
        private val booleans = setOf(
            HailData.FILTER_USER_APPS, HailData.FILTER_SYSTEM_APPS, HailData.FILTER_FROZEN_APPS,
            HailData.FILTER_UNFROZEN_APPS, HailData.GRAYSCALE_ICON, HailData.COMPACT_ICON,
            HailData.SYNTHESIZE_ADAPTIVE_ICONS, HailData.FUZZY_SEARCH, HailData.NINE_KEY_SEARCH,
            HailData.AUTO_FREEZE_AFTER_LOCK, HailData.SKIP_FOREGROUND_APP,
            HailData.SKIP_NOTIFYING_APP, HailData.SKIP_WHILE_CHARGING
        )
        private val choices get() = mapOf(
            HailData.APP_THEME to HailData.APP_THEME_VALUES,
            HailData.SORT_BY to listOf(HailData.SORT_NAME, HailData.SORT_INSTALL, HailData.SORT_UPDATE),
            HailData.TILE_ACTION to HailData.TILE_ACTION_VALUES,
            HailData.DYNAMIC_SHORTCUT_ACTION to HailData.DYNAMIC_SHORTCUT_ACTIONS
        )
        val settingKeys get() = booleans + choices.keys + setOf(
            HailData.ICON_PACK, HailData.HOME_FONT_SIZE, HailData.AUTO_FREEZE_DELAY
        )

        fun portableSettings(values: Map<String, *>): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            val enums = choices
            for ((key, value) in values) {
                when {
                    key in booleans -> result[key] = value as Boolean
                    key in enums -> result[key] = (value as String).also { require(it in enums.getValue(key)) }
                    key == HailData.ICON_PACK -> result[key] = (value as String).also { require(packageId.matches(it)) }
                    key == HailData.HOME_FONT_SIZE || key == HailData.AUTO_FREEZE_DELAY -> {
                        val number = (value as Number).toFloat()
                        val range = if (key == HailData.HOME_FONT_SIZE) 11f..16f else 0f..30f
                        require(number in range)
                        result[key] = number
                    }
                }
            }
            return result
        }

        fun encodeApps(apps: List<AppInfo>) = JSONArray().apply {
            apps.forEach { put(JSONObject().put("package", it.packageName).put("pinned", it.pinned)
                .put("whitelisted", it.whitelisted).put("tags", JSONArray(it.tagIdList))) }
        }

        fun encodeTags(tags: List<Pair<String, Int>>) = JSONArray().apply {
            tags.forEach { (name, id) -> put(JSONObject().put("tag", name).put("id", id)) }
        }

        fun readFrom(input: InputStream): BackupContent {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            val bytes = ByteArrayOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(bytes.size() + count <= MAX_BACKUP_BYTES)
                bytes.write(buffer, 0, count)
            }
            val parser = JSONTokener(bytes.toString("UTF-8"))
            val root = parser.nextValue() as JSONObject
            require(parser.nextClean() == '\u0000' && root.get("format") == FORMAT && root.get("version") == 1)
            val tags = root.getJSONArray("tags").mapObjects { (it.get("tag") as String) to (it.get("id") as Int) }
            val ids = tags.mapTo(hashSetOf()) { it.second }
            require(tags.firstOrNull()?.second == 0 && ids.size == tags.size)
            val seen = hashSetOf<String>()
            val apps = root.getJSONArray("apps").mapObjects { item ->
                val pkg = item.get("package") as String
                require(packageId.matches(pkg) && seen.add(pkg))
                val values = item.getJSONArray("tags")
                val memberships = MutableList(values.length()) { values.get(it) as Int }
                require(memberships.isNotEmpty() && memberships.all { it in ids } && memberships.distinct().size == memberships.size)
                AppInfo(pkg, item.get("pinned") as Boolean, item.get("whitelisted") as Boolean, memberships)
            }
            val preferences = root.getJSONObject("settings")
            val settings = portableSettings(preferences.keys().asSequence().associateWith { preferences.get(it) })
            return BackupContent(apps, tags, settings)
        }

        private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T) =
            List(length()) { transform(getJSONObject(it)) }
    }
}
