package io.github.hamzabhai.jhatpat.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** Lists the phone's network interfaces as (name, IPv4 address) pairs. Injectable for tests. */
fun interface NetworkInfoProvider {
    fun candidates(): List<Pair<String, String>>
}

/** The real thing: every interface that is up, with each of its IPv4 addresses. */
object SystemNetworkInfo : NetworkInfoProvider {
    override fun candidates(): List<Pair<String, String>> {
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return emptyList()
        }
        return interfaces
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic ->
                nic.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { addr ->
                    addr.hostAddress?.let { nic.name to it }
                }
            }
    }
}
