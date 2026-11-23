package seamain.org.typhoonEye

import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.local.TyphoonDatabase
import seamain.org.typhoonEye.data.repository.RoomFavoriteRepository
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.util.WallClock

/**
 * Favorites live in their own table. Replacing the live typhoon cache, and
 * reopening the database, must not drop them. Upgrading a version-1 database
 * keeps the cached storms and adds the favorites table.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FavoriteRepositoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val clock = WallClock { 1_700_000_000_000L }

    @After
    fun cleanUp() {
        context.deleteDatabase(FILE_DB)
        context.deleteDatabase(V1_DB)
    }

    @Test
    fun add_dedupesByStableId_andSurvivesCacheClearAndReopen() = runBlocking {
        context.deleteDatabase(FILE_DB)
        val first = Room.databaseBuilder(context, TyphoonDatabase::class.java, FILE_DB)
            .addMigrations(TyphoonDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val repository = RoomFavoriteRepository(first.favoriteDao(), clock)
        repository.add(
            FavoriteDraft(
                sourceId = "NP_2609",
                name = "Bavi",
                englishName = "BAVI",
                year = 2026,
                status = "active",
                strong = "TY"
            )
        )
        repository.add(
            FavoriteDraft(
                sourceId = "202609",
                name = "巴威",
                englishName = "BAVI",
                year = 2026,
                status = "dissipated"
            )
        )
        first.typhoonDao().clear()
        assertEquals(0, first.typhoonDao().getAll().size)
        first.close()

        val reopened = Room.databaseBuilder(context, TyphoonDatabase::class.java, FILE_DB)
            .addMigrations(TyphoonDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val saved = reopened.favoriteDao().getAll()
        assertEquals(1, saved.size)
        assertEquals("2609", saved.single().stableId)
        assertEquals("NP_2609", saved.single().sourceId)
        assertEquals("NP_2609", saved.single().trackId)
        assertEquals("Bavi", saved.single().name)
        assertEquals("BAVI", saved.single().englishName)
        assertEquals(2026, saved.single().year)
        assertEquals("202609", saved.single().number)
        RoomFavoriteRepository(reopened.favoriteDao(), clock).remove("202609")
        assertTrue(reopened.favoriteDao().getAll().isEmpty())
        reopened.close()
    }

    @Test
    fun createFavorites_matchesTheSharedSchemaLiteral() {
        assertEquals(SHARED_CREATE_FAVORITES, TyphoonDatabase.CREATE_FAVORITES)
    }

    @Test
    fun migration1To2_keepsCachedTyphoon_andAcceptsAFavorite() = runBlocking {
        context.deleteDatabase(V1_DB)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(V1_DB)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(TYPHOONS_V1)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        helper.writableDatabase.execSQL(
            "INSERT INTO typhoons (" +
                "id, name, englishName, status, strong, positionDesc, forecastText, " +
                "startTime, endTime, pointsJson, forecastPointsJson, cachedAtEpochMs" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                "202609", "巴威", "BAVI", "active", "TY", "", "",
                "", "", "[]", "[]", 10L
            )
        )
        helper.close()

        val upgraded = Room.databaseBuilder(context, TyphoonDatabase::class.java, V1_DB)
            .addMigrations(TyphoonDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val cached = upgraded.typhoonDao().getById("202609")
        assertNotNull(cached)
        assertEquals("巴威", cached?.name)
        RoomFavoriteRepository(upgraded.favoriteDao(), clock).add(
            FavoriteDraft(sourceId = "NP_2418", name = "Hagupit", year = 2024, status = "dissipated")
        )
        val favorite = upgraded.favoriteDao().getByStableId("2418")
        assertEquals("Hagupit", favorite?.name)
        assertEquals(2024, favorite?.year)
        assertEquals(2, upgraded.openHelper.readableDatabase.version)
        val created = upgraded.openHelper.readableDatabase.query(
            SimpleSQLiteQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = 'favorite_typhoons'"
            )
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        // SQLite stores the executed statement without "IF NOT EXISTS".
        assertEquals(SHARED_CREATE_FAVORITES.replace("IF NOT EXISTS ", ""), created)
        upgraded.close()

        val again = Room.databaseBuilder(context, TyphoonDatabase::class.java, V1_DB)
            .addMigrations(TyphoonDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        assertEquals("Hagupit", again.favoriteDao().getByStableId("2418")?.name)
        assertNull(again.favoriteDao().getByStableId("missing"))
        again.close()
    }

    private companion object {
        const val FILE_DB = "favorites-reopen.db"
        const val V1_DB = "favorites-migrate.db"
        const val SHARED_CREATE_FAVORITES =
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
        const val TYPHOONS_V1 =
            "CREATE TABLE IF NOT EXISTS `typhoons` (" +
                "`id` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, " +
                "`englishName` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, " +
                "`strong` TEXT NOT NULL, " +
                "`positionDesc` TEXT NOT NULL, " +
                "`forecastText` TEXT NOT NULL, " +
                "`startTime` TEXT NOT NULL, " +
                "`endTime` TEXT NOT NULL, " +
                "`pointsJson` TEXT NOT NULL, " +
                "`forecastPointsJson` TEXT NOT NULL, " +
                "`cachedAtEpochMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
    }
}
