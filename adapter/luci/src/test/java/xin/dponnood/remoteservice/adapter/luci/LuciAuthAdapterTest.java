package xin.dponnood.remoteservice.adapter.luci;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.Test;

import xin.dponnood.remoteservice.core.model.ServiceConfig;
import xin.dponnood.remoteservice.core.model.ConnectionPolicy;
import xin.dponnood.remoteservice.core.model.ServiceType;
import xin.dponnood.remoteservice.core.security.CredentialStore;
import xin.dponnood.remoteservice.core.security.ServiceCredentials;

/**
 * JVM-only security contract tests. Keeping this class in src/test/java makes
 * AGP's Java test output explicit and avoids Kotlin-only test worker discovery
 * regressions in the adapter module.
 */
public final class LuciAuthAdapterTest {
    private static final class RecordingStore implements CredentialStore {
        private String requestedServiceId;
        private String requestedRouteKey;

        @Override
        public void put(String serviceId, String routeKey, ServiceCredentials credentials) {
            // No-op: these tests only verify the read gate.
        }

        @Override
        public ServiceCredentials get(String serviceId, String routeKey) {
            requestedServiceId = serviceId;
            requestedRouteKey = routeKey;
            return new ServiceCredentials("admin", "p\"ass");
        }

        @Override
        public void delete(String serviceId, String routeKey) {
            // No-op.
        }

        @Override
        public void clear() {
            // No-op.
        }

        private void reset() {
            requestedServiceId = null;
            requestedRouteKey = null;
        }
    }

    @Test
    public void loginScriptEscapesCredentialsAndHasNoBridge() {
        RecordingStore store = new RecordingStore();
        String script = new LuciAuthAdapter(store)
                .buildLoginScript(new ServiceCredentials("ad'min", "p\"ass"));
        assertTrue(script.contains("ad\\u0027min") || script.contains("ad'min"));
        assertTrue(script.contains("p\\\"ass"));
        assertTrue(script.contains("requestSubmit"));
        assertTrue(script.contains("HTMLFormElement.prototype.submit"));
        assertTrue(script.contains("__remoteServicesLoginSubmitted"));
        assertFalse(script.contains("addJavascriptInterface"));
    }

    @Test
    public void originCheckIncludesEffectivePort() {
        assertTrue(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://router.example", "https://router.example:443/cgi-bin/luci"));
        assertTrue(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://router.example:443", "https://router.example/cgi-bin/luci"));
        assertFalse(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://router.example", "https://router.example:8443/cgi-bin/luci"));
        assertTrue(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://192.168.1.1:8443", "https://192.168.1.1:8443/cgi-bin/luci"));
        assertFalse(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://192.168.1.1:8443", "https://192.168.1.1/cgi-bin/luci"));
        assertFalse(LuciAuthAdapter.Companion.isAllowedOrigin(
                "https://router.example", "http://router.example/cgi-bin/luci"));
    }

    @Test
    public void domDetectorRequiresExactOriginAndLuciServiceType() {
        LuciEndpoint endpoint = new LuciEndpoint(
                "router", "https://192.168.1.1:8443", "/cgi-bin/luci", ServiceType.ISTORE);
        String loginPage = "<form id=\"luci_login\"><input name=\"luci_username\"><input name=\"luci_password\"></form>";
        assertEquals(
                LuciSessionState.LOGIN_REQUIRED,
                LuciDomDetector.INSTANCE.detect(endpoint, "https://192.168.1.1:8443/cgi-bin/luci", null, loginPage));
        LuciEndpoint openClash = new LuciEndpoint(
                "openclash", "https://192.168.1.1:8443", "/cgi-bin/luci", ServiceType.OPENCLASH_PANEL);
        assertEquals(
                LuciSessionState.LOGIN_REQUIRED,
                LuciDomDetector.INSTANCE.detect(
                        openClash, "https://192.168.1.1:8443/cgi-bin/luci", null, loginPage));
        assertEquals(
                LuciSessionState.UNKNOWN,
                LuciDomDetector.INSTANCE.detect(endpoint, "https://192.168.1.1/cgi-bin/luci", null, loginPage));
        assertEquals(
                LuciSessionState.AUTHENTICATED,
                LuciDomDetector.INSTANCE.detect(
                        endpoint, "https://192.168.1.1:8443/admin/dashboard", null,
                        "<input name=\"username\"><input type=\"password\">") );
        LuciEndpoint generic = new LuciEndpoint(
                "router", "https://192.168.1.1:8443", "/cgi-bin/luci", ServiceType.GENERIC);
        assertEquals(
                LuciSessionState.UNKNOWN,
                LuciDomDetector.INSTANCE.detect(generic, "https://192.168.1.1:8443/cgi-bin/luci", null, loginPage));
    }

    @Test
    public void prepareReloginUsesCredentialStoreRouteKeyOnlyAfterDomChecks() {
        RecordingStore store = new RecordingStore();
        LuciAuthAdapter adapter = new LuciAuthAdapter(store);
        LuciEndpoint endpoint = new LuciEndpoint(
                "router", "https://router.example", "/cgi-bin/luci", ServiceType.LUCI);
        String loginPage = "<input name=\"username\"><input type=\"password\">";
        String script = adapter.prepareAutoRelogin(
                endpoint, "wan:https://router.example:443", "https://router.example/cgi-bin/luci",
                loginPage, null);
        assertTrue(script != null && script.contains("input[name=\"username\"]"));
        assertEquals("router", store.requestedServiceId);
        assertEquals("wan:https://router.example:443", store.requestedRouteKey);

        store.reset();
        assertEquals(
                null,
                adapter.prepareAutoRelogin(
                        endpoint, "wrong-port", "https://router.example:8443/cgi-bin/luci",
                        loginPage, null));
        assertEquals(null, store.requestedServiceId);
        assertEquals(null, store.requestedRouteKey);
    }

    @Test
    public void hookDecodesDomSnapshotWithoutInstallingBridge() {
        String encoded = "\"<form id=\\\"luci_login\\\"><input name=\\\"password\\\"></form>\"";
        assertEquals(
                "<form id=\"luci_login\"><input name=\"password\"></form>",
                LuciWebViewHook.Companion.decodeJavascriptResult(encoded));
        assertTrue(LuciWebViewHook.DOM_SNAPSHOT_SCRIPT.contains("document.documentElement"));
        assertFalse(LuciWebViewHook.DOM_SNAPSHOT_SCRIPT.contains("addJavascriptInterface"));
    }

    @Test
    public void endpointFactoryCarriesServiceType() {
        ServiceConfig service = new ServiceConfig(
                "istore", "iStore", null, null, null, 0, "service",
                Collections.<String>emptySet(), ServiceType.ISTORE, false, ConnectionPolicy.AUTO);
        assertEquals(
                ServiceType.ISTORE,
                LuciAuthAdapter.Companion.endpointFor(service, "https://router.example", "/cgi-bin/luci").getServiceType());
    }
}
