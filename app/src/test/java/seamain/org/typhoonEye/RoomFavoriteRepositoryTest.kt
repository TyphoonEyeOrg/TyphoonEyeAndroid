package seamain.org.typhoonEye

import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import seamain.org.typhoonEye.data.local.TyphoonDatabase
import seamain.org.typhoonEye.data.local.TyphoonEntity
import seamain.org.typhoonEye.data.repository.RoomFavoriteRepository
import seamain.org.typhoonEye.domain.favorites.favoriteFrom
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.Typhoon

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomFavoriteRepositoryTest {

    private val context = RuntimeEnvironment.getApplication()
    private val open = mutableListOf<TyphoonDatabase>()

    @After
    fun tearDown() {
        open.forEach { runCatching { it.close() } }
        open.clear()
    }

    @Test
    fun add_isIdempotentAcrossIdFormats_andRestartKeepsTheRow() {
        val name = "favorites-restart.db"
        deleteDatabase(name)
        val first = openFile(name)
        val repository = RoomFavoriteRepository(first.favoriteTyphoonDao())
        val saved = runBlocking(Dispatchers.IO) {
            val inserted = repository.add(
                favoriteFrom(
                    Typhoon(
                        id = "202418",
                        name = "巴威",
                        englishName = "BAVI",
                        status = "active",
                        startTime = "2024-08-20 08:00"
                    ),
                    nowMs = 10L
                )
            )
            val again = repository.add(
                favoriteFrom(
                    HistoricalStorm("NP_2418", "Bavi", 2024, "NP", false, "202418"),
                    nowMs = 99L
                )
            )
            assertEquals(inserted, again)
            assertEquals(1, repository.getAll().size)
            assertTrue(repository.isFavorite("np_2418"))
            inserted
        }
        first.close()

        val reopened = openFile(name)
        val afterRestart = runBlocking(Dispatchers.IO) {
            RoomFavoriteRepository(reopened.favoriteTyphoonDao()).getAll()
        }
        assertEquals(listOf(saved), afterRestart)
    }

    @Test
    fun clearingTheActiveCache_doesNotRemoveFavorites() {
        val db = openMemory()
        val repository = RoomFavoriteRepository(db.favoriteTyphoonDao())
        runBlocking(Dispatchers.IO) {
            db.typhoonDao().upsert(sampleTyphoon("202609"))
            repository.toggle(
                favoriteFrom(
                    Typhoon(id = "202609", name = "巴威", englishName = "BAVI", status = "active"),
                    nowMs = 5L
                )
            )
            db.typhoonDao().replaceAll(emptyList())
            assertTrue(db.typhoonDao().getAll().isEmpty())
            val favorites = repository.getAll()
            assertEquals(listOf("NP_2609"), favorites.map { it.id })
            assertEquals("巴威", favorites.single().name)
            assertEquals(2026, favorites.single().year)
            assertFalse(repository.toggle(favorites.single()))
            assertTrue(repository.getAll().isEmpty())
        }
    }

    @Test
    fun migration1To2_keepsTyphoonRows_andFavoriteTableMatchesRoom() {
        val fresh = openMemory()
        val expectedFavoriteSql = sqliteSql(fresh.openHelper.writableDatabase, "favorite_typhoons")
        val typhoonSql = sqliteSql(fresh.openHelper.writableDatabase, "typhoons")
        fresh.close()
        assertNotNull(expectedFavoriteSql)
        assertNotNull(typhoonSql)

        val name = "favorites-migration.db"
        deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(typhoonSql!!)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO typhoons (id, name, englishName, status, strong, positionDesc, forecastText, " +
                    "startTime, endTime, pointsJson, forecastPointsJson, cachedAtEpochMs) " +
                    "VALUES ('202609', '巴威', 'BAVI', 'active', '', '', '', '', '', '[]', '[]', 1)"
            )
            db.version = 1
        }
        helper.close()

        val migrated = openFile(name)
        runBlocking(Dispatchers.IO) {
            val typhoons = migrated.typhoonDao().getAll()
            assertEquals(listOf("202609"), typhoons.map { it.id })
            assertEquals("巴威", typhoons.single().name)
            val repository = RoomFavoriteRepository(migrated.favoriteTyphoonDao())
            repository.add(
                favoriteFrom(
                    Typhoon(id = "201822", name = "山竹", englishName = "MANGKHUT", status = "dissipated"),
                    nowMs = 7L
                )
            )
            assertEquals("NP_1822", repository.getAll().single().id)
            assertEquals("山竹", repository.getAll().single().name)
        }
        val migratedSql = sqliteSql(migrated.openHelper.writableDatabase, "favorite_typhoons")
        assertEquals(normalizeCreate(expectedFavoriteSql), normalizeCreate(migratedSql))
    }

    private fun openMemory(): TyphoonDatabase =
        Room.inMemoryDatabaseBuilder(context, TyphoonDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { open += it }

    private fun openFile(name: String): TyphoonDatabase =
        Room.databaseBuilder(context, TyphoonDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(TyphoonDatabase.MIGRATION_1_2)
            .build()
            .also { open += it }

    private fun deleteDatabase(name: String) {
        context.deleteDatabase(name)
    }

    private fun sampleTyphoon(id: String) = TyphoonEntity(
        id = id,
        name = id,
        englishName = id,
        status = "active",
        strong = "",
        positionDesc = "",
        forecastText = "",
        startTime = "",
        endTime = "",
        pointsJson = "[]",
        forecastPointsJson = "[]",
        cachedAtEpochMs = 1L
    )

    private fun sqliteSql(db: SupportSQLiteDatabase, table: String): String? {
        val cursor: Cursor = db.query(
            SimpleSQLiteQuery(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table)
            )
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getString(0)
        }
    }

    private fun normalizeCreate(sql: String?): String =
        sql.orEmpty()
            .replace("IF NOT EXISTS ", "", ignoreCase = true)
            .replace(Regex("\\s+"), " ")
            .trim()
}
