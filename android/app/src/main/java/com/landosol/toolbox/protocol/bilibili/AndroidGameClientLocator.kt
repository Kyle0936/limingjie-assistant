package com.landosol.toolbox.protocol.bilibili

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class InstalledGameClient(
    val packageName: String,
    val server: GameServer,
)

object PcrGamePackages {
    const val BILIBILI = "com.bilibili.priconne"
    const val XIAOMI = "com.bilibili.priconne.mi"
    const val HUAWEI = "com.bilibili.priconne.huawei"
    const val VIVO = "com.bilibili.priconne.vivo"
    const val TENCENT = "com.tencent.tmgp.bilibili.priconne"
    const val ALIGAMES = "com.bilibili.priconne.aligames"

    val knownPackages: LinkedHashMap<String, GameServer> = linkedMapOf(
        BILIBILI to GameServer.CN_BILIBILI,
        XIAOMI to GameServer.CN_CHANNEL,
        HUAWEI to GameServer.CN_CHANNEL,
        VIVO to GameServer.CN_CHANNEL,
        TENCENT to GameServer.CN_CHANNEL,
        ALIGAMES to GameServer.CN_CHANNEL,
    )

    fun classify(packageName: String?): GameServer? {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        knownPackages[normalized]?.let { return it }
        return if (looksLikePcrPackage(normalized)) GameServer.CN_CHANNEL else null
    }

    fun looksLikePcrPackage(packageName: String?): Boolean {
        val value = packageName?.lowercase().orEmpty()
        return value.contains("priconne") || value.contains("princessconnect")
    }
}

class AndroidGameClientLocator(
    context: Context,
) {
    private val packageManager = context.applicationContext.packageManager

    fun isSupportedGamePackage(packageName: String?): Boolean = PcrGamePackages.classify(packageName) != null

    fun findInstalled(server: GameServer? = null): InstalledGameClient? =
        installedClients()
            .firstOrNull { server == null || it.server == server }

    fun launchIntent(server: GameServer? = null): Intent? =
        installedClients().asSequence()
            .filter { server == null || it.server == server }
            .mapNotNull { packageManager.getLaunchIntentForPackage(it.packageName) }
            .firstOrNull()

    fun installedClients(): List<InstalledGameClient> {
        val result = LinkedHashMap<String, InstalledGameClient>()

        PcrGamePackages.knownPackages.forEach { (packageName, server) ->
            if (isInstalled(packageName)) {
                result[packageName] = InstalledGameClient(packageName, server)
            }
        }

        launcherPackages().forEach { packageName ->
            if (packageName !in result) {
                PcrGamePackages.classify(packageName)?.let { server ->
                    result[packageName] = InstalledGameClient(packageName, server)
                }
            }
        }
        return result.values.toList()
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun launcherPackages(): Sequence<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved.asSequence().map { it.activityInfo.packageName }.distinct()
    }
}
