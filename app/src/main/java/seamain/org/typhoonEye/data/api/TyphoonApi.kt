package seamain.org.typhoonEye.data.api

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import seamain.org.typhoonEye.data.model.JuheActiveListResponse
import seamain.org.typhoonEye.data.model.JuheDetailResponse
import seamain.org.typhoonEye.data.model.QWeatherAlertResponse
import seamain.org.typhoonEye.data.model.QWeatherStormForecastResponse
import seamain.org.typhoonEye.data.model.QWeatherStormListResponse
import seamain.org.typhoonEye.data.model.QWeatherStormTrackResponse
import seamain.org.typhoonEye.data.model.RelayAlertsResponse

/**
 * 聚合数据 API — 对齐 Postman「Typhoon Eye」Collection:
 * - GET https://apis.juhe.cn/fapigw/typhoon/active
 * - GET https://apis.juhe.cn/fapigw/typhoon/detail
 *
 * F-Droid build: same paths under `{relay}/v1/juhe/`, with [apiKey] = null so no `key`
 * parameter is sent (the relay adds its own).
 */
interface JuheTyphoonApi {
    /** 当前活跃台风列表 */
    @GET("fapigw/typhoon/active")
    suspend fun getActiveTyphoons(
        @Query("key") apiKey: String?
    ): JuheActiveListResponse

    /** 指定台风实时路径与预报 */
    @GET("fapigw/typhoon/detail")
    suspend fun getTyphoonDetail(
        @Query("key") apiKey: String?,
        @Query("tfid") typhoonId: String
    ): JuheDetailResponse
}

/**
 * 和风天气 Tropical API v7 — 对齐 Postman「Typhoon Eye」Collection:
 * - GET {{api_host}}/v7/tropical/storm-list
 * - GET {{api_host}}/v7/tropical/storm-track
 * - GET {{api_host}}/v7/tropical/storm-forecast
 *
 * Auth: Authorization Bearer JWT（由 OkHttp Interceptor 注入）
 *
 * F-Droid build: same paths under `{relay}/v1/qweather/`, no auth header (the relay
 * authenticates upstream).
 */
interface QWeatherTyphoonApi {
    /** 台风列表（按海区与年份） */
    @GET("v7/tropical/storm-list")
    suspend fun getStormList(
        @Query("basin") basin: String = "NP",
        @Query("year") year: String
    ): QWeatherStormListResponse

    /** 台风实况路径 */
    @GET("v7/tropical/storm-track")
    suspend fun getStormTrack(
        @Query("stormid") stormId: String
    ): QWeatherStormTrackResponse

    /** 台风预报路径 */
    @GET("v7/tropical/storm-forecast")
    suspend fun getStormForecast(
        @Query("stormid") stormId: String
    ): QWeatherStormForecastResponse
}

/**
 * 和风天气 Warning API — 官方气象预警（含台风信号）
 * GET /weatheralert/v1/current/{latitude}/{longitude}
 *
 * GitHub build only. The F-Droid build never sends a location anywhere; it uses
 * [RelayAlertsApi] instead.
 */
interface QWeatherWarningApi {
    @GET("weatheralert/v1/current/{latitude}/{longitude}")
    suspend fun getCurrentAlerts(
        @Path("latitude") latitude: String,
        @Path("longitude") longitude: String,
        @Query("lang") lang: String = "zh",
        @Query("localTime") localTime: Boolean = true
    ): QWeatherAlertResponse
}

/**
 * TyphoonEye relay (F-Droid build): typhoon-related official warnings for a fixed list of
 * coastal points, refreshed by the relay on a schedule and shared by all users.
 * The request carries no location; the app picks nearby points on the device.
 */
interface RelayAlertsApi {
    @GET("v1/alerts")
    suspend fun getAlerts(): RelayAlertsResponse
}
