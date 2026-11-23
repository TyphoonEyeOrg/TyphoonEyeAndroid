package seamain.org.typhoonEye.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import seamain.org.typhoonEye.data.repository.DefaultTyphoonRepository
import seamain.org.typhoonEye.data.repository.DefaultWarningRepository
import seamain.org.typhoonEye.data.repository.RoomFavoriteRepository
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.repository.WarningRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTyphoonRepository(
        impl: DefaultTyphoonRepository
    ): TyphoonRepository

    @Binds
    @Singleton
    abstract fun bindWarningRepository(
        impl: DefaultWarningRepository
    ): WarningRepository

    @Binds
    @Singleton
    abstract fun bindFavoriteRepository(
        impl: RoomFavoriteRepository
    ): FavoriteRepository
}
