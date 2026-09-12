package com.landosol.toolbox.data

import androidx.room.migration.AutoMigrationSpec
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.landosol.toolbox.data.local.AppDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SchemaV4Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList<AutoMigrationSpec>(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun schemaV3MigratesToV4() {
        helper.createDatabase(TEST_DATABASE, 3).close()
        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            AppDatabase.MIGRATION_3_4,
        )
    }

    private companion object {
        const val TEST_DATABASE = "schema-v4-migration-test.db"
    }
}
