package com.landosol.toolbox.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV1Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun exportedSchemaV1CreatesExpectedTables() {
        helper.createDatabase(TEST_DATABASE, 1).use { database ->
            val cursor = database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
            )
            val tables = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            cursor.close()

            assertEquals(
                listOf(
                    "accounts",
                    "automation_runs",
                    "feature_profiles",
                    "labyrinth_routes",
                    "migration_checkpoints",
                ),
                tables,
            )
        }
    }

    private companion object {
        const val TEST_DATABASE = "schema-v1-test.db"
    }
}
