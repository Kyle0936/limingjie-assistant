package com.landosol.toolbox.protocol.bilibili

import android.content.Context
import android.os.Build

data class GameProtocolProfile(
    val appVersion: String,
    val headers: Map<String, String>,
)

class AndroidGameProtocolProfileFactory(
    private val context: Context,
    private val gameClientLocator: AndroidGameClientLocator = AndroidGameClientLocator(context),
) {
    fun create(server: GameServer = GameServer.CN_BILIBILI): GameProtocolProfile {
        val client = gameClientLocator.findInstalled(server)
            ?: gameClientLocator.findInstalled()
            ?: error("未找到已安装的公主连结国服客户端")
        val packageInfo = context.packageManager.getPackageInfo(client.packageName, 0)
        val appVersion = packageInfo.versionName.orEmpty().ifBlank { error("无法读取国服游戏版本") }
        val model = listOf(Build.BRAND, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        return GameProtocolProfile(
            appVersion = appVersion,
            headers = linkedMapOf(
                "Accept-Encoding" to "gzip",
                "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android ${Build.VERSION.RELEASE})",
                "X-Unity-Version" to UNITY_VERSION,
                "APP-VER" to appVersion,
                "BATTLE-LOGIC-VERSION" to "4",
                "BUNDLE-VER" to "",
                "DEVICE" to "2",
                "DEVICE-NAME" to model.ifBlank { "Android" },
                "EXCEL-VER" to "1.0.0",
                "GRAPHICS-DEVICE-NAME" to "Android",
                "IP-ADDRESS" to "",
                "KEYCHAIN" to "",
                "LOCALE" to "CN",
                "PLATFORM-OS-VERSION" to "Android ${Build.VERSION.RELEASE} / API-${Build.VERSION.SDK_INT}",
                "REGION-CODE" to "",
                "RES-KEY" to RES_KEY,
                "RES-VER" to DEFAULT_RES_VERSION,
                "SHORT-UDID" to "0",
                "PLATFORM" to "2",
                "PLATFORM-ID" to "2",
                "CHANNEL-ID" to "1",
            ),
        )
    }

    private companion object {
        const val UNITY_VERSION = "2021.3.20f1c1"
        const val RES_KEY = "ab00a0a6dd915a052a2ef7fd649083e5"
        const val DEFAULT_RES_VERSION = "10002200"
    }
}
