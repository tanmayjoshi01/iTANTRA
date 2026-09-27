package com.itantra.app.link

import java.net.Inet4Address
import java.net.NetworkInterface

/** This device's IPv4 LAN addresses (e.g. 192.168.43.12), for the user to type into Phone A. */
fun localIpv4Addresses(): List<String> = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
} catch (_: Exception) {
    emptyList()
}
