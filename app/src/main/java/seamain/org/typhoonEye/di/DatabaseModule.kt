package seamain.org.typhoonEye.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import seamain.org.typhoonEye.data.local.TyphoonDao
import seamain.org.typhoonEye.data.local.TyphoonDatabase
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.data.sync.SharedPrefsFeedSyncStore
import seamain.org.typhoonEye.domain.util.WallClock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TyphoonDatabase =
        TyphoonDatabase.create(context)

    @Provides
    @Singleton
    fun provideTyphoonDao(database: TyphoonDatabase): TyphoonDao =
        database.typhoonDao()

    @Provides
    @Singleton
    fun provideTyphoonLocalDataSource(
        dao: TyphoonDao,
        json: Json
    ): TyphoonLocalDataSource = TyphoonLocalDataSource(dao, json)

    @Provides
    @Singleton
    fun provideFeedSyncStore(@ApplicationContext context: Context): FeedSyncStore =
        SharedPrefsFeedSyncStore(context)

    @Provides
    @Singleton
    fun provideWallClock(): WallClock = WallClock.System
}
