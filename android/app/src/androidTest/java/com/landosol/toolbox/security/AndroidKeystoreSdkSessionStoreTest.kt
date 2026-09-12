package com.landosol.toolbox.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.landosol.toolbox.protocol.bilibili.SdkSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSdkSessionStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AndroidKeystoreSdkSessionStore(context, storageName = "sdk-session-test")

    @After
    fun cleanUp() = runBlocking { store.clearAllForTest() }

    @Test
    fun sessionTokenIsEncryptedAndCanBeDeleted() = runBlocking {
        val session = SdkSession("uid-fixture", "access-key-fixture", 123L)
        store.save("account-key", session)

        val recreated = AndroidKeystoreSdkSessionStore(context, storageName = "sdk-session-test")
        assertEquals(session, recreated.read("account-key"))
        val raw = context.getSharedPreferences("sdk-session-test", Context.MODE_PRIVATE).all.values.joinToString("")
        assertFalse(raw.contains(session.uid))
        assertFalse(raw.contains(session.accessKey))

        recreated.delete("account-key")
        assertNull(recreated.read("account-key"))
    }
}
