package xin.dponnood.remoteservice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for the Compose/AndroidView ownership bug fixed in
 * MainActivity.ServiceWebScreen.
 *
 * The first DisposableEffect is created before AndroidView's factory assigns
 * the WebView.  Its cleanup must therefore use the instance captured when
 * that effect was created; reading the mutable state from onDispose destroys
 * the new view during the first recomposition.  This source-level guard is
 * intentional because the failure requires an Android WebView/Compose
 * runtime and cannot be exercised by the JVM-only test runner.
 */
class MainActivityWhiteScreenRegressionTest {
    @Test
    fun webViewCleanupUsesCapturedEffectInstance() {
        val source = File(
            "src/main/java/xin/dponnood/remoteservice/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("val effectWebView = webView"))
        assertTrue(source.contains("effectWebView?.destroy()"))
        assertFalse(
            "Cleanup must not read the mutable webView state directly",
            source.contains("webView?.destroy()"),
        )
        assertTrue(source.contains("X-LuCI-Login-Required"))
        assertTrue(source.contains("pageNotice"))
        assertTrue(source.contains("WEB_RENDER_PROCESS_GONE"))
    }

    @Test
    fun webPageAutoHidesTitleBarAndProvidesCollapsedMenu() {
        val source = File(
            "src/main/java/xin/dponnood/remoteservice/MainActivity.kt",
        ).readText()

        assertTrue(source.contains("WEB_PAGE_TOOLBAR_AUTO_HIDE_MS = 900L"))
        assertTrue(source.contains("visible = titleBarExpanded"))
        assertTrue(source.contains("visible = !titleBarExpanded && pageError == null"))
        assertTrue(source.contains("fun closeCurrentPage()"))
        assertTrue(source.contains("closeWindow(currentWindow.id)"))
        assertTrue(source.contains("text = \"⋮\""))
        assertTrue(source.contains("DropdownMenu("))
        assertTrue(source.contains("Text(\"返回上一页\")"))
        assertTrue(source.contains("Text(\"关闭页面\")"))
        assertTrue(source.contains("\"WEB_PAGE_CLOSED\""))
    }
}
