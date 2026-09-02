package com.yugahashimoto.andcode.core.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class ForgeReference(
    val type: String,
    val number: Int,
    val title: String,
    val url: String,
    val forge: String,
)

enum class ForgeType {
    GITHUB,
    GITLAB,
    GITEA,
    FORGEJO,
    CODEBERG,
}

fun detectForgeType(remoteUrl: String): ForgeType =
    when {
        remoteUrl.contains("github.com") -> ForgeType.GITHUB
        remoteUrl.contains("gitlab.com") -> ForgeType.GITLAB
        remoteUrl.contains("codeberg.org") -> ForgeType.CODEBERG
        remoteUrl.contains("forgejo") -> ForgeType.FORGEJO
        remoteUrl.contains("gitea") -> ForgeType.GITEA
        else -> ForgeType.GITEA
    }

interface ForgeClient {
    suspend fun getPullRequests(
        repo: String,
        branch: String,
    ): List<ForgeReference>

    suspend fun getIssues(
        repo: String,
        query: String,
    ): List<ForgeReference>
}

class GitHubForgeClient(private val token: String?) : ForgeClient {
    private val client: OkHttpClient = OkHttpClient()
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override suspend fun getPullRequests(
        repo: String,
        branch: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "https://api.github.com/repos/$repo/pulls".toHttpUrl().newBuilder()
                            .addQueryParameter("head", branch)
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GitHubPull>>(body)
                    items.map { pull ->
                        ForgeReference(
                            type = "PR",
                            number = pull.number,
                            title = pull.title,
                            url = pull.htmlUrl,
                            forge = "github",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    override suspend fun getIssues(
        repo: String,
        query: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    buildRequest(
                        "https://api.github.com/search/issues".toHttpUrl().newBuilder()
                            .addQueryParameter("q", "repo:$repo $query")
                            .build().toString(),
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val result = json.decodeFromString<GitHubSearchResult>(body)
                    result.items.map { item ->
                        ForgeReference(
                            type = "Issue",
                            number = item.number,
                            title = item.title,
                            url = item.htmlUrl,
                            forge = "github",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .apply {
                token?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "Bearer $it")
                }
            }
            .build()

    @Serializable
    private data class GitHubPull(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class GitHubSearchResult(
        val items: List<GitHubIssue> = emptyList(),
    )

    @Serializable
    private data class GitHubIssue(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )
}

class GitLabForgeClient(private val token: String?) : ForgeClient {
    private val client: OkHttpClient = OkHttpClient()
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override suspend fun getPullRequests(
        repo: String,
        branch: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val encodedRepo = repo.replace("/", "%2F")
                val request =
                    buildRequest(
                        "https://gitlab.com/api/v4/projects/$encodedRepo/merge_requests?source_branch=$branch",
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GitLabMR>>(body)
                    items.map { mr ->
                        ForgeReference(
                            type = "MR",
                            number = mr.iid,
                            title = mr.title,
                            url = mr.webUrl,
                            forge = "gitlab",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    override suspend fun getIssues(
        repo: String,
        query: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val encodedRepo = repo.replace("/", "%2F")
                val request =
                    buildRequest(
                        "https://gitlab.com/api/v4/projects/$encodedRepo/issues?search=$query",
                    )
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GitLabIssue>>(body)
                    items.map { issue ->
                        ForgeReference(
                            type = "Issue",
                            number = issue.iid,
                            title = issue.title,
                            url = issue.webUrl,
                            forge = "gitlab",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .apply {
                token?.takeIf { it.isNotBlank() }?.let {
                    header("PRIVATE-TOKEN", it)
                }
            }
            .build()

    @Serializable
    private data class GitLabMR(
        val iid: Int = 0,
        val title: String = "",
        @SerialName("web_url") val webUrl: String = "",
    )

    @Serializable
    private data class GitLabIssue(
        val iid: Int = 0,
        val title: String = "",
        @SerialName("web_url") val webUrl: String = "",
    )
}

class GiteaForgeClient(private val baseUrl: String, private val token: String?) : ForgeClient {
    private val client: OkHttpClient = OkHttpClient()
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    override suspend fun getPullRequests(
        repo: String,
        branch: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("$baseUrl/api/v1/repos/$repo/pulls?head=$branch")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GiteaPull>>(body)
                    items.map { pull ->
                        ForgeReference(
                            type = "PR",
                            number = pull.number,
                            title = pull.title,
                            url = pull.htmlUrl,
                            forge = "gitea",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    override suspend fun getIssues(
        repo: String,
        query: String,
    ): List<ForgeReference> =
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest("$baseUrl/api/v1/repos/$repo/issues?q=$query&type=issues")
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext emptyList()
                    val body = response.body?.string().orEmpty()
                    val items = json.decodeFromString<List<GiteaIssue>>(body)
                    items.map { issue ->
                        ForgeReference(
                            type = "Issue",
                            number = issue.number,
                            title = issue.title,
                            url = issue.htmlUrl,
                            forge = "gitea",
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun buildRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .apply {
                token?.takeIf { it.isNotBlank() }?.let {
                    header("Authorization", "token $it")
                }
            }
            .build()

    @Serializable
    private data class GiteaPull(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class GiteaIssue(
        val number: Int = 0,
        val title: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )
}
