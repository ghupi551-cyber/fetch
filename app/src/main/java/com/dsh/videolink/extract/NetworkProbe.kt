package com.dsh.videolink.extract

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 简单网络状态判断：现在是不是挂着代理（VPN） */
object NetworkProbe {

    fun vpnActive(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        manager.allNetworks.any { network ->
            manager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)
}
