package com.landosol.toolbox.automation

import android.content.pm.PackageManager
import com.landosol.toolbox.protocol.bilibili.GameServer

sealed interface GameClientResolution {
    data class Available(val server: GameServer) : GameClientResolution

    /** 选中账号所属服务器的客户端未安装。不退而求其次操作别的渠道包。 */
    data class NotInstalled(val server: GameServer) : GameClientResolution

    /** 没有选中账号，且装了不止一个客户端，无法判断该操作哪一个。 */
    data class Ambiguous(val servers: List<GameServer>) : GameClientResolution

    data object Unavailable : GameClientResolution
}

object GameClientResolver {
    /**
     * 选中账号的服务器决定要操作的客户端，因此多个渠道包并存也能明确选择。
     * 仅在还没有选中账号时按已安装的包推断，且只接受唯一安装。
     */
    fun resolve(selectedServer: GameServer?, installedPackageNames: Set<String>): GameClientResolution {
        if (selectedServer != null) {
            return if (selectedServer.packageName in installedPackageNames) {
                GameClientResolution.Available(selectedServer)
            } else {
                GameClientResolution.NotInstalled(selectedServer)
            }
        }
        val installed = GameServer.entries.filter { it.packageName in installedPackageNames }
        return when (installed.size) {
            0 -> GameClientResolution.Unavailable
            1 -> GameClientResolution.Available(installed.single())
            else -> GameClientResolution.Ambiguous(installed)
        }
    }

    fun installedPackageNames(packageManager: PackageManager): Set<String> = GameServer.entries
        .map(GameServer::packageName)
        .filter { packageName -> isInstalled(packageManager, packageName) }
        .toSet()

    private fun isInstalled(packageManager: PackageManager, packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
