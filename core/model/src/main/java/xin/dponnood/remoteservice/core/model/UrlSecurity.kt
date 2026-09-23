package xin.dponnood.remoteservice.core.model

import java.net.URI

/** Detects user info in valid URLs and credential-shaped authorities that fail URI parsing. */
fun urlContainsUserInfo(rawUrl: String?): Boolean {
    val value = rawUrl?.trim()?.takeIf(String::isNotEmpty) ?: return false
    val uri = runCatching { URI(value) }.getOrNull()
    val authorityContainsAt = authorityRange(value)?.let { (start, end) ->
        value.substring(start, end).contains('@')
    } ?: false
    return uri?.rawUserInfo != null || authorityContainsAt
}

/** Removes only URL user info while preserving the original path, query, and fragment bytes. */
fun stripUrlUserInfo(rawUrl: String?): String? {
    val value = rawUrl ?: return null
    val authority = authorityRange(value) ?: return value
    val uri = runCatching { URI(value.trim()) }.getOrNull()
    if (uri?.rawUserInfo == null && !value.substring(authority.first, authority.second).contains('@')) {
        return value
    }
    val separator = value.lastIndexOf('@', authority.second - 1)
    if (separator < authority.first) return value
    return value.removeRange(authority.first, separator + 1)
}

private fun authorityRange(value: String): Pair<Int, Int>? {
    val authorityStart = value.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return null
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: value.length
    return authorityStart to authorityEnd
}

/** Service endpoint credentials belong in the encrypted credential store, never the URL. */
fun ServiceConfig.hasEmbeddedUrlCredentials(): Boolean =
    urlContainsUserInfo(lanUrl) || urlContainsUserInfo(wanUrl)
