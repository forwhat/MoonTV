package com.moontv.android.local

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun isLocalMediaRequest(method: String, uri: String): Boolean =
    method == "GET" && uri in setOf("/api/m3u8", "/api/m3u8/proxy", "/api/image-proxy")

internal fun requestBodyContentType(value: String?): String? {
    val contentType = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val isJson = contentType.substringBefore(';').trim().let {
        it.equals("application/json", ignoreCase = true) || it.endsWith("+json", ignoreCase = true)
    }
    return if (isJson && !contentType.contains("charset=", ignoreCase = true)) {
        "$contentType; charset=UTF-8"
    } else {
        contentType
    }
}

/**
 * APK 内部的 MoonTV 运行时。
 *
 * 仅绑定 127.0.0.1：负责提供随 APK 打包的前端文件，并把原来依赖远端
 * Next.js 的少量读接口放到手机本地执行。它不会监听局域网地址。
 */
class LocalMoonServer(
    private val context: Context,
    private val serverMode: Boolean = false,
    private val upstreamBaseUrl: String? = null,
) : NanoHTTPD("127.0.0.1", if (serverMode) SERVER_PORT else LOCAL_PORT) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val searchPool = Executors.newFixedThreadPool(6)
    private val responsePool = Executors.newCachedThreadPool()
    private val responseCache = ConcurrentHashMap<String, CacheEntry>()
    private val syncPreferences by lazy { SyncPreferences.open(context) }
    private val config by lazy {
        context.assets.open("web/config.json").bufferedReader().use { JSONObject(it.readText()) }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:$listeningPort"

    fun startServer() {
        start(SOCKET_READ_TIMEOUT, false)
    }

    override fun stop() {
        searchPool.shutdownNow()
        responsePool.shutdownNow()
        super.stop()
    }

    override fun serve(session: IHTTPSession): Response = try {
        when {
            session.uri == "/runtime.js" -> runtimeScript()
            session.uri == "/api/native/runtime" -> nativeRuntime()
            isLocalMediaRequest(session.method.name, session.uri) -> proxyMedia(session)
            serverMode && session.uri.startsWith("/api/") -> proxyUpstream(session)
            session.uri == "/api/server-config" -> localServerConfig()
            session.uri == "/api/config/sources" -> json(sourceList())
            session.uri == "/api/config/custom_category" -> json(allCategories())
            session.uri == "/api/search/suggestions" -> json(JSONArray())
            session.uri == "/api/search" -> search(session)
            session.uri == "/api/admin/config" -> localAdminConfig()
            session.uri == "/api/admin/source" -> localAdminSource(session)
            session.uri == "/api/admin/category" -> localAdminCategory(session)
            session.uri == "/api/admin/config_file" -> localAdminConfigFile(session)
            session.uri == "/api/admin/site" -> localAdminSite(session)
            session.uri == "/api/admin/subscription" -> localAdminSubscription(session)
            session.uri == "/api/admin/reset" -> resetLocalAdmin()
            session.uri == "/api/local/sources/test" -> testLocalSource(session)
            session.uri == "/api/local/sources" -> localSources(session)
            session.uri == "/api/douban/categories" -> doubanCategories(session)
            session.uri == "/api/douban/recommends" -> doubanRecommends(session)
            session.uri == "/api/douban/collection" -> doubanCollection(session)
            session.uri == "/api/douban" -> doubanList(session)
            session.uri == "/api/detail" -> detail(session)
            session.uri == "/api/bangumi/calendar" -> proxyJson(
                "https://api.bgm.tv/calendar",
                fallback = JSONArray(),
            )
            session.uri.startsWith("/api/") -> json(
                JSONObject().put("error", "本地版本暂不支持此接口"),
                Response.Status.NOT_FOUND,
            )
            else -> serveAsset(session.uri)
        }
    } catch (error: Exception) {
        json(
            JSONObject().put("error", error.message ?: "本地请求失败"),
            Response.Status.INTERNAL_ERROR,
        )
    }

    private fun nativeRuntime(): Response = json(
        JSONObject()
            .put("mode", if (serverMode) "server" else "local")
            .put("serverUrl", upstreamBaseUrl.orEmpty()),
    )

    private fun runtimeScript(): Response {
        val runtime = JSONObject()
            .put("STORAGE_TYPE", if (serverMode) "server" else "localstorage")
            .put("ENABLE_REGISTER", false)
            .put("DOUBAN_PROXY_TYPE", "direct")
            .put("DOUBAN_PROXY", "")
            .put("DOUBAN_IMAGE_PROXY_TYPE", "server")
            .put("DOUBAN_IMAGE_PROXY", "")
            .put("BANGUMI_PROXY", "")
            .put("BANGUMI_IMAGE_PROXY", "")
            .put("DISABLE_YELLOW_FILTER", false)
            .put("DANMU_API_BASE_URL", "")
            .put("PLAYBACK_SAVE_INTERVAL", 5)
            .put("ANDROID_LOCAL_FIRST", true)
            .put("ANDROID_MODE", if (serverMode) "server" else "local")
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/javascript; charset=utf-8",
            "window.RUNTIME_CONFIG=${runtime};",
        ).apply { addHeader("Cache-Control", "no-store") }
    }

    private fun localServerConfig(): Response {
        val site = localSiteConfig()
        return json(
            JSONObject()
                .put("SiteName", site.optString("SiteName", "MoonTV"))
                .put("StorageType", "localstorage")
                .put("Mode", "local")
                .put("RuntimeConfig", publicRuntimeConfig(site)),
        )
    }

    private fun publicRuntimeConfig(site: JSONObject): JSONObject = JSONObject()
        .put("STORAGE_TYPE", "localstorage")
        .put("ENABLE_REGISTER", false)
        .put("DOUBAN_PROXY_TYPE", site.optString("DoubanProxyType", "direct"))
        .put("DOUBAN_PROXY", site.optString("DoubanProxy"))
        .put("DOUBAN_IMAGE_PROXY_TYPE", site.optString("DoubanImageProxyType", "server"))
        .put("DOUBAN_IMAGE_PROXY", site.optString("DoubanImageProxy"))
        .put("BANGUMI_PROXY", site.optString("BangumiProxy"))
        .put("BANGUMI_IMAGE_PROXY", site.optString("BangumiImageProxy"))
        .put("DISABLE_YELLOW_FILTER", site.optBoolean("DisableYellowFilter"))
        .put("DANMU_API_BASE_URL", site.optString("DanmakuApiBaseUrl"))
        .put("PLAYBACK_SAVE_INTERVAL", site.optInt("PlaybackSaveInterval", 5))

    private fun proxyUpstream(session: IHTTPSession): Response {
        val base = upstreamBaseUrl?.trimEnd('/')
            ?: return json(JSONObject().put("error", "未配置服务端地址"), Response.Status.BAD_REQUEST)
        val target = buildString {
            append(base)
            append(session.uri)
            session.queryParameterString?.takeIf(String::isNotBlank)?.let {
                append('?')
                append(it)
            }
        }
        val body = when (session.method) {
            Method.GET, Method.HEAD -> null
            else -> {
                requestBodyContentType(session.headers["content-type"])?.let {
                    // NanoHTTPD otherwise decodes JSON without an explicit charset as US-ASCII.
                    session.headers["content-type"] = it
                }
                val files = HashMap<String, String>()
                session.parseBody(files)
                files["postData"].orEmpty().toRequestBody(
                    session.headers["content-type"]?.toMediaTypeOrNull(),
                )
            }
        }
        val builder = Request.Builder().url(target).method(session.method.name, body)
        listOf("accept", "accept-language", "content-type", "cookie", "range").forEach { name ->
            session.headers[name]?.let { builder.header(name, it) }
        }
        builder.header("User-Agent", session.headers["user-agent"] ?: DESKTOP_USER_AGENT)
        val upstream = http.newCall(builder.build()).execute()
        val status = Response.Status.lookup(upstream.code) ?: Response.Status.INTERNAL_ERROR
        val responseBody = upstream.body
        val stream = object : FilterInputStream(
            responseBody?.byteStream() ?: ByteArrayInputStream(ByteArray(0)),
        ) {
            override fun close() {
                super.close()
                upstream.close()
            }
        }
        val contentLength = responseBody?.contentLength() ?: 0L
        val response = if (contentLength >= 0) {
            newFixedLengthResponse(
                status,
                upstream.header("Content-Type") ?: "application/octet-stream",
                stream,
                contentLength,
            )
        } else {
            newChunkedResponse(
                status,
                upstream.header("Content-Type") ?: "application/octet-stream",
                stream,
            )
        }
        listOf("Cache-Control", "Content-Disposition", "ETag", "Last-Modified").forEach { name ->
            upstream.header(name)?.let { response.addHeader(name, it) }
        }
        upstream.headers.values("Set-Cookie").lastOrNull()?.let {
            response.addHeader("Set-Cookie", rewriteCookieForLocalOrigin(it))
        }
        upstream.header("Location")?.let { location ->
            val rewritten = runCatching {
                val resolved = URI(base).resolve(location)
                if (resolved.toString().startsWith(base)) {
                    buildString {
                        append(resolved.rawPath.ifBlank { "/" })
                        resolved.rawQuery?.let { append('?').append(it) }
                        resolved.rawFragment?.let { append('#').append(it) }
                    }
                } else {
                    location
                }
            }.getOrDefault(location)
            response.addHeader("Location", rewritten)
        }
        return response
    }

    private fun rewriteCookieForLocalOrigin(value: String): String = value
        .replace(Regex("(?i);\\s*Domain=[^;]+"), "")
        .replace(Regex("(?i);\\s*Secure"), "")

    private fun localAdminConfig(): Response = json(
        JSONObject()
            .put("Role", "owner")
            .put(
                "Config",
                JSONObject()
                    .put("ConfigFile", localConfigFile().toString())
                    .put("SiteConfig", localSiteConfig())
                    .put(
                        "UserConfig",
                        JSONObject()
                            .put("AllowRegister", false)
                            .put("Users", JSONArray())
                            .put("Groups", JSONArray()),
                    )
                    .put("SourceConfig", allSources())
                    .put("CustomCategories", allCategories())
                    .put("SubscriptionConfig", localSubscription()),
            ),
    )

    private fun localSiteConfig(): JSONObject {
        syncPreferences.getString(SyncPreferences.KEY_LOCAL_SITE_CONFIG, null)?.let { stored ->
            runCatching { JSONObject(stored) }.getOrNull()?.let { return it }
        }
        return JSONObject()
            .put("SiteName", "MoonTV")
            .put("Announcement", "")
            .put("SearchDownstreamMaxPage", 1)
            .put("SiteInterfaceCacheTime", config.optInt("cache_time", 7200))
            .put("DoubanProxyType", "direct")
            .put("DoubanProxy", "")
            .put("DoubanImageProxyType", "server")
            .put("DoubanImageProxy", "")
            .put("BangumiProxy", "")
            .put("BangumiImageProxy", "")
            .put("DisableYellowFilter", false)
            .put("DanmakuApiBaseUrl", "")
            .put("TVBoxEnabled", false)
            .put("TVBoxPassword", "")
            .put("PlaybackSaveInterval", 5)
    }

    private fun localSubscription(): JSONObject {
        syncPreferences.getString(SyncPreferences.KEY_LOCAL_SUBSCRIPTION, null)?.let { stored ->
            runCatching { JSONObject(stored) }.getOrNull()?.let { return it }
        }
        return JSONObject()
            .put("subscriptionUrl", "")
            .put("autoUpdate", false)
            .put("updateInterval", 86400)
            .put("importMode", "merge")
    }

    private fun localConfigFile(): JSONObject {
        val apiSite = JSONObject()
        val sources = allSources()
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            apiSite.put(
                source.optString("key"),
                JSONObject()
                    .put("name", source.optString("name"))
                    .put("api", source.optString("api"))
                    .put("detail", source.optString("detail")),
            )
        }
        return JSONObject()
            .put("cache_time", localSiteConfig().optInt("SiteInterfaceCacheTime", 7200))
            .put("api_site", apiSite)
            .put("custom_category", allCategories())
    }

    private fun localAdminSource(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val body = requestJson(session)
        val action = body.optString("action")
        val sources = allSources().toObjectList().toMutableList()
        fun indexOf(key: String) = sources.indexOfFirst { it.optString("key") == key }
        when (action) {
            "add" -> {
                val source = normalizeSource(
                    JSONObject(body.toString()).put("from", "custom").put("disabled", false),
                ) ?: return badRequest("视频源配置无效")
                if (indexOf(source.getString("key")) >= 0) return badRequest("该源已存在")
                sources += source
            }
            "disable", "enable" -> {
                val index = indexOf(body.optString("key"))
                if (index < 0) return notFound("源不存在")
                sources[index].put("disabled", action == "disable")
            }
            "delete" -> {
                val index = indexOf(body.optString("key"))
                if (index < 0) return notFound("源不存在")
                if (sources[index].optString("from") == "config") return badRequest("该源不可删除")
                sources.removeAt(index)
            }
            "sort" -> {
                val order = body.optJSONArray("order") ?: return badRequest("排序列表格式错误")
                val byKey = sources.associateBy { it.optString("key") }.toMutableMap()
                val sorted = mutableListOf<JSONObject>()
                for (index in 0 until order.length()) byKey.remove(order.optString(index))?.let(sorted::add)
                sources.clear()
                sources.addAll(sorted)
                sources.addAll(byKey.values)
            }
            "batchDisable", "batchEnable", "batchDelete" -> {
                val keys = body.optJSONArray("keys")?.toStringSet() ?: return badRequest("缺少 keys 参数")
                if (action == "batchDelete") {
                    sources.removeAll { it.optString("key") in keys && it.optString("from") != "config" }
                } else {
                    sources.filter { it.optString("key") in keys }.forEach {
                        it.put("disabled", action == "batchDisable")
                    }
                }
            }
            else -> return badRequest("未知操作")
        }
        saveLocalSources(JSONArray(sources))
        return json(JSONObject().put("ok", true))
    }

    private fun localAdminCategory(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val body = requestJson(session)
        val action = body.optString("action")
        val categories = allCategories().toObjectList().toMutableList()
        fun indexOf(query: String, type: String) = categories.indexOfFirst {
            it.optString("query") == query && it.optString("type") == type
        }
        val query = body.optString("query").trim()
        val type = body.optString("type").trim()
        when (action) {
            "add" -> {
                if (query.isBlank() || type !in setOf("movie", "tv")) return badRequest("分类配置无效")
                if (indexOf(query, type) >= 0) return badRequest("该分类已存在")
                categories += JSONObject()
                    .put("name", body.optString("name", query))
                    .put("type", type)
                    .put("query", query)
                    .put("from", "custom")
                    .put("disabled", false)
            }
            "disable", "enable" -> {
                val index = indexOf(query, type)
                if (index < 0) return notFound("分类不存在")
                categories[index].put("disabled", action == "disable")
            }
            "delete" -> {
                val index = indexOf(query, type)
                if (index < 0) return notFound("分类不存在")
                if (categories[index].optString("from") == "config") return badRequest("该分类不可删除")
                categories.removeAt(index)
            }
            "sort" -> {
                val order = body.optJSONArray("order") ?: return badRequest("排序列表格式错误")
                val byKey = categories.associateBy { "${it.optString("query")}:${it.optString("type")}" }.toMutableMap()
                val sorted = mutableListOf<JSONObject>()
                for (index in 0 until order.length()) byKey.remove(order.optString(index))?.let(sorted::add)
                categories.clear()
                categories.addAll(sorted)
                categories.addAll(byKey.values)
            }
            else -> return badRequest("未知操作")
        }
        saveLocalCategories(JSONArray(categories))
        return json(JSONObject().put("ok", true))
    }

    private fun localAdminConfigFile(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val raw = requestJson(session).optString("configFile")
        val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return badRequest("配置文件格式错误")
        val apiSite = parsed.optJSONObject("api_site") ?: JSONObject()
        val sources = JSONArray()
        for (key in apiSite.keys()) {
            val site = apiSite.optJSONObject(key) ?: continue
            normalizeSource(
                JSONObject(site.toString()).put("key", key).put("from", "config"),
            )?.let(sources::put)
        }
        saveLocalSources(sources)
        parsed.optJSONArray("custom_category")?.let(::saveLocalCategories)
        val site = localSiteConfig().put("SiteInterfaceCacheTime", parsed.optInt("cache_time", 7200))
        saveLocalSite(site)
        return json(JSONObject().put("success", true).put("message", "配置文件更新成功"))
    }

    private fun localAdminSite(session: IHTTPSession): Response {
        if (session.method != Method.POST) return methodNotAllowed()
        val site = requestJson(session)
        if (site.optString("SiteName").isBlank()) return badRequest("站点名称不能为空")
        saveLocalSite(site)
        return json(JSONObject().put("ok", true))
    }

    private fun localAdminSubscription(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return json(JSONObject().put("subscriptionConfig", localSubscription()))
        }
        if (session.method != Method.POST) return methodNotAllowed()
        val body = requestJson(session)
        val action = body.optString("action")
        val subscription = localSubscription()
        return when (action) {
            "update" -> {
                listOf("subscriptionUrl", "autoUpdate", "updateInterval", "importMode").forEach { key ->
                    if (body.has(key)) subscription.put(key, body.get(key))
                }
                saveLocalSubscription(subscription)
                json(JSONObject().put("success", true))
            }
            "import" -> importLocalSubscription(
                body.optString("subscriptionUrl").ifBlank { subscription.optString("subscriptionUrl") },
                body.optString("importMode").ifBlank { subscription.optString("importMode", "merge") },
                subscription,
            )
            "check" -> {
                val now = System.currentTimeMillis() / 1000
                val last = subscription.optLong("lastUpdated", 0)
                val interval = subscription.optLong("updateInterval", 86400)
                if (
                    subscription.optBoolean("autoUpdate") &&
                    subscription.optString("subscriptionUrl").isNotBlank() &&
                    (last == 0L || now - last > interval)
                ) {
                    importLocalSubscription(
                        subscription.optString("subscriptionUrl"),
                        subscription.optString("importMode", "merge"),
                        subscription,
                    )
                } else {
                    json(JSONObject().put("success", true).put("updated", false))
                }
            }
            else -> badRequest("未知操作")
        }
    }

    private fun importLocalSubscription(url: String, mode: String, subscription: JSONObject): Response {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return badRequest("订阅地址无效")
        val request = Request.Builder().url(url).header("User-Agent", DESKTOP_USER_AGENT).build()
        val raw = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.string().orEmpty().trim()
        }
        val payload = runCatching { JSONObject(raw) }.getOrElse {
            JSONObject(decodeBase58(raw))
        }
        val apiSite = payload.optJSONObject("api_site") ?: JSONObject()
        val imported = JSONArray()
        for (key in apiSite.keys()) {
            val site = apiSite.optJSONObject(key) ?: continue
            normalizeSource(
                JSONObject(site.toString()).put("key", key).put("from", "config"),
            )?.let(imported::put)
        }
        val merged = if (mode == "overwrite") {
            imported
        } else {
            val byKey = allSources().toObjectList().associateBy { it.optString("key") }.toMutableMap()
            imported.toObjectList().forEach { byKey[it.optString("key")] = it }
            JSONArray(byKey.values)
        }
        saveLocalSources(merged)
        payload.optJSONArray("custom_category")?.let(::saveLocalCategories)
        subscription.put("subscriptionUrl", url)
        subscription.put("importMode", if (mode == "overwrite") "overwrite" else "merge")
        subscription.put("lastUpdated", System.currentTimeMillis() / 1000)
        saveLocalSubscription(subscription)
        return json(JSONObject().put("success", true).put("updated", true).put("imported", true))
    }

    private fun resetLocalAdmin(): Response {
        syncPreferences.edit()
            .remove(SyncPreferences.KEY_LOCAL_SOURCES)
            .remove(SyncPreferences.KEY_LOCAL_CATEGORIES)
            .remove(SyncPreferences.KEY_LOCAL_SITE_CONFIG)
            .remove(SyncPreferences.KEY_LOCAL_SUBSCRIPTION)
            .apply()
        responseCache.clear()
        return json(JSONObject().put("ok", true))
    }

    private fun saveLocalSources(sources: JSONArray) {
        syncPreferences.edit().putString(SyncPreferences.KEY_LOCAL_SOURCES, sources.toString()).apply()
        responseCache.clear()
    }

    private fun allCategories(): JSONArray {
        syncPreferences.getString(SyncPreferences.KEY_LOCAL_CATEGORIES, null)?.let { stored ->
            runCatching { JSONArray(stored) }.getOrNull()?.let { return it }
        }
        val result = JSONArray()
        val bundled = config.optJSONArray("custom_category") ?: JSONArray()
        for (index in 0 until bundled.length()) {
            val category = bundled.optJSONObject(index) ?: continue
            result.put(JSONObject(category.toString()).put("from", "config").put("disabled", false))
        }
        return result
    }

    private fun saveLocalCategories(categories: JSONArray) {
        syncPreferences.edit().putString(SyncPreferences.KEY_LOCAL_CATEGORIES, categories.toString()).apply()
    }

    private fun saveLocalSite(site: JSONObject) {
        syncPreferences.edit().putString(SyncPreferences.KEY_LOCAL_SITE_CONFIG, site.toString()).apply()
    }

    private fun saveLocalSubscription(subscription: JSONObject) {
        syncPreferences.edit().putString(SyncPreferences.KEY_LOCAL_SUBSCRIPTION, subscription.toString()).apply()
    }

    private fun decodeBase58(value: String): String {
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        var number = BigInteger.ZERO
        value.forEach { character ->
            val index = alphabet.indexOf(character)
            require(index >= 0) { "订阅内容不是有效的 JSON 或 Base58" }
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(index.toLong()))
        }
        var bytes = number.toByteArray()
        if (bytes.size > 1 && bytes.first() == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        val leadingZeros = value.takeWhile { it == '1' }.length
        return String(ByteArray(leadingZeros) + bytes, StandardCharsets.UTF_8)
    }

    private fun JSONArray.toObjectList(): List<JSONObject> =
        (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONArray.toStringSet(): Set<String> =
        (0 until length()).map(::optString).filter(String::isNotBlank).toSet()

    private fun badRequest(message: String) =
        json(JSONObject().put("error", message), Response.Status.BAD_REQUEST)

    private fun notFound(message: String) =
        json(JSONObject().put("error", message), Response.Status.NOT_FOUND)

    private fun methodNotAllowed() =
        json(JSONObject().put("error", "Method not allowed"), Response.Status.METHOD_NOT_ALLOWED)

    private fun allSources(): JSONArray {
        syncPreferences.getString(SyncPreferences.KEY_LOCAL_SOURCES, null)?.let { cached ->
            runCatching { JSONArray(cached) }.getOrNull()?.let { return it }
        }
        val sites = config.optJSONObject("api_site") ?: JSONObject()
        val result = JSONArray()
        for (key in sites.keys()) {
            val site = sites.optJSONObject(key) ?: continue
            result.put(
                JSONObject()
                    .put("key", key)
                    .put("name", site.optString("name", key))
                    .put("api", site.optString("api"))
                    .put("detail", site.optString("detail"))
                    .put("from", "config")
                    .put("disabled", false),
            )
        }
        return result
    }

    private fun sourceList(): JSONArray {
        val result = JSONArray()
        val sources = allSources()
        for (index in 0 until sources.length()) {
            val source = sources.optJSONObject(index) ?: continue
            if (!source.optBoolean("disabled") && source.optString("api").isNotBlank()) {
                result.put(source)
            }
        }
        return result
    }

    private fun search(session: IHTTPSession): Response {
        val query = session.parameter("q").trim()
        if (query.isBlank()) return ndjson(JSONObject().put("pageResults", JSONArray()).toString())

        val selected = session.parameter("sources")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val sources = (0 until sourceList().length())
            .map { sourceList().getJSONObject(it) }
            .filter { selected.isEmpty() || it.getString("key") in selected }

        val input = PipedInputStream(STREAM_BUFFER_SIZE)
        val output = PipedOutputStream(input)
        responsePool.execute {
            val completion = ExecutorCompletionService<SearchOutcome>(searchPool)
            val futures = sources.map { source ->
                completion.submit(Callable {
                    runCatching { searchSource(source, query) }
                        .fold(
                            onSuccess = { SearchOutcome(source, it, null) },
                            onFailure = { SearchOutcome(source, null, it.message ?: "请求失败") },
                        )
                })
            }
            try {
                output.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    val finished = mutableSetOf<String>()
                    val failures = JSONArray()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
                    while (finished.size < sources.size) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) break
                        val completed = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                        val outcome = runCatching { completed.get() }.getOrNull() ?: continue
                        val key = outcome.source.optString("key")
                        finished += key
                        if (outcome.result != null) {
                            writer.append(
                                JSONObject()
                                    .put("site", key)
                                    .put("pageResults", outcome.result)
                                    .toString(),
                            ).append('\n')
                            writer.flush()
                        } else {
                            failures.put(failure(outcome.source, outcome.error ?: "请求失败"))
                        }
                    }
                    sources.filter { it.optString("key") !in finished }.forEach {
                        failures.put(failure(it, "请求超时"))
                    }
                    if (failures.length() > 0) {
                        writer.append(JSONObject().put("failedSources", failures).toString()).append('\n')
                        writer.flush()
                    }
                }
            } catch (_: Exception) {
                // The browser may cancel a streaming request after receiving enough results.
            } finally {
                futures.forEach { it.cancel(true) }
                runCatching { output.close() }
            }
        }
        return newChunkedResponse(Response.Status.OK, "application/x-ndjson; charset=utf-8", input).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun searchSource(source: JSONObject, query: String): JSONArray {
        val base = source.getString("api")
        val cacheKey = "search:${source.optString("key")}:$base:$query"
        getCached(cacheKey)?.let { return JSONArray(it) }
        val separator = if ('?' in base) '&' else '?'
        val url = "$base${separator}ac=videolist&wd=${encode(query)}"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val root = JSONObject(response.body?.string().orEmpty())
            val items = root.optJSONArray("list") ?: return JSONArray()
            val results = JSONArray()
            for (index in 0 until items.length()) {
                mapVideo(items.optJSONObject(index), source)?.let(results::put)
            }
            putCached(cacheKey, results.toString(), SEARCH_CACHE_TTL_MS)
            return results
        }
    }

    private fun mapVideo(item: JSONObject?, source: JSONObject): JSONObject? {
        item ?: return null
        val id = item.optString("vod_id").trim()
        val title = item.optString("vod_name").trim()
        if (id.isBlank() || title.isBlank()) return null
        val (episodes, episodeTitles) = parseEpisodes(
            item.optString("vod_play_url"),
            item.optString("vod_content"),
        )
        val rawYear = item.opt("vod_year")?.toString().orEmpty()
        val year = YEAR.find(rawYear)?.value ?: "unknown"
        return JSONObject()
            .put("id", id)
            .put("title", title.replace(WHITESPACE, " "))
            .put("poster", item.optString("vod_pic"))
            .put("episodes", episodes)
            .put("episodes_titles", episodeTitles)
            .put("source", source.getString("key"))
            .put("source_name", source.optString("name", source.getString("key")))
            .put("class", item.optString("vod_class"))
            .put("year", year)
            .put("desc", stripHtml(item.optString("vod_content")))
            .put("type_name", item.optString("type_name"))
            .put("douban_id", item.optInt("vod_douban_id", 0))
    }

    private fun parseEpisodes(playUrl: String, fallback: String): Pair<JSONArray, JSONArray> {
        var bestUrls = emptyList<String>()
        var bestTitles = emptyList<String>()
        playUrl.split("$$$").forEach { group ->
            val urls = mutableListOf<String>()
            val titles = mutableListOf<String>()
            group.split('#').forEach { entry ->
                val separator = entry.indexOf('$')
                if (separator <= 0) return@forEach
                val url = entry.substring(separator + 1).trim()
                if (URL_M3U8.matches(url)) {
                    titles += entry.substring(0, separator).trim()
                    urls += url
                }
            }
            if (urls.size > bestUrls.size) {
                bestUrls = urls
                bestTitles = titles
            }
        }
        if (bestUrls.isEmpty()) {
            bestUrls = M3U8.findAll(fallback).map { it.value }.distinct().toList()
            bestTitles = bestUrls.indices.map { (it + 1).toString() }
        }
        return JSONArray(bestUrls) to JSONArray(bestTitles)
    }

    private fun localSources(session: IHTTPSession): Response {
        return when (session.method) {
            Method.GET -> json(allSources())
            Method.PUT, Method.POST -> {
            val payload = requestJson(session)
            val requested = payload.optJSONArray("sources")
                ?: return json(JSONObject().put("error", "缺少 sources"), Response.Status.BAD_REQUEST)
            val normalized = JSONArray()
            val keys = mutableSetOf<String>()
            for (index in 0 until requested.length()) {
                val source = normalizeSource(requested.optJSONObject(index))
                    ?: return json(
                        JSONObject().put("error", "第 ${index + 1} 个视频源配置无效"),
                        Response.Status.BAD_REQUEST,
                    )
                val key = source.getString("key")
                if (!keys.add(key)) {
                    return json(JSONObject().put("error", "视频源标识重复：$key"), Response.Status.BAD_REQUEST)
                }
                normalized.put(source)
            }
            saveLocalSources(normalized)
            json(normalized)
        }
            Method.DELETE -> {
                syncPreferences.edit().remove(SyncPreferences.KEY_LOCAL_SOURCES).apply()
                responseCache.clear()
                json(allSources())
            }
            else -> json(JSONObject().put("error", "Method not allowed"), Response.Status.METHOD_NOT_ALLOWED)
        }
    }

    private fun testLocalSource(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return json(JSONObject().put("error", "Method not allowed"), Response.Status.METHOD_NOT_ALLOWED)
        }
        val source = normalizeSource(requestJson(session).optJSONObject("source"))
            ?: return json(JSONObject().put("error", "视频源配置无效"), Response.Status.BAD_REQUEST)
        return runCatching { searchSource(source, "测试") }
            .fold(
                onSuccess = { json(JSONObject().put("ok", true).put("count", it.length())) },
                onFailure = {
                    json(
                        JSONObject().put("ok", false).put("error", it.message ?: "连接失败"),
                        Response.Status.INTERNAL_ERROR,
                    )
                },
            )
    }

    private fun normalizeSource(source: JSONObject?): JSONObject? {
        source ?: return null
        val key = source.optString("key").trim()
        val name = source.optString("name").trim()
        val api = source.optString("api").trim()
        if (!SOURCE_KEY.matches(key) || name.isBlank()) return null
        val uri = runCatching { URI(api) }.getOrNull() ?: return null
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        return JSONObject()
            .put("key", key)
            .put("name", name)
            .put("api", api)
            .put("detail", source.optString("detail").trim())
            .put("from", source.optString("from", "custom"))
            .put("disabled", source.optBoolean("disabled"))
    }

    private fun detail(session: IHTTPSession): Response {
        val id = session.parameter("id").trim()
        val fields = session.parameter("fields")
        return when (session.parameter("provider").ifBlank { "vod" }) {
            "douban" -> {
                if (!NUMERIC_ID.matches(id)) {
                    json(JSONObject().put("error", "无效的豆瓣 ID"), Response.Status.BAD_REQUEST)
                } else {
                    val data = getJson("https://m.douban.cmliussss.net/rexxar/api/v2/subject/$id")
                    json(JSONObject().put("desc", data.optString("intro")))
                }
            }
            "bangumi" -> {
                if (!NUMERIC_ID.matches(id)) {
                    json(JSONObject().put("error", "无效的 Bangumi ID"), Response.Status.BAD_REQUEST)
                } else {
                    val data = getJson("https://api.bgm.tv/v0/subjects/$id")
                    json(JSONObject().put("desc", data.optString("summary")))
                }
            }
            else -> {
                val sourceKey = session.parameter("source")
                val source = (0 until sourceList().length())
                    .map { sourceList().getJSONObject(it) }
                    .firstOrNull { it.optString("key") == sourceKey }
                    ?: return json(JSONObject().put("error", "无效的视频源"), Response.Status.BAD_REQUEST)
                if (id.isBlank()) {
                    return json(JSONObject().put("error", "缺少视频 ID"), Response.Status.BAD_REQUEST)
                }
                val result = detailSource(source, id)
                if (fields == "desc") json(JSONObject().put("desc", result.optString("desc"))) else json(result)
            }
        }
    }

    private fun detailSource(source: JSONObject, id: String): JSONObject {
        val base = source.getString("api")
        val cacheKey = "detail:${source.optString("key")}:$base:$id"
        getCached(cacheKey)?.let { return JSONObject(it) }
        val separator = if ('?' in base) '&' else '?'
        var item: JSONObject? = null
        for (action in listOf("videolist", "detail")) {
            val root = getJson("$base${separator}ac=$action&ids=${encode(id)}")
            item = root.optJSONArray("list")?.optJSONObject(0)
            if (item != null) break
        }
        val result = mapVideo(item, source) ?: error("视频详情不存在")
        putCached(cacheKey, result.toString(), DETAIL_CACHE_TTL_MS)
        return result
    }

    private fun doubanRecommends(session: IHTTPSession): Response {
        val kind = session.parameter("kind").ifBlank { "movie" }
        if (kind !in setOf("movie", "tv")) {
            return json(JSONObject().put("error", "无效的内容类型"), Response.Status.BAD_REQUEST)
        }
        val limit = session.parameter("limit").toIntOrNull()?.coerceIn(1, 100) ?: 20
        val start = session.parameter("start").toIntOrNull()?.coerceAtLeast(0) ?: 0
        fun normalized(name: String) = session.parameter(name).takeUnless { it == "all" }.orEmpty()
        val category = normalized("category")
        val format = normalized("format")
        val region = normalized("region")
        val tags = listOf(
            category,
            if (category.isBlank()) format else "",
            normalized("label"),
            region,
            normalized("year"),
            normalized("platform"),
        ).filter(String::isNotBlank)
        val selected = JSONObject().put("类型", category).apply {
            if (format.isNotBlank()) put("形式", format)
            if (region.isNotBlank()) put("地区", region)
        }
        val params = buildString {
            append("refresh=0&start=$start&count=$limit")
            append("&selected_categories=${encode(selected.toString())}")
            append("&uncollect=false&score_range=0%2C10&tags=${encode(tags.joinToString(","))}")
            normalized("sort").takeUnless { it == "T" }.orEmpty().takeIf(String::isNotBlank)?.let {
                append("&sort=${encode(it)}")
            }
        }
        val upstream = getJson("https://m.douban.cmliussss.net/rexxar/api/v2/$kind/recommend?$params")
        return json(doubanItems(upstream.optJSONArray("items") ?: JSONArray()))
    }

    private fun doubanCollection(session: IHTTPSession): Response {
        val id = session.parameter("id")
        if (id !in setOf("movie_top250", "movie_weekly_best")) {
            return json(JSONObject().put("error", "无效的榜单类型"), Response.Status.BAD_REQUEST)
        }
        val start = session.parameter("start").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val limit = session.parameter("limit").toIntOrNull()?.coerceIn(1, 100) ?: 25
        val upstream = getJson(
            "https://m.douban.cmliussss.net/rexxar/api/v2/subject_collection/$id/items" +
                "?start=$start&count=$limit",
        )
        val items = upstream.optJSONArray("subject_collection_items") ?: JSONArray()
        val list = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val pic = item.optJSONObject("pic")
            val rating = item.optJSONObject("rating")
            list.put(
                JSONObject()
                    .put("id", item.optString("id"))
                    .put("title", item.optString("title"))
                    .put(
                        "poster",
                        item.optString("cover_url").ifBlank {
                            pic?.optString("large").orEmpty().ifBlank { pic?.optString("normal").orEmpty() }
                        },
                    )
                    .put("rate", formatRating(rating?.optDouble("value", 0.0) ?: 0.0))
                    .put("year", YEAR.find(item.optString("card_subtitle"))?.value.orEmpty()),
            )
        }
        return json(JSONObject().put("code", 200).put("message", "获取成功").put("list", list))
    }

    private fun doubanItems(items: JSONArray): JSONObject {
        val list = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("type") !in setOf("movie", "tv")) continue
            val pic = item.optJSONObject("pic")
            val rating = item.optJSONObject("rating")
            list.put(
                JSONObject()
                    .put("id", item.optString("id"))
                    .put("title", item.optString("title"))
                    .put("poster", pic?.optString("normal").orEmpty().ifBlank { pic?.optString("large").orEmpty() })
                    .put("rate", formatRating(rating?.optDouble("value", 0.0) ?: 0.0))
                    .put("year", item.optString("year")),
            )
        }
        return JSONObject().put("code", 200).put("message", "获取成功").put("list", list)
    }

    private fun doubanCategories(session: IHTTPSession): Response {
        val kind = session.parameter("kind").ifBlank { "movie" }
        val category = session.parameter("category")
        val type = session.parameter("type")
        val limit = session.parameter("limit").toIntOrNull()?.coerceIn(1, 100) ?: 20
        val start = session.parameter("start").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val target = "https://m.douban.cmliussss.net/rexxar/api/v2/subject/recent_hot/" +
            "$kind?start=$start&limit=$limit&category=${encode(category)}&type=${encode(type)}"
        val upstream = getJson(target)
        val items = upstream.optJSONArray("items") ?: JSONArray()
        val list = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val pic = item.optJSONObject("pic")
            val rating = item.optJSONObject("rating")
            list.put(
                JSONObject()
                    .put("id", item.optString("id"))
                    .put("title", item.optString("title"))
                    .put("poster", pic?.optString("normal").orEmpty().ifBlank { pic?.optString("large").orEmpty() })
                    .put("rate", rating?.optDouble("value", 0.0)?.takeIf { it > 0 }?.let { "%.1f".format(it) }.orEmpty())
                    .put("year", YEAR.find(item.optString("card_subtitle"))?.value.orEmpty()),
            )
        }
        return json(JSONObject().put("code", 200).put("message", "获取成功").put("list", list))
    }

    private fun doubanList(session: IHTTPSession): Response {
        val type = session.parameter("type").ifBlank { "movie" }
        val tag = session.parameter("tag")
        val limit = session.parameter("pageSize").toIntOrNull()?.coerceIn(1, 100) ?: 20
        val start = session.parameter("pageStart").toIntOrNull()?.coerceAtLeast(0) ?: 0
        val target = "https://movie.douban.com/j/search_subjects?type=${encode(type)}&tag=${encode(tag)}" +
            "&sort=recommend&page_limit=$limit&page_start=$start"
        val upstream = getJson(target)
        val items = upstream.optJSONArray("subjects") ?: JSONArray()
        val list = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            list.put(
                JSONObject()
                    .put("id", item.optString("id"))
                    .put("title", item.optString("title"))
                    .put("poster", item.optString("cover"))
                    .put("rate", item.optString("rate"))
                    .put("year", ""),
            )
        }
        return json(JSONObject().put("code", 200).put("message", "获取成功").put("list", list))
    }

    private fun proxyJson(url: String, fallback: Any): Response = runCatching {
        json(getJsonValue(url))
    }.getOrElse { json(fallback) }

    private fun getJson(url: String): JSONObject = getJsonValue(url) as JSONObject

    private fun getJsonValue(url: String): Any {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", DESKTOP_USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty().trim()
            return if (body.startsWith("[")) JSONArray(body) else JSONObject(body)
        }
    }

    private fun proxyMedia(session: IHTTPSession): Response {
        val target = session.parameter("url")
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return json(JSONObject().put("error", "无效的视频地址"), Response.Status.BAD_REQUEST)
        }
        val builder = Request.Builder()
            .url(target)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Referer", "https://movie.douban.com/")
        session.headers["range"]?.let { builder.header("Range", it) }
        val upstream = http.newCall(builder.build()).execute()
        val contentType = upstream.header("Content-Type").orEmpty()
        if (
            target.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
            contentType.contains("mpegurl", ignoreCase = true)
        ) {
            upstream.use { response ->
                if (!response.isSuccessful) {
                    return newFixedLengthResponse(
                        Response.Status.lookup(response.code),
                        "text/plain; charset=utf-8",
                        "Upstream HTTP ${response.code}",
                    )
                }
                val manifest = response.body?.string().orEmpty()
                return newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.apple.mpegurl; charset=utf-8",
                    rewriteManifest(manifest, target),
                ).apply { addHeader("Cache-Control", "no-store") }
            }
        }
        val body = upstream.body
        val stream = body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
        val response = newChunkedResponse(
            if (upstream.code == 206) Response.Status.PARTIAL_CONTENT else Response.Status.lookup(upstream.code),
            upstream.header("Content-Type") ?: "application/octet-stream",
            stream,
        )
        listOf("Content-Range", "Accept-Ranges", "Content-Length").forEach { name ->
            upstream.header(name)?.let { response.addHeader(name, it) }
        }
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun rewriteManifest(manifest: String, sourceUrl: String): String {
        val base = URI(sourceUrl)
        return manifest.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trim()
            when {
                line.isBlank() -> rawLine
                line.startsWith("#") -> URI_ATTRIBUTE.replace(rawLine) { match ->
                    "URI=\"${localMediaUrl(base, match.groupValues[1])}\""
                }
                else -> localMediaUrl(base, line)
            }
        }
    }

    private fun localMediaUrl(base: URI, value: String): String {
        if (value.startsWith("data:") || value.startsWith("skd:")) return value
        return "/api/m3u8?url=${encode(base.resolve(value).toString())}"
    }

    private fun serveAsset(uri: String): Response {
        val requested = uri.substringBefore('?').trimStart('/')
        val path = if (requested.isBlank() || '.' !in requested.substringAfterLast('/')) {
            "index.html"
        } else {
            requested
        }
        if (".." in path) return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        val bytes = context.assets.open("web/$path").use { it.readBytes() }
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType(path),
            ByteArrayInputStream(bytes),
            bytes.size.toLong(),
        ).apply {
            addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
            addHeader("Pragma", "no-cache")
            addHeader("Expires", "0")
        }
    }

    private fun json(body: Any, status: Response.IStatus = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body.toString()).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun ndjson(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/x-ndjson; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun requestJson(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"].orEmpty().ifBlank { "{}" })
    }

    private fun getCached(key: String): String? {
        val entry = responseCache[key] ?: return null
        if (entry.expiresAt <= System.currentTimeMillis()) {
            responseCache.remove(key)
            return null
        }
        return entry.value
    }

    private fun putCached(key: String, value: String, ttlMs: Long) {
        responseCache[key] = CacheEntry(System.currentTimeMillis() + ttlMs, value)
    }

    private fun formatRating(value: Double): String =
        value.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.1f", it) }.orEmpty()

    private fun failure(source: JSONObject, message: String) = JSONObject()
        .put("name", source.optString("name", source.optString("key")))
        .put("key", source.optString("key"))
        .put("error", message)

    private fun IHTTPSession.parameter(name: String): String =
        parameters[name]?.firstOrNull()?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.orEmpty()

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun stripHtml(value: String): String = value
        .replace(HTML, " ")
        .replace("&nbsp;", " ")
        .replace(WHITESPACE, " ")
        .trim()

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> "application/octet-stream"
    }

    private data class CacheEntry(val expiresAt: Long, val value: String)

    private data class SearchOutcome(
        val source: JSONObject,
        val result: JSONArray?,
        val error: String?,
    )

    companion object {
        private const val LOCAL_PORT = 17890
        private const val SERVER_PORT = 17891
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L
        private const val DETAIL_CACHE_TTL_MS = 30 * 60 * 1000L
        private val YEAR = Regex("\\d{4}")
        private val NUMERIC_ID = Regex("\\d+")
        private val SOURCE_KEY = Regex("[^\\s/?#&=]{1,64}")
        private val WHITESPACE = Regex("\\s+")
        private val HTML = Regex("<[^>]+>")
        private val URI_ATTRIBUTE = Regex("URI=\"([^\"]+)\"")
        private val URL_M3U8 = Regex("https?://.+\\.m3u8(?:[?#].*)?", RegexOption.IGNORE_CASE)
        private val M3U8 = Regex("https?://[^\\\"'\\s]+?\\.m3u8(?:[?#][^\\\"'\\s]*)?", RegexOption.IGNORE_CASE)
    }
}
