package xin.dponnood.remoteservice.core.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Paths exposed by the Mihomo external controller used by OpenClash.
 *
 * These are intentionally limited to read-only endpoints. Control operations
 * such as changing a proxy group, replacing a config, or closing a connection
 * do not belong to this client.
 */
object OpenClashApiPaths {
    const val VERSION = "/version"
    const val CONFIGS = "/configs"
    const val PROXIES = "/proxies"
    const val CONNECTIONS = "/connections"
    const val TRAFFIC = "/traffic"

    fun proxyDelay(proxyName: String, url: String, timeoutMs: Int): String {
        val encodedProxy = encodeComponent(proxyName)
        val encodedUrl = encodeComponent(url)
        return "$PROXIES/$encodedProxy/delay?url=$encodedUrl&timeout=$timeoutMs"
    }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

/** Version information returned by Mihomo's `/version` endpoint. */
data class OpenClashVersion(
    val version: String? = null,
    val meta: Boolean? = null,
    val premium: Boolean? = null,
)

/**
 * The stable, display-relevant subset of Mihomo's `/configs` response.
 * Unknown fields remain outside this model so a newer core can be consumed
 * without making the dashboard parser brittle.
 */
data class OpenClashConfig(
    val mode: String? = null,
    val logLevel: String? = null,
    val bindAddress: String? = null,
    val externalController: String? = null,
    val port: Int? = null,
    val socksPort: Int? = null,
    val redirPort: Int? = null,
    val mixedPort: Int? = null,
    val tproxyPort: Int? = null,
    val allowLan: Boolean? = null,
    val ipv6: Boolean? = null,
    val unifiedDelay: Boolean? = null,
    val tunEnabled: Boolean? = null,
)

/** One proxy or proxy group from the `/proxies` map. */
data class OpenClashProxy(
    val name: String,
    val type: String? = null,
    val now: String? = null,
    val all: List<String> = emptyList(),
    val udp: Boolean? = null,
    val xudp: Boolean? = null,
    val hidden: Boolean? = null,
)

/** The `/proxies` response, retaining map insertion order where provided. */
data class OpenClashProxySnapshot(
    val proxies: List<OpenClashProxy> = emptyList(),
) {
    val groups: List<OpenClashProxy>
        get() = proxies.filter { proxy ->
            proxy.all.isNotEmpty() || proxy.type.equals("Selector", ignoreCase = true) ||
                proxy.type.equals("URLTest", ignoreCase = true) ||
                proxy.type.equals("Fallback", ignoreCase = true) ||
                proxy.type.equals("LoadBalance", ignoreCase = true)
        }
}

/** Connection metadata nested under a Mihomo `/connections` item. */
data class OpenClashConnectionMetadata(
    val network: String? = null,
    val type: String? = null,
    val sourceIp: String? = null,
    val destinationIp: String? = null,
    val sourcePort: Int? = null,
    val destinationPort: Int? = null,
    val host: String? = null,
    val dnsMode: String? = null,
    val processPath: String? = null,
    val process: String? = null,
    val specialProxy: String? = null,
    val specialRules: String? = null,
)

/** One active connection from the `/connections` response. */
data class OpenClashConnection(
    val id: String? = null,
    val metadata: OpenClashConnectionMetadata = OpenClashConnectionMetadata(),
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val start: String? = null,
    val chains: List<String> = emptyList(),
    val rule: String? = null,
    val rulePayload: String? = null,
)

/** Totals and active entries from Mihomo's `/connections` endpoint. */
data class OpenClashConnectionSnapshot(
    val downloadTotalBytes: Long? = null,
    val uploadTotalBytes: Long? = null,
    val memoryBytes: Long? = null,
    val connections: List<OpenClashConnection> = emptyList(),
)

/** Instantaneous rates emitted by Mihomo's `/traffic` stream. */
data class OpenClashTraffic(
    val uploadBytesPerSecond: Long? = null,
    val downloadBytesPerSecond: Long? = null,
)

/** A single read-only aggregate useful to a status-card presenter. */
data class OpenClashStatusSnapshot(
    val version: OpenClashVersion,
    val config: OpenClashConfig,
    val proxies: OpenClashProxySnapshot,
    val connections: OpenClashConnectionSnapshot,
    val traffic: OpenClashTraffic,
)

/**
 * Read-only boundary for the OpenClash/Mihomo API.
 *
 * Implementations may use HTTP for REST responses and a WebSocket-backed
 * transport for `/traffic`; callers only receive typed data and cannot issue
 * control commands through this interface.
 */
interface OpenClashApiClient {
    suspend fun fetchVersion(): OpenClashVersion

    suspend fun fetchConfig(): OpenClashConfig

    suspend fun fetchProxies(): OpenClashProxySnapshot

    suspend fun fetchConnections(): OpenClashConnectionSnapshot

    suspend fun fetchTraffic(): OpenClashTraffic

    suspend fun fetchStatus(): OpenClashStatusSnapshot = OpenClashStatusSnapshot(
        version = fetchVersion(),
        config = fetchConfig(),
        proxies = fetchProxies(),
        connections = fetchConnections(),
        traffic = fetchTraffic(),
    )
}

/** Minimal transport seam; tests can provide payloads without a router. */
interface OpenClashApiTransport {
    suspend fun get(path: String): String
}

/** Narrow write boundary used only for an explicit proxy-node selection. */
interface OpenClashApiControlTransport : OpenClashApiTransport {
    suspend fun put(path: String, body: String)
}

/** Receives a single live `/traffic` frame from a WebSocket transport. */
interface OpenClashTrafficTransport {
    suspend fun nextTrafficFrame(): String
}

/** Error raised when an OpenClash HTTP response cannot be read. */
class OpenClashApiException(
    val path: String,
    val statusCode: Int? = null,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Parser plus transport adapter for the five read-only API responses.
 * Keeping parsing separate from I/O makes it safe to test with captured,
 * redacted fixtures and avoids coupling the feature layer to Android classes.
 */
class ReadOnlyOpenClashApiClient(
    private val transport: OpenClashApiTransport,
) : OpenClashApiClient {
    override suspend fun fetchVersion(): OpenClashVersion =
        OpenClashJsonParser.parseVersion(transport.get(OpenClashApiPaths.VERSION))

    override suspend fun fetchConfig(): OpenClashConfig =
        OpenClashJsonParser.parseConfig(transport.get(OpenClashApiPaths.CONFIGS))

    override suspend fun fetchProxies(): OpenClashProxySnapshot =
        OpenClashJsonParser.parseProxies(transport.get(OpenClashApiPaths.PROXIES))

    override suspend fun fetchConnections(): OpenClashConnectionSnapshot =
        OpenClashJsonParser.parseConnections(transport.get(OpenClashApiPaths.CONNECTIONS))

    override suspend fun fetchTraffic(): OpenClashTraffic {
        val liveTransport = transport as? OpenClashTrafficTransport
            ?: throw OpenClashApiException(
                path = OpenClashApiPaths.TRAFFIC,
                message = "Live traffic requires a WebSocket-capable OpenClash transport",
            )
        return OpenClashJsonParser.parseTraffic(liveTransport.nextTrafficFrame())
    }
}

/** Runtime-only Mihomo Selector operation; this never edits router config files. */
class OpenClashProxySelector(
    private val transport: OpenClashApiControlTransport,
) {
    suspend fun selectNode(groupName: String, nodeName: String) {
        require(groupName.isNotBlank()) { "Proxy group name must not be blank" }
        require(nodeName.isNotBlank()) { "Proxy node name must not be blank" }
        val encodedGroup = URLEncoder.encode(groupName, Charsets.UTF_8.name())
            .replace("+", "%20")
        transport.put(
            path = "${OpenClashApiPaths.PROXIES}/$encodedGroup",
            body = JSONObject().put("name", nodeName).toString(),
        )
    }
}

/** Explicit, per-node latency probe; unlike group health checks this does not clear automatic pins. */
class OpenClashProxyDelayTester(
    private val transport: OpenClashApiTransport,
) {
    suspend fun testNodeDelay(
        proxyName: String,
        url: String = DEFAULT_TEST_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Int {
        val normalizedUrl = url.trim()
        require(proxyName.isNotBlank()) { "Proxy name must not be blank" }
        require(isHttpUrl(normalizedUrl)) { "Delay test URL must be HTTP or HTTPS" }
        require(timeoutMs in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS) { "Delay test timeout is out of range" }
        val path = OpenClashApiPaths.proxyDelay(proxyName, normalizedUrl, timeoutMs)
        return OpenClashJsonParser.parseProxyDelay(transport.get(path))
    }

    private fun isHttpUrl(value: String): Boolean {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
        return uri.scheme?.lowercase(Locale.US) in setOf("http", "https") && !uri.host.isNullOrBlank()
    }

    companion object {
        const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_TIMEOUT_MS = 5_000
        private const val MIN_TIMEOUT_MS = 500
        private const val MAX_TIMEOUT_MS = 30_000
    }
}

/**
 * Small URLConnection transport for REST endpoints. The authorization secret
 * is supplied at request time and is never logged, persisted, or placed in a
 * URL. A WebSocket transport can implement [OpenClashApiTransport] separately
 * when live `/traffic` streaming is wired into the app.
 */
class HttpOpenClashApiTransport(
    baseUrl: String,
    private val secretProvider: () -> String? = { null },
    private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
) : OpenClashApiControlTransport {
    private val baseUrl: String = normalizeBaseUrl(baseUrl)

    override suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val requestUrl = buildRequestUrl(baseUrl, path)
        val connection = runCatching { URL(requestUrl).openConnection() as HttpURLConnection }
            .getOrElse { failure ->
                throw OpenClashApiException(
                    path = path,
                    message = "OpenClash endpoint is invalid",
                    cause = failure,
                )
            }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            secretProvider()?.trim()?.takeIf(String::isNotEmpty)?.let { secret ->
                connection.setRequestProperty("Authorization", "Bearer $secret")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenClashApiException(
                    path = path,
                    statusCode = status,
                    message = "OpenClash endpoint returned HTTP $status",
                )
            }
            readBounded(connection, path)
        } catch (failure: OpenClashApiException) {
            throw failure
        } catch (failure: Exception) {
            throw OpenClashApiException(
                path = path,
                statusCode = runCatching { connection.responseCode }.getOrNull(),
                message = "OpenClash endpoint could not be read",
                cause = failure,
            )
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun put(path: String, body: String) = withContext(Dispatchers.IO) {
        val requestUrl = buildRequestUrl(baseUrl, path)
        val connection = runCatching { URL(requestUrl).openConnection() as HttpURLConnection }
            .getOrElse { failure ->
                throw OpenClashApiException(
                    path = path,
                    message = "OpenClash endpoint is invalid",
                    cause = failure,
                )
            }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.requestMethod = "PUT"
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Cache-Control", "no-cache")
            secretProvider()?.trim()?.takeIf(String::isNotEmpty)?.let { secret ->
                connection.setRequestProperty("Authorization", "Bearer $secret")
            }
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenClashApiException(
                    path = path,
                    statusCode = status,
                    message = "OpenClash node selection returned HTTP $status",
                )
            }
        } catch (failure: OpenClashApiException) {
            throw failure
        } catch (failure: Exception) {
            throw OpenClashApiException(
                path = path,
                statusCode = runCatching { connection.responseCode }.getOrNull(),
                message = "OpenClash node selection could not be sent",
                cause = failure,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(connection: HttpURLConnection, path: String): String {
        return connection.inputStream.use { input ->
            readUtf8Bounded(input, maxBytes = MAX_RESPONSE_BYTES, path = path)
        }
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 4_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

        private fun normalizeBaseUrl(value: String): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
            require(uri != null && uri.scheme?.lowercase(Locale.US) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null
            ) { "OpenClash base URL must be an HTTP(S) origin without credentials, query, or fragment" }
            return trimmed
        }

        private fun buildRequestUrl(base: String, path: String): String {
            val normalizedPath = "/${path.trim().trimStart('/')}"
            return base + normalizedPath
        }
    }
}

/** Reads no more than [maxBytes] plus one overflow-detection byte, then decodes UTF-8. */
internal fun readUtf8Bounded(input: InputStream, maxBytes: Int, path: String): String {
    require(maxBytes > 0) { "Response byte limit must be positive" }
    val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    val buffer = ByteArray(minOf(maxBytes, 8 * 1024))
    var totalBytes = 0
    while (true) {
        val remaining = maxBytes - totalBytes
        val count = input.read(buffer, 0, minOf(buffer.size, remaining + 1))
        if (count < 0) break
        if (count > remaining) {
            throw OpenClashApiException(path = path, message = "OpenClash response is too large")
        }
        output.write(buffer, 0, count)
        totalBytes += count
    }
    return output.toByteArray().toString(Charsets.UTF_8)
}

/** JSON parser for Mihomo's version, config, proxy, connection and traffic payloads. */
object OpenClashJsonParser {
    fun parseVersion(payload: String): OpenClashVersion {
        val root = payload.toJsonObject(OpenClashApiPaths.VERSION)
        return OpenClashVersion(
            version = root.stringOrNull("version"),
            meta = root.booleanOrNull("meta"),
            premium = root.booleanOrNull("premium"),
        )
    }

    fun parseConfig(payload: String): OpenClashConfig {
        val root = payload.toJsonObject(OpenClashApiPaths.CONFIGS)
        val tun = root.objectOrNull("tun")
        return OpenClashConfig(
            mode = root.stringOrNull("mode"),
            logLevel = root.stringOrNull("log-level", "logLevel"),
            bindAddress = root.stringOrNull("bind-address", "bindAddress"),
            externalController = root.stringOrNull("external-controller", "externalController"),
            port = root.intOrNull("port"),
            socksPort = root.intOrNull("socks-port", "socksPort"),
            redirPort = root.intOrNull("redir-port", "redirPort"),
            mixedPort = root.intOrNull("mixed-port", "mixedPort"),
            tproxyPort = root.intOrNull("tproxy-port", "tproxyPort"),
            allowLan = root.booleanOrNull("allow-lan", "allowLan"),
            ipv6 = root.booleanOrNull("ipv6"),
            unifiedDelay = root.booleanOrNull("unified-delay", "unifiedDelay"),
            tunEnabled = tun?.booleanOrNull("enable", "enabled"),
        )
    }

    fun parseProxies(payload: String): OpenClashProxySnapshot {
        val root = payload.toJsonObject(OpenClashApiPaths.PROXIES)
        val proxyObject = root.objectOrNull("proxies") ?: JSONObject()
        val proxies = proxyObject.keys().asSequence().mapNotNull { name ->
            val value = proxyObject.opt(name) as? JSONObject ?: return@mapNotNull null
            OpenClashProxy(
                name = name,
                type = value.stringOrNull("type"),
                now = value.stringOrNull("now"),
                all = value.stringList("all"),
                udp = value.booleanOrNull("udp"),
                xudp = value.booleanOrNull("xudp"),
                hidden = value.booleanOrNull("hidden"),
            )
        }.toList()
        return OpenClashProxySnapshot(proxies)
    }

    fun parseProxyDelay(payload: String): Int {
        val path = "${OpenClashApiPaths.PROXIES}/{proxy}/delay"
        val root = payload.toJsonObject(path)
        val raw = root.opt("delay")
        val delay = when (raw) {
            is Number -> raw.toDouble().takeIf { it.isFinite() && it % 1.0 == 0.0 }
                ?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }?.takeIf { it in 0..MAX_DELAY_MS }
            ?: throw OpenClashApiException(path, message = "OpenClash delay response is invalid")
        return delay
    }

    fun parseConnections(payload: String): OpenClashConnectionSnapshot {
        val root = payload.toJsonObject(OpenClashApiPaths.CONNECTIONS)
        val entries = root.arrayOrNull("connections") ?: JSONArray()
        val connections = (0 until entries.length()).mapNotNull { index ->
            val value = entries.opt(index) as? JSONObject ?: return@mapNotNull null
            val metadata = value.objectOrNull("metadata") ?: JSONObject()
            OpenClashConnection(
                id = value.stringOrNull("id"),
                metadata = OpenClashConnectionMetadata(
                    network = metadata.stringOrNull("network"),
                    type = metadata.stringOrNull("type"),
                    sourceIp = metadata.stringOrNull("sourceIP", "sourceIp"),
                    destinationIp = metadata.stringOrNull("destinationIP", "destinationIp"),
                    sourcePort = metadata.intOrNull("sourcePort"),
                    destinationPort = metadata.intOrNull("destinationPort"),
                    host = metadata.stringOrNull("host"),
                    dnsMode = metadata.stringOrNull("dnsMode"),
                    processPath = metadata.stringOrNull("processPath"),
                    process = metadata.stringOrNull("process"),
                    specialProxy = metadata.stringOrNull("specialProxy"),
                    specialRules = metadata.stringOrNull("specialRules"),
                ),
                uploadBytes = value.longOrNull("upload", "uploadBytes"),
                downloadBytes = value.longOrNull("download", "downloadBytes"),
                start = value.stringOrNull("start"),
                chains = value.stringList("chains"),
                rule = value.stringOrNull("rule"),
                rulePayload = value.stringOrNull("rulePayload", "rule_payload"),
            )
        }
        return OpenClashConnectionSnapshot(
            downloadTotalBytes = root.longOrNull("downloadTotal", "download_total"),
            uploadTotalBytes = root.longOrNull("uploadTotal", "upload_total"),
            memoryBytes = root.longOrNull("memory", "memoryBytes", "memory_bytes"),
            connections = connections,
        )
    }

    fun parseTraffic(payload: String): OpenClashTraffic {
        val root = payload.toJsonObject(OpenClashApiPaths.TRAFFIC)
        return OpenClashTraffic(
            uploadBytesPerSecond = root.longOrNull(
                "up",
                "upload",
                "uploadBytesPerSecond",
                "upBytesPerSecond",
            ),
            downloadBytesPerSecond = root.longOrNull(
                "down",
                "download",
                "downloadBytesPerSecond",
                "downBytesPerSecond",
            ),
        )
    }

    private fun String.toJsonObject(path: String): JSONObject = runCatching { JSONObject(this) }
        .getOrElse { failure ->
            throw OpenClashApiException(
                path = path,
                message = "OpenClash response is not a JSON object",
                cause = failure,
            )
        }

    private fun JSONObject.objectOrNull(name: String): JSONObject? = opt(name) as? JSONObject

    private fun JSONObject.arrayOrNull(name: String): JSONArray? = opt(name) as? JSONArray

    private fun JSONObject.stringOrNull(vararg names: String): String? = names.asSequence()
        .mapNotNull { name ->
            val raw = opt(name)
            when (raw) {
                null, JSONObject.NULL -> null
                is String -> raw.trim().takeIf(String::isNotEmpty)
                else -> raw.toString().trim().takeIf(String::isNotEmpty)
            }
        }
        .firstOrNull()

    private fun JSONObject.booleanOrNull(vararg names: String): Boolean? = names.asSequence()
        .mapNotNull { name ->
            when (val raw = opt(name)) {
                is Boolean -> raw
                is Number -> when (raw.toInt()) {
                    0 -> false
                    1 -> true
                    else -> null
                }
                is String -> when (raw.trim().lowercase(Locale.US)) {
                    "true", "yes", "on", "1" -> true
                    "false", "no", "off", "0" -> false
                    else -> null
                }
                else -> null
            }
        }
        .firstOrNull()

    private fun JSONObject.intOrNull(vararg names: String): Int? = longOrNull(*names)
        ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
        ?.toInt()

    private fun JSONObject.longOrNull(vararg names: String): Long? = names.asSequence()
        .mapNotNull { name -> numberToLong(opt(name)) }
        .firstOrNull()

    private fun numberToLong(raw: Any?): Long? = when (raw) {
        null, JSONObject.NULL -> null
        is Byte -> raw.toLong()
        is Short -> raw.toLong()
        is Int -> raw.toLong()
        is Long -> raw
        is Float -> raw.takeIf(Float::isFinite)?.toLong()
        is Double -> raw.takeIf(Double::isFinite)?.toLong()
        is Number -> raw.toDouble().takeIf(Double::isFinite)?.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }

    private const val MAX_DELAY_MS = 65_535

    private fun JSONObject.stringList(name: String): List<String> {
        val array = opt(name) as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val value = array.opt(index)
            when (value) {
                null, JSONObject.NULL -> null
                else -> value.toString().trim().takeIf(String::isNotEmpty)
            }
        }
    }
}
