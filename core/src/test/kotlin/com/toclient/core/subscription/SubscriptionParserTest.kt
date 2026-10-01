package com.toclient.core.subscription

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubscriptionParserTest {
    private val parser = SubscriptionParser()
    private val uuid = "11111111-2222-3333-4444-555555555555"

    @Test
    fun `parses plain and base64 lists and removes duplicates`() {
        val list = """
            # comment
            vless://$uuid@one.example:443#One
            vless://$uuid@one.example:443#Duplicate
            trojan://secret@two.example:443#Two
        """.trimIndent()

        assertEquals(2, parser.parse(list.toByteArray()).size)
        val encoded = Base64.getEncoder().encodeToString(list.toByteArray())
        assertEquals(2, parser.parse(encoded.toByteArray()).size)
    }

    @Test
    fun `parses base64 list with unescaped unicode display names`() {
        val list = """
            vless://$uuid@one.example:443?encryption=none&type=ws#ERRF 🇨🇦 Mine
            vless://11111111-2222-3333-4444-555555555556@two.example:443?encryption=none&type=ws#ERRF 🇩🇪 Mine
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(list.toByteArray())

        val imported = parser.parse(encoded.toByteArray())

        assertEquals(2, imported.size)
        assertEquals(listOf("ERRF 🇨🇦 Mine", "ERRF 🇩🇪 Mine"), imported.map { it.name })
    }

    @Test
    fun `parses json array and ignores unsupported entries`() {
        val value = """
            ["ftp://invalid","socks5://user:pass@host.example:1080#Proxy"]
        """.trimIndent()

        val imported = parser.parse(value.toByteArray())
        assertEquals(1, imported.size)
        assertEquals("socks", imported.single().protocol)
    }

    @Test
    fun `parses pretty printed raw xray json as one configuration`() {
        val value = """
            {
              "log": {"loglevel": "warning"},
              "outbounds": [
                {"protocol": "freedom"}
              ]
            }
        """.trimIndent()

        val imported = parser.parse(value.toByteArray())
        assertEquals(1, imported.size)
        assertEquals("xray-json", imported.single().protocol)
    }

    @Test
    fun `rejects empty unsupported and oversized subscriptions`() {
        listOf(
            byteArrayOf(),
            "ftp://invalid".toByteArray(),
            ByteArray(SubscriptionParser.MAX_SUBSCRIPTION_BYTES + 1),
        ).forEach {
            assertFailsWith<IllegalArgumentException> { parser.parse(it) }
        }
    }
}