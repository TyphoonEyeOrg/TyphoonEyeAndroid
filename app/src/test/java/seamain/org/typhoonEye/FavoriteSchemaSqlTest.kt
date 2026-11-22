package seamain.org.typhoonEye

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import seamain.org.typhoonEye.data.local.TyphoonDatabase

/**
 * Pins the favorites DDL so foss and master ship the same table.
 * There is no secondary index: the stable id is the primary key.
 */
class FavoriteSchemaSqlTest {

    @Test
    fun createFavorites_matchesPinnedStatement_andMigrationCreatesNoIndex() {
        assertEquals(PINNED_CREATE_FAVORITES, TyphoonDatabase.CREATE_FAVORITES)

        val db = mock<SupportSQLiteDatabase>()
        val sql = argumentCaptor<String>()
        TyphoonDatabase.MIGRATION_1_2.migrate(db)
        verify(db).execSQL(sql.capture())
        verifyNoMoreInteractions(db)

        assertEquals(listOf(PINNED_CREATE_FAVORITES), sql.allValues)
        assertEquals(PINNED_INDEX_SQL, sql.allValues.filter { it.contains("INDEX", ignoreCase = true) })
        assertEquals(emptyList<String>(), PINNED_INDEX_SQL)
    }

    private companion object {
        /**
         * Exact statement executed by [TyphoonDatabase.MIGRATION_1_2].
         * Copy this string verbatim when aligning the other branch.
         */
        const val PINNED_CREATE_FAVORITES =
            "CREATE TABLE IF NOT EXISTS `favorite_typhoons` (" +
                "`stableId` TEXT NOT NULL, " +
                "`sourceId` TEXT NOT NULL, " +
                "`trackId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`englishName` TEXT NOT NULL, " +
                "`year` INTEGER, " +
                "`basin` TEXT NOT NULL, " +
                "`number` TEXT, " +
                "`status` TEXT NOT NULL, " +
                "`strong` TEXT NOT NULL, " +
                "`favoritedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`stableId`))"

        /** No CREATE INDEX. Favorites are deduped by the primary key alone. */
        val PINNED_INDEX_SQL: List<String> = emptyList()
    }
}
