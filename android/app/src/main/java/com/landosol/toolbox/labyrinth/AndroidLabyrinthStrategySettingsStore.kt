package com.landosol.toolbox.labyrinth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidLabyrinthStrategySettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("labyrinth-strategy", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val current = MutableStateFlow(LabyrinthStrategySettingsCodec.decode(preferences.getString("settings.v1", null)))
    val state = current.asStateFlow()

    suspend fun save(settings: LabyrinthStrategySettings) = mutex.withLock {
        val encoded = LabyrinthStrategySettingsCodec.encode(settings)
        withContext(Dispatchers.IO) {
            check(preferences.edit().putString("settings.v1", encoded).commit()) { "策略保存失败，请检查存储空间" }
            current.value = settings
        }
    }
}
