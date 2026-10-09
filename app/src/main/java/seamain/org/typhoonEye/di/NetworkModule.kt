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

    @Provides
    @Singleton
    fun provideQWeatherAuthInterceptor(): QWeatherAuthInterceptor =
        QWeatherAuthInterceptor(
            apiKey = BuildConfig.QWEATHER_API_KEY,
            kid = BuildConfig.QWEATHER_KID,
            projectId = BuildConfig.QWEATHER_PROJECT_ID,
            developerId = BuildConfig.QWEATHER_DEVELOPER_ID,
            privateKeyPem = BuildConfig.QWEATHER_PRIVATE_KEY
        )

    @Provides
    @Singleton
    @Named("juhe_key")
    fun provideJuheKey(): String = BuildConfig.JUHE_KEY

    @Provides
    @Singleton
    @Named("logging")
    fun provideLoggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

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
        val host = BuildConfig.QWEATHER_HOST.ifBlank {
            "https://pu6yvrgfbv.re.qweatherapi.com/"
        }.let { if (it.endsWith("/")) it else "$it/" }
        return Retrofit.Builder()
            .baseUrl(host)
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
