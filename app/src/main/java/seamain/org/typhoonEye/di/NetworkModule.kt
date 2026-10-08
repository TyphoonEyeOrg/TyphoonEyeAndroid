package seamain.org.typhoonEye.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import seamain.org.typhoonEye.BuildConfig
import seamain.org.typhoonEye.data.api.GitHubReleaseApi
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherWarningApi
import seamain.org.typhoonEye.data.api.redactingLoggingInterceptor
import seamain.org.typhoonEye.data.credentials.DataSourceCredentials
import seamain.org.typhoonEye.data.credentials.QWeatherHost
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /** Key + API host are read per request from [DataSourceCredentials] (Settings → BuildConfig). */
    @Provides
    @Singleton
    fun provideQWeatherAuthInterceptor(credentials: DataSourceCredentials): QWeatherAuthInterceptor =
        QWeatherAuthInterceptor(credentials)

    /** Debug logs full URLs (BASIC): keys in query params / auth headers are masked. */
    @Provides
    @Singleton
    @Named("logging")
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        redactingLoggingInterceptor(
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        )

    @Provides
    @Singleton
    @Named("juhe")
    fun provideJuheOkHttp(
        @Named("logging") logging: HttpLoggingInterceptor
    ): OkHttpClient = baseOkHttpBuilder()
        .addInterceptor(logging)
        .build()

    /** Shared client for GitHub API + APK download (longer timeouts). */
    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubOkHttp(
        @Named("logging") logging: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "TyphoonEye/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(logging)
        .build()

    @Provides
    @Singleton
    @Named("qweather")
    fun provideQWeatherOkHttp(
        auth: QWeatherAuthInterceptor,
        @Named("logging") logging: HttpLoggingInterceptor
    ): OkHttpClient = baseOkHttpBuilder()
        .addInterceptor(auth)
        .addInterceptor(logging)
        .build()

    private fun baseOkHttpBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)

    @Provides
    @Singleton
    @Named("juhe")
    fun provideJuheRetrofit(
        @Named("juhe") client: OkHttpClient,
        json: Json
    ): Retrofit {
        val mediaType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://apis.juhe.cn/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
    }

    @Provides
    @Singleton
    @Named("qweather")
    fun provideQWeatherRetrofit(
        @Named("qweather") client: OkHttpClient,
        json: Json
    ): Retrofit {
        val mediaType = "application/json".toMediaType()
        // Placeholder only: QWeatherAuthInterceptor rewrites the host per request.
        return Retrofit.Builder()
            .baseUrl("${QWeatherHost.DEFAULT}/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
    }

    @Provides
    @Singleton
    fun provideJuheTyphoonApi(@Named("juhe") retrofit: Retrofit): JuheTyphoonApi =
        retrofit.create(JuheTyphoonApi::class.java)

    @Provides
    @Singleton
    fun provideQWeatherTyphoonApi(@Named("qweather") retrofit: Retrofit): QWeatherTyphoonApi =
        retrofit.create(QWeatherTyphoonApi::class.java)

    @Provides
    @Singleton
    fun provideQWeatherWarningApi(@Named("qweather") retrofit: Retrofit): QWeatherWarningApi =
        retrofit.create(QWeatherWarningApi::class.java)

    @Provides
    @Singleton
    @Named("github")
    fun provideGitHubRetrofit(
        @Named("github") client: OkHttpClient,
        json: Json
    ): Retrofit {
        val mediaType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(mediaType))
            .build()
    }

    @Provides
    @Singleton
    fun provideGitHubReleaseApi(@Named("github") retrofit: Retrofit): GitHubReleaseApi =
        retrofit.create(GitHubReleaseApi::class.java)
}
