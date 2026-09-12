package com.landosol.toolbox.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV2Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun schemaV1MigratesToV2() {
        helper.createDatabase(TEST_DATABASE, 1).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            2,
            true,
            AppDatabase.MIGRATION_1_2,
        )
    }

    private companion object {
        const val TEST_DATABASE = "schema-v2-migration-test.db"
    }
}
