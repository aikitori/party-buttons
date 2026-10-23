package de.aikitori.partybuttons.server

import java.net.Inet4Address
import java.net.NetworkInterface

/** Eine IPv4-Adresse an einer Netzwerkschnittstelle des Hosts. */
data class InterfaceAddress(val interfaceName: String, val address: String)

/**
 * Adressen, unter denen Mitspielende den Host erreichen, die beste zuerst (für den QR-Code).
 * WLAN und Hotspot vor Kabel; mobile Daten, VPN und interne Schnittstellen fallen weg.
 */
object LanAddresses {
    // Hotspot heißt je nach Hersteller ap0, swlan0, softap0 oder wlan1
    private val wifi = Regex("^(wlan|ap|swlan|softap)\\d*$")
    private val ethernet = Regex("^(eth|en)\\w*$")
    private val excluded = Regex("^(lo|rmnet|ccmni|tun|ppp|dummy|ipsec|clat|v4-|p2p|aware|nan).*")

    fun rank(candidates: List<InterfaceAddress>): List<String> =
        candidates
            .filter { !excluded.matches(it.interfaceName) && isPrivate(it.address) }
            .sortedBy {
                when {
                    wifi.matches(it.interfaceName) -> 0
                    ethernet.matches(it.interfaceName) -> 1
                    else -> 2
                }
            }
            .map { it.address }
            .distinct()

    /** Alle Adressen dieses Geräts, sortiert wie [rank]. */
    fun current(): List<String> = rank(
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif ->
                    nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                        .mapNotNull { addr -> addr.hostAddress?.let { InterfaceAddress(nif.name, it) } }
                }
        }.getOrDefault(emptyList()),
    )

    // 10/8, 172.16/12, 192.168/16: nur lokale Netze, keine öffentliche Adresse vom Mobilfunk
    private fun isPrivate(ip: String): Boolean {
        val parts = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) || (parts[0] == 192 && parts[1] == 168)
    }
}
