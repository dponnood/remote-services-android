package xin.dponnood.remoteservice.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xin.dponnood.remoteservice.core.model.ConnectionPolicy

class NetworkSettingsValidationTest {
    @Test
    fun rejectsUserInfoForInternalAndPublicEndpoints() {
        val internalError = validateNetworkDraft(
            NetworkDraft(
                lanUrl = "http://admin:secret@192.168.1.1/cgi-bin/luci",
                wanUrl = "https://router.example/cgi-bin/luci",
                trustedSsids = emptySet(),
                connectionPolicy = ConnectionPolicy.AUTO,
            ),
        )
        val publicError = validateNetworkDraft(
            NetworkDraft(
                lanUrl = "http://192.168.1.1/cgi-bin/luci",
                wanUrl = "https://admin:secret@router.example/cgi-bin/luci",
                trustedSsids = emptySet(),
                connectionPolicy = ConnectionPolicy.AUTO,
            ),
        )

        assertEquals("内网地址不能包含用户名或密码，请将登录信息保存在服务凭据中。", internalError)
        assertEquals("公网地址不能包含用户名或密码，请将登录信息保存在服务凭据中。", publicError)
    }

    @Test
    fun continuesToAllowEndpointPathsQueriesAndFragments() {
        val error = validateNetworkDraft(
            NetworkDraft(
                lanUrl = "http://192.168.1.1/cgi-bin/luci?next=%2Fadmin#overview",
                wanUrl = "https://router.example/admin?view=system#status",
                trustedSsids = setOf("Home WiFi"),
                connectionPolicy = ConnectionPolicy.AUTO,
            ),
        )

        assertNull(error)
    }
}
