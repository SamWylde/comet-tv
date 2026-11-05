package com.tdarby.comet.handoff

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The TV's own IPv4 address on Wi-Fi or Ethernet, or null when it has neither. */
object LanAddress {
    fun find(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        // The active network first. With a VPN active, its own address is not the LAN one, so fall back
        // to the other networks and take the first plain Wi-Fi or Ethernet one.
        val candidates = listOfNotNull(cm.activeNetwork) + otherNetworks(cm)
        for (network in candidates) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val address = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            if (address != null) return address
        }
        return null
    }

    @Suppress("DEPRECATION") // Still the only way to list every network; used only as a fallback.
    private fun otherNetworks(cm: ConnectivityManager): List<Network> = cm.allNetworks.toList()
}
