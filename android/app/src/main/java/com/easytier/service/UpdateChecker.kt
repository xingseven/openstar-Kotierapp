package com.easytier.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val latestVersion: String,
    val latestVersionCode: Int?,
    val downloadUrl: String,
    val releaseNotes: String,
)

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val VERSION_CHECK_API =
            "https://kotier.wotty.app/version.json"
        private const val MANUAL_DOWNLOAD_NOTE =
            "如果遇到签名不一致，请手动下载安装包并更新。"
        private const val TIMEOUT = 15_000
        private const val DOWNLOAD_TIMEOUT = 120_000
    }

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        data class Unavailable(val reason: String) : Result()
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        try {
            val conn = URL(VERSION_CHECK_API).openConnection() as HttpURLConnection
            conn.apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code == 404) {
                return@withContext Result.Unavailable("软件未找到")
            }
            if (code != 200) {
                return@withContext Result.Unavailable("检查失败 (HTTP $code)")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)

            val latestVersion = json.optString("latest_version", "").takeIf { it.isNotBlank() }
                ?: return@withContext Result.Unavailable("无法获取版本信息")

            val downloadUrl = json.optString("download_url", "").takeIf { it.isNotBlank() }
                ?: return@withContext Result.Unavailable("未找到下载链接")

            val latestVersionCode = json.optInt("latest_version_code", -1).takeIf { it >= 0 }
            val currentVersion = com.easytier.BuildConfig.VERSION_NAME
            val currentVersionCode = com.easytier.BuildConfig.VERSION_CODE
            Log.d(
                TAG,
                "最新版本: $latestVersion ($latestVersionCode), 当前版本: $currentVersion ($currentVersionCode), 下载地址: $downloadUrl"
            )

            val versionComparison = compareVersions(latestVersion, currentVersion)
            val isSameVersionCodeNewer =
                versionComparison == 0 && latestVersionCode != null && latestVersionCode > currentVersionCode
            if (versionComparison < 0 || (versionComparison == 0 && !isSameVersionCodeNewer)) {
                return@withContext Result.Unavailable("已是最新版本 ($currentVersion)")
            }

            val releaseNotes = buildString {
                val notes = sanitizeReleaseNotes(json.optString("release_notes", ""))
                if (notes.isNotEmpty()) {
                    append(notes)
                    append("\n\n")
                }
                if (!notes.contains(MANUAL_DOWNLOAD_NOTE)) {
                    append(MANUAL_DOWNLOAD_NOTE)
                }
            }

            Result.Available(
                UpdateInfo(
                    latestVersion = latestVersion,
                    latestVersionCode = latestVersionCode,
                    downloadUrl = downloadUrl,
                    releaseNotes = releaseNotes,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "版本检查失败", e)
            Result.Unavailable("网络错误: ${e.localizedMessage ?: "未知错误"}")
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo, onProgress: (Int) -> Unit = {}): String? =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.externalCacheDir, "updates")
                dir.mkdirs()
                val versionCodeSuffix = info.latestVersionCode?.let { "-$it" } ?: ""
                val file = File(dir, "kotier-v${info.latestVersion}${versionCodeSuffix}.apk")
                // 已下载过直接安装
                if (file.exists()) {
                    installApk(file)
                    return@withContext null
                }

                val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = DOWNLOAD_TIMEOUT
                conn.readTimeout = DOWNLOAD_TIMEOUT
                conn.instanceFollowRedirects = true

                Log.d(TAG, "下载开始: ${info.downloadUrl}")
                val total = conn.contentLength
                Log.d(TAG, "文件大小: $total bytes")
                val input = conn.inputStream
                val output = FileOutputStream(file)
                val buffer = ByteArray(8192)
                var downloaded = 0
                var lastReported = -1

                while (true) {
                    val bytes = input.read(buffer)
                    if (bytes == -1) break
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    val pct = if (total > 0) (downloaded.toLong() * 100 / total).toInt() else -1
                    if (pct != lastReported) {
                        onProgress(pct)
                        lastReported = pct
                    }
                }

                output.close()
                input.close()
                installApk(file)
                null
            } catch (e: Exception) {
                Log.e(TAG, "下载失败", e)
                "下载失败: ${e.localizedMessage ?: "未知错误"}"
            }
        }

    private fun installApk(file: File) {
        Log.d(TAG, "安装APK: ${file.absolutePath}, 大小: ${file.length()}")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        Log.d(TAG, "URI: $uri")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            Log.d(TAG, "安装意图已发送")
        } catch (e: Exception) {
            Log.e(TAG, "启动安装界面失败", e)
        }
    }

    private fun sanitizeReleaseNotes(value: String): String =
        value
            .lineSequence()
            .filterNot { line ->
                line.trim()
                    .removePrefix("-")
                    .trimStart()
                    .let { it.startsWith("安装包：") || it.startsWith("安装包:") }
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val a = parts1.getOrElse(i) { 0 }
            val b = parts2.getOrElse(i) { 0 }
            if (a != b) return a - b
        }
        return 0
    }
}
