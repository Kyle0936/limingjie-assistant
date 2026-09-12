package com.landosol.toolbox.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun accountSelectionIsAtomic() = runBlocking {
        val firstId = database.accountDao().insert(account("first", "key-1"))
        val secondId = database.accountDao().insert(account("second", "key-2"))

        database.accountDao().selectAccount(firstId)
        database.accountDao().selectAccount(secondId)

        val accounts = database.accountDao().observeAll().first()
        assertEquals(listOf(secondId), accounts.filter { it.isSelected }.map { it.id })
    }

    @Test
    fun maliciousTextIsStoredAsDataWithoutChangingSchema() = runBlocking {
        val payload = "'; DROP TABLE accounts; --"
        val id = database.accountDao().insert(account(payload, "key-payload"))

        assertEquals(payload, database.accountDao().getById(id)?.alias)
        assertEquals(1, database.accountDao().count())
    }

    @Test
    fun deletingAccountCascadesProfilesAndRoutes() = runBlocking {
        val id = database.accountDao().insert(account("cascade", "key-cascade"))
        database.seedRelatedRecordsForTest(id)

        database.accountDao().deleteById(id)

        assertNull(database.accountDao().getById(id))
        assertEquals(0, database.featureProfileDao().countForAccount(id))
        assertEquals(0, database.labyrinthRouteDao().countForAccount(id))
    }

    private fun account(alias: String, credentialKey: String) = AccountEntity(
        alias = alias,
        serverId = "cn-bilibili",
        gameUid = null,
        credentialKey = credentialKey,
        isSelected = false,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
