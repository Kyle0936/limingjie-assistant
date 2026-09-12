package com.landosol.toolbox.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.labyrinth.LabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.RoomLabyrinthRunStateStore
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthCharacterMatch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LabyrinthRunStateDatabaseTest {
    private lateinit var database: AppDatabase
    private lateinit var store: LabyrinthRunStateStore

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomLabyrinthRunStateStore(database)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun recognizedCharactersSurviveReloadAndRemainDeduplicated() = runBlocking {
        store.startNew(accountId = null, nowMillis = 100L)
        store.recordPage(
            accountId = null,
            page = "CHARACTER_JOINED",
            characters = listOf(
                match("1209", "伊莉亚(新年)", 0.91),
                match("1257", "花凛(炼金术师)", 0.88),
            ),
            nowMillis = 100L,
        )
        store.recordPage(
            accountId = null,
            page = "CHARACTER_JOINED",
            characters = listOf(match("1257", "花凛(炼金术师)", 0.95)),
            nowMillis = 200L,
        )

        val reloaded = store.load(accountId = null)

        assertNotNull(reloaded)
        assertEquals(listOf("1209", "1257"), reloaded!!.joinedCharacters.map { it.characterId })
        assertEquals(0.95, reloaded.joinedCharacters[1].confidence, 0.0001)
        assertEquals("CHARACTER_JOINED", reloaded.currentPage)
    }

    private fun match(id: String, name: String, confidence: Double) = LabyrinthCharacterMatch(
        slotId = "joined",
        characterId = id,
        displayName = name,
        confidence = confidence,
        screenRect = EntryPixelRect(0, 0, 10, 10),
    )
}
