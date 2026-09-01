package com.easytier.service

import android.content.Context
import android.content.SharedPreferences
import com.easytier.data.NetworkConfig
import com.easytier.data.ServerEntry
import org.json.JSONArray
import org.json.JSONObject

data class OneClickSessionSnapshot(
    val instanceName: String,
    val hostname: String,
    val networkName: String,
    val networkSecret: String,
    val dhcp: Boolean,
    val ipv4: String,
    val generatedCode: String = "",
    val virtualIp: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("instance_name", instanceName)
        put("hostname", hostname)
        put("network_name", networkName)
        put("network_secret", networkSecret)
        put("dhcp", dhcp)
        put("ipv4", ipv4)
        put("generated_code", generatedCode)
        put("virtual_ip", virtualIp)
    }

    fun toNetworkConfig(): NetworkConfig = NetworkConfig(
        instanceName = instanceName.ifBlank { NetworkConfig.oneClickInstanceName() },
        hostname = hostname,
        networkName = networkName,
        networkSecret = networkSecret,
        dhcp = dhcp,
        ipv4 = ipv4,
        privateMode = true,
        servers = NetworkConfig.oneClickServers().toMutableList(),
    )

    companion object {
        fun fromJson(obj: JSONObject): OneClickSessionSnapshot {
            return OneClickSessionSnapshot(
                instanceName = obj.optString("instance_name", ""),
                hostname = obj.optString("hostname", ""),
                networkName = obj.optString("network_name", ""),
                networkSecret = obj.optString("network_secret", ""),
                dhcp = obj.optBoolean("dhcp", true),
                ipv4 = obj.optString("ipv4", ""),
                generatedCode = obj.optString("generated_code", ""),
                virtualIp = obj.optString("virtual_ip", ""),
            )
        }

        fun fromConfig(
            config: NetworkConfig,
            generatedCode: String = "",
            virtualIp: String = "",
        ): OneClickSessionSnapshot {
            return OneClickSessionSnapshot(
                instanceName = config.instanceName,
                hostname = config.hostname,
                networkName = config.networkName,
                networkSecret = config.networkSecret,
                dhcp = config.dhcp,
                ipv4 = config.ipv4,
                generatedCode = generatedCode,
                virtualIp = virtualIp,
            )
        }
    }
}

/** 应用设置持久化 —— 封装 SharedPreferences */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("easytier_settings", Context.MODE_PRIVATE)

    // ── 通用设置 ──

    var followSystemTheme: Boolean
        get() = prefs.getBoolean("follow_system", true)
        set(v) = prefs.edit().putBoolean("follow_system", v).apply()

    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(v) = prefs.edit().putBoolean("dark_mode", v).apply()

    // ── 日志设置 ──

    var logLevel: String
        get() = prefs.getString("log_level", "info") ?: "info"
        set(v) = prefs.edit().putString("log_level", v).apply()

    var oneClickGuestCodeDraft: String
        get() = prefs.getString("one_click_guest_code_draft", "") ?: ""
        set(v) = prefs.edit().putString("one_click_guest_code_draft", v).apply()

    // ── 网络配置持久化 ──

    fun saveNetworkConfigs(jsonArray: JSONArray) {
        val saved = prefs.edit().putString("network_configs", jsonArray.toString()).commit()
        LogService.info(
            "network_configs 已写入 SharedPreferences: success=$saved, size=${jsonArray.length()}",
            source = "SettingsRepository"
        )
    }

    fun saveNetworkConfigs(configs: List<NetworkConfig>) {
        val jsonArray = JSONArray()
        configs.forEach { jsonArray.put(JSONObject(it.toJson())) }
        LogService.info(
            "准备保存网络配置: ${
                configs.joinToString(" | ") {
                    "${it.instanceName}[label=${it.networkLabel}, host=${it.hostname}, network=${it.networkName}, secretLen=${it.networkSecret.length}]"
                }
            }",
            source = "SettingsRepository"
        )
        saveNetworkConfigs(jsonArray)
    }

    // ── 在线节点图标 ──

    fun loadNodeDeviceIcons(): MutableMap<String, String> {
        val raw = prefs.getString("node_device_icons", null) ?: return mutableMapOf()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "").trim()
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
            result
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveNodeDeviceIcon(nodeKey: String, deviceType: String) {
        val normalizedKey = nodeKey.trim()
        val normalizedType = deviceType.trim()
        if (normalizedKey.isBlank() || normalizedType.isBlank()) return

        val icons = loadNodeDeviceIcons()
        icons[normalizedKey] = normalizedType
        val json = JSONObject().apply {
            icons.forEach { (key, value) -> put(key, value) }
        }
        prefs.edit().putString("node_device_icons", json.toString()).apply()
    }

    fun loadNetworkConfigsJson(): JSONArray? {
        val json = prefs.getString("network_configs", null) ?: return null
        return try { JSONArray(json) } catch (_: Exception) { null }
    }

    fun loadNetworkConfigs(): MutableList<NetworkConfig> {
        val jsonArray = loadNetworkConfigsJson() ?: return mutableListOf(NetworkConfig())
        val configs = mutableListOf<NetworkConfig>()

        for (index in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(index) ?: continue
            configs.add(NetworkConfig.fromJson(obj))
        }
        val result = if (configs.isEmpty()) mutableListOf(NetworkConfig()) else configs
        LogService.info(
            "读取网络配置: ${
                result.joinToString(" | ") {
                    "${it.instanceName}[label=${it.networkLabel}, host=${it.hostname}, network=${it.networkName}, secretLen=${it.networkSecret.length}]"
                }
            }",
            source = "SettingsRepository"
        )
        return result
    }

    fun addServerToFirstNetworkConfig(serverUrl: String): Boolean {
        val configs = loadNetworkConfigs()
        val firstConfig = configs.firstOrNull() ?: return false

        if (firstConfig.servers.any { it == serverUrl }) {
            return false
        }

        firstConfig.servers = (firstConfig.servers + serverUrl).toMutableList()
        saveNetworkConfigs(configs)
        return true
    }

    // ── 服务器收藏 ──

    fun saveFavoriteServers(jsonArray: JSONArray) {
        prefs.edit().putString("favorite_servers", jsonArray.toString()).apply()
    }

    fun loadFavoriteServersJson(): JSONArray? {
        val json = prefs.getString("favorite_servers", null) ?: return null
        return try { JSONArray(json) } catch (_: Exception) { null }
    }

    fun loadFavoriteServers(): MutableList<ServerEntry> {
        val json = loadFavoriteServersJson() ?: return defaultServerEntries()
        val servers = mutableListOf<ServerEntry>()

        for (index in 0 until json.length()) {
            val obj = json.optJSONObject(index) ?: continue
            servers.add(ServerEntry.fromJson(obj))
        }

        val sanitizedServers = servers
            .filterNot { it.isDefault && NetworkConfig.isDeprecatedDefaultServer(it.url) }
            .toMutableList()

        return if (sanitizedServers.isEmpty() || isLegacyDefaultServerList(sanitizedServers)) {
            defaultServerEntries()
        } else {
            sanitizedServers
        }
    }

    fun saveFavoriteServers(servers: List<ServerEntry>) {
        val jsonArray = JSONArray()
        servers.forEach { jsonArray.put(it.toJson()) }
        saveFavoriteServers(jsonArray)
    }

    private fun defaultServerEntries(): MutableList<ServerEntry> {
        return NetworkConfig.defaultServers().map { url ->
            val name = when (url) {
                "tcp://225284.xyz:11010" -> "225284 公共服务器"
                else -> url
            }
            ServerEntry(name = name, url = url, isDefault = true)
        }.toMutableList()
    }

    private fun isLegacyDefaultServerList(servers: List<ServerEntry>): Boolean {
        return servers.size == 1 && servers.first().isDefault && servers.first().url == "wss://qtet-public.070219.xyz"
    }

    fun saveOneClickHostSession(session: OneClickSessionSnapshot?) {
        saveOneClickSession("one_click_host_session", session)
    }

    fun loadOneClickHostSession(): OneClickSessionSnapshot? {
        return loadOneClickSession("one_click_host_session")
    }

    fun saveOneClickGuestSession(session: OneClickSessionSnapshot?) {
        saveOneClickSession("one_click_guest_session", session)
    }

    fun loadOneClickGuestSession(): OneClickSessionSnapshot? {
        return loadOneClickSession("one_click_guest_session")
    }

    private fun saveOneClickSession(key: String, session: OneClickSessionSnapshot?) {
        val editor = prefs.edit()
        if (session == null) {
            editor.remove(key)
        } else {
            editor.putString(key, session.toJson().toString())
        }
        editor.apply()
    }

    private fun loadOneClickSession(key: String): OneClickSessionSnapshot? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            OneClickSessionSnapshot.fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    // ── 清除 ──

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
