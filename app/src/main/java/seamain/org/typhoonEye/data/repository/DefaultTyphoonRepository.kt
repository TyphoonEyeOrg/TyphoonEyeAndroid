package seamain.org.typhoonEye.data.repository

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.qWeatherTypeToStrong
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.util.TyphoonActivity
import seamain.org.typhoonEye.domain.util.WallClock
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Named

/**
 * Cache-first typhoon repository:
 * 1) Active list is served from Room while younger than [LIST_TTL_MS]; pull-to-refresh may
 *    force a fetch, but no more often than [MIN_FORCE_INTERVAL_MS].
 * 2) Remote: prefer Juhe, fallback QWeather. A Juhe detail is only re-fetched when the list's
 *    update time (`endtime`) for that storm changed, so a refresh costs 1 + changed storms.
 * 3) On remote failure → serve Room cache with a stale message.
 * 4) Status is resolved client-side ([TyphoonActivity]): no observation for 24 h → dissipated.
 */
class DefaultTyphoonRepository @Inject constructor(
    private val juheApi: JuheTyphoonApi,
    private val qWeatherApi: QWeatherTyphoonApi,
    @Named("juhe_key") private val juheKey: String,
    private val qWeatherAuth: QWeatherAuthInterceptor,
    private val localDataSource: TyphoonLocalDataSource,
    private val syncStore: FeedSyncStore,
    private val clock: WallClock
) : TyphoonRepository {

    private val qWeatherConfigured: Boolean
        get() = qWeatherAuth.hasCredentials

    private val tag = "TyphoonRepository"

    /** Serialises list loads so UI + background worker never fetch in parallel. */
    private val listMutex = Mutex()

    private class FetchOutcome(
        val typhoons: List<Typhoon>? = null,
        val error: String? = null
    )

    override suspend fun getCachedFeed(): TyphoonFeed? {
        val cached = runCatching { localDataSource.getAll() }.getOrNull().orEmpty()
        val fetchedAt = syncStore.lastListFetchAtMs
        if (cached.isEmpty() && fetchedAt == null) return null
        return TyphoonFeed(
            typhoons = cached.resolved(clock.nowMs()),
            fromCache = true,
            fetchedAtEpochMs = fetchedAt ?: latestCachedAt()
        )
    }

    override suspend fun getActiveTyphoons(forceRefresh: Boolean): Result<TyphoonFeed> =
        listMutex.withLock {
            val now = clock.nowMs()
            val lastFetch = syncStore.lastListFetchAtMs
            val minAge = if (forceRefresh) MIN_FORCE_INTERVAL_MS else LIST_TTL_MS
            val age = lastFetch?.let { now - it }
            if (age != null && age >= 0 && age < minAge) {
                val cached = runCatching { localDataSource.getAll() }.getOrNull()
                if (cached != null) {
                    Log.d(tag, "List fresh (${age / 1000}s old), serving Room cache")
                    return@withLock Result.success(
                        TyphoonFeed(
                            typhoons = cached.resolved(now),
                            fromCache = true,
                            fetchedAtEpochMs = lastFetch
                        )
                    )
                }
            }

            val cachedList = runCatching { localDataSource.getAll() }.getOrNull().orEmpty()
            val remote = fetchRemoteActive(cachedList.associateBy { it.id })
            remote.onSuccess { list ->
                runCatching { localDataSource.replaceAll(list) }
                    .onFailure { Log.w(tag, "Failed to cache active typhoons", it) }
                syncStore.lastListFetchAtMs = now
                return@withLock Result.success(
                    TyphoonFeed(typhoons = list.resolved(now), fromCache = false, fetchedAtEpochMs = now)
                )
            }

            if (cachedList.isNotEmpty()) {
                Log.i(tag, "Serving ${cachedList.size} typhoon(s) from Room cache")
                return@withLock Result.success(
                    TyphoonFeed(
                        typhoons = cachedList.resolved(now),
                        fromCache = true,
                        staleMessage = remote.exceptionOrNull()?.message
                            ?: "网络请求失败",
                        fetchedAtEpochMs = lastFetch ?: latestCachedAt()
                    )
                )
            }
            Result.failure(
                remote.exceptionOrNull()
                    ?: Exception("所有数据源均失败。请检查 local.properties（参考 local.properties.example）")
            )
        }

    override suspend fun getTyphoonDetail(id: String): Result<Typhoon> {
        val now = clock.nowMs()
        val cached = runCatching { localDataSource.getById(id) }.getOrNull()
        if (cached != null && cached.hasFullDetail()) {
            val cachedAt = runCatching { localDataSource.getCachedAtMs(id) }.getOrNull()
            val age = cachedAt?.let { now - it }
            if (age != null && age >= 0 && age < DETAIL_TTL_MS) {
                Log.d(tag, "Detail $id fresh (${age / 1000}s old), serving Room cache")
                return Result.success(TyphoonActivity.resolve(cached, now))
            }
        }

        val remote = fetchRemoteDetail(id)
        remote.onSuccess { detail ->
            runCatching { localDataSource.upsert(detail) }
                .onFailure { Log.w(tag, "Failed to cache typhoon detail $id", it) }
            return Result.success(TyphoonActivity.resolve(detail, now))
        }

        if (cached != null) {
            Log.i(tag, "Serving typhoon detail $id from Room cache")
            return Result.success(TyphoonActivity.resolve(cached, now))
        }
        return remote
    }

    private fun List<Typhoon>.resolved(now: Long): List<Typhoon> =
        map { TyphoonActivity.resolve(it, now) }

    private suspend fun latestCachedAt(): Long? =
        runCatching { localDataSource.latestCachedAtMs() }.getOrNull()

    /** A list-only snapshot has a single synthesized point and no forecast. */
    private fun Typhoon.hasFullDetail(): Boolean =
        points.size > 1 || forecastPoints.isNotEmpty()

    private suspend fun fetchRemoteActive(cachedById: Map<String, Typhoon>): Result<List<Typhoon>> {
        val errors = ArrayList<String>()

        if (juheKey.isBlank()) {
            errors.add("聚合 JUHE_KEY 未配置")
        } else {
            val juhe = fetchFromJuhe(cachedById)
            val data = juhe.typhoons
            if (data != null) {
                return Result.success(data)
            }
            juhe.error?.let { errors.add(it) }
        }

        if (!qWeatherConfigured) {
            errors.add("和风凭证未配置（QWEATHER_API_KEY 或 JWT）")
        } else {
            val qWeather = fetchFromQWeather()
            val data = qWeather.typhoons
            if (data != null) {
                return Result.success(data)
            }
            qWeather.error?.let { errors.add(it) }
        }

        val detail = if (errors.isEmpty()) {
            "所有数据源均失败"
        } else {
            errors.joinToString("；")
        }
        return Result.failure(
            Exception("$detail。请检查 local.properties（参考 local.properties.example）")
        )
    }

    private suspend fun fetchRemoteDetail(id: String): Result<Typhoon> {
        if (!id.startsWith("NP_") && juheKey.isNotBlank()) {
            try {
                val response = juheApi.getTyphoonDetail(juheKey, id)
                if (response.errorCode == 0 && response.result?.data != null) {
                    return Result.success(response.result.data.toDomain())
                }
                Log.w(tag, "Juhe detail error: ${response.reason} (${response.errorCode})")
            } catch (e: Exception) {
                Log.e(tag, "Juhe detail request failed", e)
            }
        }

        if (!qWeatherConfigured) {
            return Result.failure(Exception("无法获取台风详情: $id（和风凭证未配置）"))
        }

        // Keep the caller/nav id stable. QWeather storm id is only for the HTTP path;
        // returning NP_* here used to desync Detail route matching (flash → endless loading).
        val stormId = if (id.startsWith("NP_")) id else "NP_${id.takeLast(4)}"
        try {
            val track = qWeatherApi.getStormTrack(stormId)
            if (track.code == "200") {
                val forecast = runCatching { qWeatherApi.getStormForecast(stormId) }.getOrNull()
                val infoNow = track.now
                val typhoon = Typhoon(
                    id = id,
                    name = stormId,
                    englishName = "",
                    status = if (track.isActive == "1") "active" else "dissipated",
                    strong = infoNow?.type?.let { qWeatherTypeToStrong(it) }.orEmpty(),
                    points = track.track.map { it.toDomain() }.ifEmpty {
                        listOfNotNull(infoNow?.toDomain())
                    },
                    forecastPoints = forecast?.forecast?.map { it.toDomain() }.orEmpty()
                )
                return Result.success(typhoon)
            }
            Log.e(tag, "QWeather track error code: ${track.code}")
        } catch (e: Exception) {
            Log.e(tag, "QWeather track request failed", e)
            return Result.failure(Exception("无法获取台风详情: $id（${e.message}）"))
        }

        return Result.failure(Exception("无法获取台风详情: $id"))
    }

    private suspend fun fetchFromJuhe(cachedById: Map<String, Typhoon>): FetchOutcome {
        return try {
            val listResponse = juheApi.getActiveTyphoons(juheKey)
            when (listResponse.errorCode) {
                0 -> {
                    val active = listResponse.result?.data.orEmpty()
                    if (active.isEmpty()) {
                        Log.d(tag, "Juhe: no active typhoons")
                        return FetchOutcome(typhoons = emptyList())
                    }
                    var reused = 0
                    val typhoons = active.map { info ->
                        val cached = cachedById[info.tfid]
                        if (cached != null && info.endtime.isNotBlank() &&
                            cached.endTime == info.endtime && cached.hasFullDetail()
                        ) {
                            // Same storm, same list update time → detail unchanged; skip the call.
                            reused++
                            return@map cached
                        }
                        try {
                            val detail = juheApi.getTyphoonDetail(juheKey, info.tfid)
                            if (detail.errorCode == 0 && detail.result?.data != null) {
                                // Key the cached detail by the list's update time.
                                detail.result.data.toDomain().let {
                                    if (info.endtime.isNotBlank()) it.copy(endTime = info.endtime) else it
                                }
                            } else {
                                info.toDomain()
                            }
                        } catch (e: Exception) {
                            Log.w(tag, "Juhe detail failed for ${info.tfid}, using list snapshot", e)
                            info.toDomain()
                        }
                    }
                    Log.d(tag, "Fetched ${typhoons.size} typhoon(s) from Juhe, reused $reused cached detail(s)")
                    FetchOutcome(typhoons = typhoons)
                }
                10001, 10002 -> {
                    val msg = "聚合 KEY 无效（${listResponse.errorCode}: ${listResponse.reason}）"
                    Log.e(tag, msg)
                    FetchOutcome(error = msg)
                }
                10012, 10013, 10022, 10023 -> {
                    val msg = "聚合配额受限（${listResponse.errorCode}）"
                    Log.w(tag, "$msg, fallback to QWeather")
                    FetchOutcome(error = msg)
                }
                else -> {
                    val msg = "聚合错误（${listResponse.errorCode}: ${listResponse.reason}）"
                    Log.e(tag, msg)
                    FetchOutcome(error = msg)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Juhe request failed", e)
            FetchOutcome(error = "聚合请求失败: ${e.message}")
        }
    }

    private suspend fun fetchFromQWeather(): FetchOutcome {
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR).toString()
            val listResponse = qWeatherApi.getStormList(basin = "NP", year = year)
            if (listResponse.code != "200") {
                val msg = "和风列表错误 code=${listResponse.code}"
                Log.e(tag, msg)
                return FetchOutcome(error = msg)
            }

            val activeStorms = listResponse.storm.filter { it.isActive == "1" }
            if (activeStorms.isEmpty()) {
                Log.d(tag, "QWeather: no active storms in $year")
                return FetchOutcome(typhoons = emptyList())
            }

            val typhoons = activeStorms.map { storm ->
                val track = runCatching { qWeatherApi.getStormTrack(storm.id) }.getOrNull()
                val forecast = runCatching { qWeatherApi.getStormForecast(storm.id) }.getOrNull()
                storm.toDomain(
                    track = track?.track.orEmpty(),
                    now = track?.now,
                    forecast = forecast?.forecast.orEmpty()
                )
            }
            Log.d(tag, "Fetched ${typhoons.size} storm(s) from QWeather")
            FetchOutcome(typhoons = typhoons)
        } catch (e: Exception) {
            Log.e(tag, "QWeather request failed", e)
            FetchOutcome(error = "和风请求失败: ${e.message}")
        }
    }

    companion object {
        const val LIST_TTL_MS: Long = 10L * 60 * 1000
        const val MIN_FORCE_INTERVAL_MS: Long = 60L * 1000
        const val DETAIL_TTL_MS: Long = 30L * 60 * 1000
    }
}
