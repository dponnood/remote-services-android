package xin.dponnood.remoteservice.feature.services

import java.net.URI

/** The only schemes supported by a service endpoint. */
enum class EndpointScheme(val value: String) {
    HTTP("http"),
    HTTPS("https"),
}

/**
 * The two values rendered by the endpoint editor.
 *
 * ServiceConfig and ServiceDraft deliberately continue to store a complete URL. This
 * presentation model only keeps the protocol selector and the user-editable authority/path
 * portion separate while the editor is on screen.
 */
data class EndpointFieldState(
    val scheme: EndpointScheme = EndpointScheme.HTTPS,
    val address: String = "",
)

private val endpointSchemePrefix = Regex("^(https?)://(.*)$", RegexOption.IGNORE_CASE)

/** Splits an existing full URL for display in the protocol selector and address field. */
fun splitEndpointUrl(rawUrl: String?, defaultScheme: EndpointScheme = EndpointScheme.HTTPS): EndpointFieldState {
    val value = rawUrl.orEmpty().trim()
    if (value.isBlank()) return EndpointFieldState(defaultScheme)

    val match = endpointSchemePrefix.matchEntire(value)
        ?: return EndpointFieldState(defaultScheme, value)
    val scheme = if (match.groupValues[1].equals("http", ignoreCase = true)) {
        EndpointScheme.HTTP
    } else {
        EndpointScheme.HTTPS
    }
    return EndpointFieldState(scheme = scheme, address = match.groupValues[2])
}

/** Joins editor values without changing the existing full-URL storage format. */
fun joinEndpointUrl(state: EndpointFieldState): String =
    if (state.address.isBlank()) "" else "${state.scheme.value}://${state.address}"

/**
 * Returns a user-facing validation message, or null for a valid/empty optional endpoint.
 *
 * HTTP is intentionally accepted for local-only services, but the UI calls it out as
 * unencrypted. Credentials must only be used with a trusted HTTPS endpoint by the caller.
 */
fun endpointUrlValidationError(rawUrl: String): String? {
    val value = rawUrl.trim()
    if (value.isBlank()) return null
    if (value.any { it.isWhitespace() || it.code in 0..0x1f || it.code == 0x7f }) {
        return "地址不能包含空格或控制字符"
    }
    if (Regex("^https?://[A-Za-z][A-Za-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
        return "主机部分不能包含另一个协议"
    }

    val uri = runCatching { URI(value) }.getOrNull()
        ?: return "请输入有效的 HTTP 或 HTTPS 地址"
    if (!uri.scheme.equals("http", ignoreCase = true) &&
        !uri.scheme.equals("https", ignoreCase = true)
    ) {
        return "地址协议必须是 HTTP 或 HTTPS"
    }
    if (uri.isOpaque || uri.rawAuthority.isNullOrBlank() || uri.host.isNullOrBlank()) {
        return "请输入有效的主机名、IP 或 IPv6 地址"
    }
    if (uri.userInfo != null) {
        return "地址不支持用户名或密码"
    }

    // A pasted second URL must not be interpreted as part of the host/path input.
    // Checking the parsed authority allows URL-like text in a query/path while rejecting
    // values such as https://http://router.example.
    if (uri.rawAuthority.orEmpty().contains("://")) {
        return "主机部分不能包含另一个协议"
    }
    return null
}

fun isValidEndpointUrl(rawUrl: String): Boolean = endpointUrlValidationError(rawUrl) == null
