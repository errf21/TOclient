package com.toclient.core.routing

import com.toclient.core.model.RoutingPolicy
import kotlin.math.floor
import kotlin.math.ln

data class Ipv4Cidr(
    val address: String,
    val prefixLength: Int,
)

object Ipv4RoutePlanner {
    private val lanCidrs = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
    )

    fun includedRoutes(policy: RoutingPolicy): List<Ipv4Cidr> {
        val excluded = buildList {
            if (policy.bypassLan) addAll(lanCidrs)
            addAll(policy.excludedCidrs)
        }
        if (excluded.isEmpty()) return listOf(Ipv4Cidr("0.0.0.0", 0))

        val merged = excluded.map(::parseRange)
            .sortedBy(AddressRange::start)
            .fold(mutableListOf<AddressRange>()) { ranges, next ->
                val previous = ranges.lastOrNull()
                if (previous != null && next.start <= previous.end + 1) {
                    ranges[ranges.lastIndex] = previous.copy(end = maxOf(previous.end, next.end))
                } else {
                    ranges += next
                }
                ranges
            }

        val included = mutableListOf<AddressRange>()
        var cursor = 0L
        merged.forEach { range ->
            if (cursor < range.start) included += AddressRange(cursor, range.start - 1)
            cursor = maxOf(cursor, range.end + 1)
        }
        if (cursor <= MAX_IPV4) included += AddressRange(cursor, MAX_IPV4)
        return included.flatMap(::rangeToCidrs)
    }

    private fun parseRange(cidr: String): AddressRange {
        val parts = cidr.split('/', limit = 2)
        require(parts.size == 2) { "IPv4 route must use CIDR notation: $cidr" }
        val prefix = parts[1].toIntOrNull()
        require(prefix != null && prefix in 0..32) { "Invalid IPv4 prefix: $cidr" }
        val address = parseAddress(parts[0])
        val size = 1L shl (32 - prefix)
        require(address % size == 0L) { "CIDR is not network-aligned: $cidr" }
        return AddressRange(address, address + size - 1)
    }

    private fun parseAddress(value: String): Long {
        val parts = value.split('.')
        require(parts.size == 4) { "Invalid IPv4 address: $value" }
        return parts.fold(0L) { result, part ->
            val octet = part.toIntOrNull()
            require(octet != null && octet in 0..255) { "Invalid IPv4 address: $value" }
            (result shl 8) or octet.toLong()
        }
    }

    private fun rangeToCidrs(range: AddressRange): List<Ipv4Cidr> {
        val result = mutableListOf<Ipv4Cidr>()
        var start = range.start
        while (start <= range.end) {
            val alignment = if (start == 0L) 1L shl 32 else start and -start
            val remaining = range.end - start + 1
            var block = minOf(alignment, highestPowerOfTwoAtMost(remaining))
            while (start + block - 1 > range.end) block = block shr 1
            val prefix = 32 - log2(block)
            result += Ipv4Cidr(formatAddress(start), prefix)
            start += block
        }
        return result
    }

    private fun highestPowerOfTwoAtMost(value: Long): Long =
        java.lang.Long.highestOneBit(value)

    private fun log2(value: Long): Int =
        floor(ln(value.toDouble()) / ln(2.0)).toInt()

    private fun formatAddress(value: Long): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift ->
            ((value shr shift) and 0xff).toString()
        }

    private data class AddressRange(val start: Long, val end: Long)

    private const val MAX_IPV4 = 0xffff_ffffL
}