package com.example.mycam.util

import android.content.Context
import android.net.wifi.WifiManager
import android.text.format.Formatter

object NetworkInfo {
    fun getDeviceIp(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiInfo = wifiManager?.connectionInfo ?: return null
        val ipInt = wifiInfo.ipAddress
        if (ipInt == 0) return null
        return Formatter.formatIpAddress(ipInt)
    }
}


