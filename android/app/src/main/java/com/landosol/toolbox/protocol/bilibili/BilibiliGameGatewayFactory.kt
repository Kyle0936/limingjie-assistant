package com.landosol.toolbox.protocol.bilibili

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

object BilibiliGameGatewayFactory {
    fun create(context: Context): BilibiliGameGateway {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        return BilibiliGameProtocolGateway(
            client = client,
            bootstrapEndpoint = BOOTSTRAP_ENDPOINT.toHttpUrl(),
            profile = AndroidGameProtocolProfileFactory(context.applicationContext).create(),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    private const val BOOTSTRAP_ENDPOINT = "https://l3-prod-all-gs-gzlj.bilibiligame.net/"
}
