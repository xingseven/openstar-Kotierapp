package com.easytier.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class Contributor(
    val login: String,
    val avatarUrl: String,
    val htmlUrl: String,
    val contributions: Int
)

object GitHubService {
    private const val TAG = "GitHubService"
    private const val CONTRIBUTORS_URL = "https://api.github.com/repos/sevencnup/wotty-kotierapp/contributors"

    suspend fun fetchContributors(): List<Contributor> = withContext(Dispatchers.IO) {
        try {
            val url = URL(CONTRIBUTORS_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                connectTimeout = 10000
                readTimeout = 10000
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                parseContributors(response)
            } else {
                Log.e(TAG, "Failed to fetch contributors: HTTP $responseCode")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching contributors", e)
            emptyList()
        }
    }

    private fun parseContributors(jsonString: String): List<Contributor> {
        val contributors = mutableListOf<Contributor>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)
                contributors.add(
                    Contributor(
                        login = jsonObject.getString("login"),
                        avatarUrl = jsonObject.getString("avatar_url"),
                        htmlUrl = jsonObject.getString("html_url"),
                        contributions = jsonObject.getInt("contributions")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing contributors JSON", e)
        }
        return contributors
    }
}
