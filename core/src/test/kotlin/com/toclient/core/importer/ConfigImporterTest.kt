package com.toclient.core.importer

import com.toclient.core.connection.XrayConfigPlanner
import com.toclient.core.contract.SocksEndpoint
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConfigImporterTest {
    private val importer = ConfigImporter()
    private val json = Json
    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `imports vless reality without depending on external formats`() {
        val imported = importer.import(
            "vless://$uuid@vpn.example:443?encryption=none&type=grpc&security=reality" +
                "&sni=edge.example&serviceName=tunnel&fp=chrome&pbk=public-key&sid=abcd#Primary",
        )
        val outbound = proxyOutbound(imported.xrayJson)
        val stream = outbound.getValue("streamSettings").jsonObject

        assertEquals("Primary", imported.name)
        assertEquals("vless", imported.protocol)
        assertEquals("grpc", stream.getValue("network").jsonPrimitive.content)
        assertEquals(
            "public-key",
            stream.getValue("realitySettings").jsonObject
                .getValue("publicKey").jsonPrimitive.content,
        )
    }

    @Test
    fun `imports configuration with an unescaped unicode display name`() {
        val imported = importer.import(
            "vless://$uuid@vpn.example:443?encryption=none&type=ws" +
                "&security=tls&path=/sync#ERRF 🇨🇦 Mine",
        )

        assertEquals("ERRF 🇨🇦 Mine", imported.name)
        assertEquals("vless", imported.protocol)
    }

    @Test
    fun `preserves vless flow and reality client fields`() {
        val imported = importer.import(
            "vless://$uuid@vpn.example:443?encryption=none&type=tcp&security=reality" +
                "&flow=xtls-rprx-vision&sni=edge.example&fp=chrome&pbk=public-key" +
                "&sid=abcd&spx=%2Fprobe#Reality",
        )
        val outbound = proxyOutbound(imported.xrayJson)
        val user = outbound.getValue("settings").jsonObject
            .getValue("vnext").jsonArray.single().jsonObject
            .getValue("users").jsonArray.single().jsonObject
        val reality = outbound.getValue("streamSettings").jsonObject
            .getValue("realitySettings").jsonObject

        assertEquals("xtls-rprx-vision", user.getValue("flow").jsonPrimitive.content)
        assertEquals("/probe", reality.getValue("spiderX").jsonPrimitive.content)
    }

    @Test
    fun `preserves real xhttp extra through import and runtime planning`() {
        val imported = importer.import(
            "vless://$uuid@disi.netbazshop.ir:1001?encryption=none&security=tls" +
                "&type=xhttp&headerType=none&path=%2F&host=cafeariel.ir" +
                "&mode=stream-up&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%2C" +
                "%22xPaddingObfsMode%22%3Atrue%7D&sni=sslcert.okamii1.ir#Finland",
        )
        val importedExtra = proxyOutbound(imported.xrayJson)
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject
            .getValue("extra").jsonObject

        assertEquals("100-1000", importedExtra.getValue("xPaddingBytes").jsonPrimitive.content)
        assertEquals(true, importedExtra.getValue("xPaddingObfsMode").jsonPrimitive.content.toBoolean())

        val planned = XrayConfigPlanner().prepare(imported.xrayJson, SocksEndpoint(port = 11_080))
        val plannedExtra = proxyOutbound(planned)
            .getValue("streamSettings").jsonObject
            .getValue("xhttpSettings").jsonObject
            .getValue("extra").jsonObject
        assertEquals(importedExtra, plannedExtra)
    }

    @Test
    fun `preserves supported transport specific fields`() {
        val xhttp = proxyOutbound(importer.import(
            "vless://$uuid@vpn.example:443?encryption=none&type=xhttp&security=tls" +
                "&host=cdn.example&path=%2Ftunnel&mode=packet-up",
        ).xrayJson).getValue("streamSettings").jsonObject
        assertEquals("packet-up", xhttp.getValue("xhttpSettings").jsonObject
            .getValue("mode").jsonPrimitive.content)

        val grpc = proxyOutbound(importer.import(
            "vless://$uuid@vpn.example:443?encryption=none&type=grpc&path=%2Fservice",
        ).xrayJson).getValue("streamSettings").jsonObject
        assertEquals("service", grpc.getValue("grpcSettings").jsonObject
            .getValue("serviceName").jsonPrimitive.content)

        val tcp = proxyOutbound(importer.import(
            "vless://$uuid@vpn.example:80?encryption=none&type=tcp" +
                "&headerType=http&host=front.example&path=%2Findex",
        ).xrayJson).getValue("streamSettings").jsonObject
        assertEquals("http", tcp.getValue("tcpSettings").jsonObject
            .getValue("header").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `renders hysteria2 for the bundled xray schema`() {
        val outbound = proxyOutbound(importer.import(
            "hy2://secret@hy.example:443?sni=hy.example&insecure=1" +
                "&obfs=salamander&obfs-password=mask#HY2",
        ).xrayJson)
        val settings = outbound.getValue("settings").jsonObject
        val stream = outbound.getValue("streamSettings").jsonObject

        assertEquals("hysteria", outbound.getValue("protocol").jsonPrimitive.content)
        assertEquals("2", settings.getValue("version").jsonPrimitive.content)
        assertEquals("hysteria", stream.getValue("network").jsonPrimitive.content)
        assertEquals("secret", stream.getValue("hysteriaSettings").jsonObject
            .getValue("auth").jsonPrimitive.content)
        assertEquals("salamander", stream.getValue("finalmask").jsonObject
            .getValue("udp").jsonArray.single().jsonObject
            .getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `rejects transport combinations the bundled core does not support`() {
        listOf(
            "vless://$uuid@vpn.example:443?type=quic",
            "vless://$uuid@vpn.example:443?type=ws&security=reality",
            "vless://$uuid@vpn.example:443?type=xhttp&mode=invalid",
            "trojan://secret@vpn.example:443?flow=xtls-rprx-vision",
            "hy2://secret@hy.example:443?obfs=unknown",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>("Input should fail: $value") {
                importer.import(value)
            }
        }
    }

    @Test
    fun `imports vmess json and transport`() {
        val vmess = """
            {"v":"2","ps":"VMess Test","add":"vmess.example","port":"8443",
             "id":"$uuid","aid":"0","scy":"auto","net":"ws","tls":"tls",
             "sni":"tls.example","host":"cdn.example","path":"/socket"}
        """.trimIndent()
        val imported = importer.import("vmess://${base64(vmess)}")
        val outbound = proxyOutbound(imported.xrayJson)

        assertEquals("vmess", outbound.getValue("protocol").jsonPrimitive.content)
        assertEquals(
            "/socket",
            outbound.getValue("streamSettings").jsonObject
                .getValue("wsSettings").jsonObject
                .getValue("path").jsonPrimitive.content,
        )
    }

    @Test
    fun `imports supported authority based protocols`() {
        val values = listOf(
            Triple("trojan://secret@trojan.example:443?sni=tls.example#Trojan", "trojan", "trojan"),
            Triple("socks5://user:pass@socks.example:1080#SOCKS", "socks", "socks"),
            Triple("https://user:pass@proxy.example:8443#HTTP", "http", "http"),
            Triple("hysteria2://secret@hy.example:443?sni=hy.example", "hysteria2", "hysteria"),
        )

        values.forEach { (input, importedProtocol, runtimeProtocol) ->
            val imported = importer.import(input)
            assertEquals(importedProtocol, imported.protocol)
            assertEquals(runtimeProtocol, proxyOutbound(imported.xrayJson)
                .getValue("protocol").jsonPrimitive.content)
        }
    }

    @Test
    fun `imports both shadowsocks sip002 forms`() {
        val credentials = base64("aes-256-gcm:password")
        val first = importer.import("ss://$credentials@ss.example:8388#One")
        val whole = importer.import(
            "ss://${base64("aes-256-gcm:password@ss.example:8388")}#Two",
        )

        assertEquals("shadowsocks", first.protocol)
        assertEquals("shadowsocks", whole.protocol)
        assertEquals(first.fingerprint, whole.fingerprint)
        assertTrue(first.xrayJson.contains("\"method\":\"aes-256-gcm\""))
    }

    @Test
    fun `accepts bounded raw xray json and rejects malformed inputs`() {
        val raw = """{"outbounds":[{"protocol":"freedom"}]}"""
        assertEquals("xray-json", importer.import(raw).protocol)
        assertEquals(
            importer.import("""{"log":{"loglevel":"warning"},"outbounds":[{"protocol":"freedom"}]}""")
                .fingerprint,
            importer.import("""{"outbounds":[{"protocol":"freedom"}],"log":{"loglevel":"warning"}}""")
                .fingerprint,
        )

        listOf(
            "",
            "ftp://example.com/file",
            "vless://not-a-uuid@example.com:443",
            "trojan://@example.com:443",
            """{"inbounds":[]}""",
            "vmess://not-base64",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>("Input should fail: $value") {
                importer.import(value)
            }
        }
    }

    @Test
    fun `malformed fuzz corpus fails without escaping typed errors`() {
        repeat(128) { index ->
            val value = listOf(
                "vless://",
                "ss://",
                "vmess://",
                "trojan://",
                "hy2://",
                "{",
            )[index % 6] + "\u0000".repeat(index % 9) + index
            runCatching { importer.import(value) }.exceptionOrNull()?.let {
                assertTrue(it is IllegalArgumentException)
            }
        }
    }

    private fun proxyOutbound(value: String) =
        json.parseToJsonElement(value).jsonObject
            .getValue("outbounds").jsonArray.first().jsonObject

    private fun base64(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}