package xin.dponnood.remoteservice.feature.web

import android.webkit.WebView
import xin.dponnood.remoteservice.core.model.ServiceType

/**
 * Compatibility repair for LuCI/iStore shells which occasionally render a
 * fully populated DOM with html/body/#app/#main computed to zero height in
 * Android WebView. The script never reads cookies, form values or page text.
 */
object WebViewLayoutCompatibility {
    /**
     * Runs only for router service families. The JavaScript checks the actual
     * window height and changes layout only when the document root and its
     * primary content container are collapsed to zero.
     */
    val ROOT_HEIGHT_REPAIR_SCRIPT: String = """
        (function() {
          try {
            var root = document.documentElement;
            var body = document.body;
            var apply = function() {
              var currentRoot = document.documentElement;
              var currentBody = document.body;
              // Zashboard's shell can keep html/body/#app at the viewport
              // height while its flex child #app-content resolves
              // `var(--app-height, 100dvh)` to 0px in some Android WebView
              // providers. Inspect all known primary containers instead of
              // stopping at the first non-collapsed one.
              var contentNodes = Array.prototype.slice.call(
                document.querySelectorAll('#app-content,#main,#app')
              );
              var viewportHeight = Math.round(window.innerHeight || (currentRoot && currentRoot.clientHeight) || 0);
              if (!currentRoot || !currentBody || viewportHeight <= 0) return false;

              var heightOf = function(node) {
                if (!node) return 0;
                var value = parseFloat(window.getComputedStyle(node).height);
                return isFinite(value) ? value : 0;
              };
              var rootCollapsed = heightOf(currentRoot) <= 0 && heightOf(currentBody) <= 0;
              var contentCollapsed = contentNodes.some(function(node) { return heightOf(node) <= 0; });
              if (!rootCollapsed && !contentCollapsed) return false;

              var style = document.getElementById('remote-services-layout-compat');
              if (!style) {
                style = document.createElement('style');
                style.id = 'remote-services-layout-compat';
                (document.head || currentRoot).appendChild(style);
              }
              var height = viewportHeight + 'px';
              style.textContent =
                'html,body{height:' + height + '!important;min-height:' + height + '!important;}' +
                '#app,#app-content,#main{height:' + height + '!important;min-height:' + height + '!important;}';
              return true;
            };

            var appliedNow = apply();
            // LuCI/iStore can replace the shell after authentication. Retry
            // after the shell's common layout points, without reading page
            // text, cookies, form values or credentials.
            window.setTimeout(apply, 250);
            window.setTimeout(apply, 1000);
            if (!window.__remoteServicesLayoutCompatInstalled) {
              window.__remoteServicesLayoutCompatInstalled = true;
              window.__remoteServicesLayoutCompatApply = apply;
              window.addEventListener('resize', function() {
                window.__remoteServicesLayoutCompatApply();
              }, false);
            }
            return appliedNow;
          } catch (ignored) {
            return false;
          }
        })();
    """.trimIndent()

    fun isRouterService(serviceType: ServiceType): Boolean =
        serviceType == ServiceType.LUCI ||
            serviceType == ServiceType.ISTORE ||
            serviceType == ServiceType.OPENCLASH

    /** Pure policy helper for JVM tests and future hosts. */
    fun needsRootHeightRepair(
        serviceType: ServiceType,
        viewportHeight: Int,
        htmlHeight: Int,
        bodyHeight: Int,
        contentHeight: Int?,
    ): Boolean {
        if (!isRouterService(serviceType) || viewportHeight <= 0) return false
        val rootCollapsed = htmlHeight <= 0 && bodyHeight <= 0
        val contentCollapsed = contentHeight != null && contentHeight <= 0
        return rootCollapsed || contentCollapsed
    }

    fun applyIfNeeded(
        webView: WebView,
        serviceType: ServiceType,
        onComplete: (applied: Boolean) -> Unit = {},
    ) {
        if (!isRouterService(serviceType)) {
            onComplete(false)
            return
        }
        webView.evaluateJavascript(ROOT_HEIGHT_REPAIR_SCRIPT) { result ->
            onComplete(result?.trim() == "true")
        }
    }
}
