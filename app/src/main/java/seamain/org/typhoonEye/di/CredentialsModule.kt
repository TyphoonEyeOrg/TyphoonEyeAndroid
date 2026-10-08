package seamain.org.typhoonEye.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.data.credentials.BuildTimeCredentials
import seamain.org.typhoonEye.data.credentials.DataSourceCredentials
import seamain.org.typhoonEye.data.credentials.DataStoreUserKeyStore
import seamain.org.typhoonEye.data.credentials.DefaultDataSourceCredentials
import seamain.org.typhoonEye.data.credentials.UserKeyStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CredentialsModule {

    @Provides
    @Singleton
    fun provideUserKeyStore(@ApplicationContext context: Context): UserKeyStore =
        DataStoreUserKeyStore(context)

    /** Empty on the F-Droid flavor (no keys at build time). */
    @Provides
    @Singleton
    fun provideBuildTimeCredentials(): BuildTimeCredentials = BuildTimeCredentials(
        juheKey = BuildConfig.JUHE_KEY,
        qWeatherApiKey = BuildConfig.QWEATHER_API_KEY,
        qWeatherHost = BuildConfig.QWEATHER_HOST,
        qWeatherKid = BuildConfig.QWEATHER_KID,
        qWeatherProjectId = BuildConfig.QWEATHER_PROJECT_ID,
        qWeatherPrivateKeyPem = BuildConfig.QWEATHER_PRIVATE_KEY
    )

    /** User key (Settings) first, else build-time key; read on every call. */
    @Provides
    @Singleton
    fun provideDataSourceCredentials(
        store: UserKeyStore,
        buildTime: BuildTimeCredentials
    ): DataSourceCredentials = DefaultDataSourceCredentials(store::current, buildTime)
}
