package com.landosol.toolbox.protocol.bilibili

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PcrGamePackagesTest {
    @Test
    fun `known bilibili package maps to bilibili server`() {
        assertEquals(GameServer.CN_BILIBILI, PcrGamePackages.classify("com.bilibili.priconne"))
    }

    @Test
    fun `known channel packages map to channel server`() {
        listOf(
            "com.bilibili.priconne.mi",
            "com.bilibili.priconne.huawei",
            "com.bilibili.priconne.vivo",
            "com.tencent.tmgp.bilibili.priconne",
            "com.bilibili.priconne.aligames",
        ).forEach { packageName ->
            assertEquals(GameServer.CN_CHANNEL, PcrGamePackages.classify(packageName))
        }
    }

    @Test
    fun `unknown priconne package is accepted as channel fallback`() {
        assertEquals(GameServer.CN_CHANNEL, PcrGamePackages.classify("vendor.example.priconne.channel"))
        assertTrue(PcrGamePackages.looksLikePcrPackage("vendor.princessconnect.client"))
    }

    @Test
    fun `unrelated packages are rejected`() {
        assertNull(PcrGamePackages.classify("com.example.game"))
    }
}
