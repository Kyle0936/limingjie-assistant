package com.landosol.toolbox.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = AndroidKeystoreCredentialStore(context, storageName = "credential-test")

    @After
    fun cleanUp() = runBlocking {
        store.clearAllForTest()
    }

    @Test
    fun encryptedCredentialsSurviveStoreRecreation() = runBlocking {
        val credentials = AccountCredentials("login-user", "plain-secret")
        store.save("account-key", credentials)

        val recreated = AndroidKeystoreCredentialStore(context, storageName = "credential-test")
        assertEquals(credentials, recreated.read("account-key"))

        val rawValues = context.getSharedPreferences("credential-test", Context.MODE_PRIVATE).all.values
            .joinToString(separator = "")
        assertFalse(rawValues.contains(credentials.loginId))
        assertFalse(rawValues.contains(credentials.password))

        recreated.delete("account-key")
        assertNull(recreated.read("account-key"))
    }
}
