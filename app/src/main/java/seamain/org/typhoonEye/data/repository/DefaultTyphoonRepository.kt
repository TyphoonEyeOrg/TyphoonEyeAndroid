package seamain.org.typhoonEye.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import retrofit2.Response
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherAuthInterceptor
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.isRelayStale
import seamain.org.typhoonEye.data.api.parseRetryAfterUntilMs
import seamain.org.typhoonEye.data.api.relayLimitKind
import seamain.org.typhoonEye.data.api.RELAY_STALE_HEADER
import seamain.org.typhoonEye.data.api.RETRY_AFTER_HEADER
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.QWeatherStormInfo
import seamain.org.typhoonEye.data.model.qWeatherTypeToStrong
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.model.RelayLimitKind
import seamain.org.typhoonEye.domain.model.RelayLimitedException
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
 * 5) HTTP 429/503 and `Retry-After` hold the next refresh (including pull-to-refresh and the
 *    live-update worker) until the header elapses. `X-Relay-Stale: 1` is cached data, not live.
 * 6) QWeather track and forecast calls run together, at most [QWEATHER_MAX_IN_FLIGHT] at once.
 *    One storm's failure does not drop the others. Those calls still record 429/503,
 *    `Retry-After`, and `X-Relay-Stale` on [FeedSyncStore].
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

    private val relayThrottleLock = Any()

    private class FetchOutcome(
        val typhoons: List<Typhoon>? = null,
        val error: String? = null,
        val stale: Boolean = false
    )

    private class QWeatherStormPiece(
        val typhoon: Typhoon,
        val stale: Boolean
    )

    private class RemoteList(
        val typhoons: List<Typhoon>,
        val stale: Boolean
    )

    override suspend fun getCachedFeed(): TyphoonFeed? {
        val cached = runCatchingCancellable { localDataSource.getAll() }.getOrNull().orEmpty()
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
            clearExpiredRelayThrottle(now)
            val blocked = activeRetryUntil(now)
            if (blocked != null) {
                Log.i(tag, "Relay Retry-After active until $blocked, skipping network")
                return@withLock throttledFeed(now, blocked)
            }
            val lastFetch = syncStore.lastListFetchAtMs
            val minAge = if (forceRefresh) MIN_FORCE_INTERVAL_MS else LIST_TTL_MS
            val age = lastFetch?.let { now - it }
            if (age != null && age >= 0 && age < minAge) {
                val cached = runCatchingCancellable { localDataSource.getAll() }.getOrNull()
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

            val cachedList = runCatchingCancellable { localDataSource.getAll() }.getOrNull().orEmpty()
            val remote = fetchRemoteActive(cachedList.associateBy { it.id })
            remote.onSuccess { list ->
                runCatchingCancellable { localDataSource.replaceAll(list.typhoons) }
                    .onFailure { Log.w(tag, "Failed to cache active typhoons", it) }
                syncStore.lastListFetchAtMs = now
                if (list.stale) {
                    Log.i(tag, "Relay marked the list stale; showing the cache chip")
                } else {
                    clearRelayThrottle()
                }
                val throttle = currentThrottle()
                return@withLock Result.success(
                    TyphoonFeed(
                        typhoons = list.typhoons.resolved(now),
                        fromCache = list.stale,
                        fetchedAtEpochMs = now,
                        relayLimit = if (list.stale) throttle.kind else null,
                        retryAfterUntilEpochMs = if (list.stale) throttle.untilMs else null
                    )
                )
            }

            val error = remote.exceptionOrNull()
            val limited = error as? RelayLimitedException
            if (cachedList.isNotEmpty()) {
                Log.i(tag, "Serving ${cachedList.size} typhoon(s) from Room cache")
                return@withLock Result.success(
                    TyphoonFeed(
                        typhoons = cachedList.resolved(now),
                        fromCache = true,
                        staleMessage = if (limited == null) {
                            error?.message ?: "网络请求失败"
                        } else {
                            null
                        },
                        fetchedAtEpochMs = lastFetch ?: latestCachedAt(),
                        relayLimit = limited?.kind,
                        retryAfterUntilEpochMs = limited?.retryAfterUntilEpochMs
                    )
                )
            }
            Result.failure(
                error ?: Exception("所有数据源均失败。请检查 local.properties（参考 local.properties.example）")
            )
        }

    override suspend fun getTyphoonDetail(id: String): Result<Typhoon> {
        val now = clock.nowMs()
        val cached = runCatchingCancellable { localDataSource.getById(id) }.getOrNull()
        if (cached != null && cached.hasFullDetail()) {
            val cachedAt = runCatchingCancellable { localDataSource.getCachedAtMs(id) }.getOrNull()
            val age = cachedAt?.let { now - it }
            if (age != null && age >= 0 && age < DETAIL_TTL_MS) {
                Log.d(tag, "Detail $id fresh (${age / 1000}s old), serving Room cache")
                return Result.success(TyphoonActivity.resolve(cached, now))
            }
        }

        val blocked = activeRetryUntil(now)
        if (blocked != null) {
            Log.i(tag, "Detail $id skipped; relay Retry-After still active")
            if (cached != null) return Result.success(TyphoonActivity.resolve(cached, now))
            return Result.failure(RelayLimitedException(currentThrottle().kind ?: RelayLimitKind.Busy, blocked))
        }

        val remote = fetchRemoteDetail(id)
        remote.onSuccess { detail ->
            runCatchingCancellable { localDataSource.upsert(detail) }
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
        runCatchingCancellable { localDataSource.latestCachedAtMs() }.getOrNull()

    /** A list-only snapshot has a single synthesized point and no forecast. */
    private fun Typhoon.hasFullDetail(): Boolean =
        points.size > 1 || forecastPoints.isNotEmpty()

    private suspend fun fetchRemoteActive(cachedById: Map<String, Typhoon>): Result<RemoteList> {
        val errors = ArrayList<String>()

        if (juheKey.isBlank()) {
            errors.add("聚合 JUHE_KEY 未配置")
        } else {
            val juhe = fetchFromJuhe(cachedById)
            val data = juhe.typhoons
            if (data != null) {
                return Result.success(RemoteList(data, juhe.stale))
            }
            juhe.error?.let { errors.add(it) }
        }

        if (!qWeatherConfigured) {
            errors.add("和风凭证未配置（QWEATHER_API_KEY 或 JWT）")
        } else {
            val qWeather = fetchFromQWeather()
            val data = qWeather.typhoons
            if (data != null) {
                return Result.success(RemoteList(data, qWeather.stale))
            }
            qWeather.error?.let { errors.add(it) }
        }

        currentThrottle().kind?.let { kind ->
            return Result.failure(
                RelayLimitedException(kind, currentThrottle().untilMs)
            )
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
                val http = juheApi.getTyphoonDetail(juheKey, id)
                val relay = http.relayHttp()
                if (relay.limit != null) {
                    rememberRelayThrottle(relay.limit, relay.retryAfterUntilMs)
                    Log.w(tag, "Juhe detail limited HTTP ${relay.code}")
                } else if (http.isSuccessful) {
                    val response = http.body()
                    val data = response?.result?.data
                    if (response?.errorCode == 0 && data != null) {
                        return Result.success(data.toDomain())
                    }
                    Log.w(tag, "Juhe detail error: ${response?.reason} (${response?.errorCode})")
                } else {
                    Log.w(tag, "Juhe detail HTTP ${http.code()}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Juhe detail request failed", e)
            }
        }

        if (!qWeatherConfigured) {
            return limitedOr(Exception("无法获取台风详情: $id（和风凭证未配置）"))
        }

        // Keep the caller/nav id stable. QWeather storm id is only for the HTTP path;
        // returning NP_* here used to desync Detail route matching (flash → endless loading).
        val stormId = if (id.startsWith("NP_")) id else "NP_${id.takeLast(4)}"
        try {
            val trackHttp = qWeatherApi.getStormTrack(stormId)
            val trackRelay = trackHttp.relayHttp()
            val track = trackHttp.body()
            if (trackRelay.limit != null) {
                rememberRelayThrottle(trackRelay.limit, trackRelay.retryAfterUntilMs)
                Log.w(tag, "QWeather track limited HTTP ${trackRelay.code}")
            } else if (trackHttp.isSuccessful && track != null && track.code == "200") {
                val forecastHttp = runCatchingCancellable { qWeatherApi.getStormForecast(stormId) }.getOrNull()
                forecastHttp?.relayHttp()?.let { forecastRelay ->
                    if (forecastRelay.limit != null) {
                        rememberRelayThrottle(forecastRelay.limit, forecastRelay.retryAfterUntilMs)
                    }
                }
                val forecast = forecastHttp?.takeIf { it.isSuccessful }?.body()
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
            } else if (trackHttp.isSuccessful) {
                Log.e(tag, "QWeather track error code: ${track?.code}")
            } else {
                Log.e(tag, "QWeather track HTTP ${trackHttp.code()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "QWeather track request failed", e)
            return limitedOr(Exception("无法获取台风详情: $id（${e.message}）"))
        }

        return limitedOr(Exception("无法获取台风详情: $id"))
    }

    private suspend fun fetchFromJuhe(cachedById: Map<String, Typhoon>): FetchOutcome {
        return try {
            val http = juheApi.getActiveTyphoons(juheKey)
            val relay = http.relayHttp()
            if (!http.isSuccessful) {
                return httpFailure("聚合", relay)
            }
            val listResponse = http.body() ?: return FetchOutcome(error = "聚合响应为空")
            when (listResponse.errorCode) {
                0 -> {
                    val active = listResponse.result?.data.orEmpty()
                    if (active.isEmpty()) {
                        Log.d(tag, "Juhe: no active typhoons")
                        return FetchOutcome(typhoons = emptyList(), stale = relay.stale)
                    }
                    var reused = 0
                    var stale = relay.stale
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
                            val detailHttp = juheApi.getTyphoonDetail(juheKey, info.tfid)
                            val detailRelay = detailHttp?.relayHttp()
                            if (detailRelay?.stale == true) stale = true
                            if (detailRelay?.limit != null) {
                                rememberRelayThrottle(detailRelay.limit, detailRelay.retryAfterUntilMs)
                                return@map info.toDomain()
                            }
                            val detail = detailHttp?.takeIf { it.isSuccessful }?.body()
                            if (detail?.errorCode == 0 && detail.result?.data != null) {
                                // Key the cached detail by the list's update time.
                                detail.result.data.toDomain().let {
                                    if (info.endtime.isNotBlank()) it.copy(endTime = info.endtime) else it
                                }
                            } else {
                                info.toDomain()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(tag, "Juhe detail failed for ${info.tfid}, using list snapshot", e)
                            info.toDomain()
                        }
                    }
                    Log.d(tag, "Fetched ${typhoons.size} typhoon(s) from Juhe, reused $reused cached detail(s)")
                    FetchOutcome(typhoons = typhoons, stale = stale)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Juhe request failed", e)
            FetchOutcome(error = "聚合请求失败: ${e.message}")
        }
    }

    private suspend fun fetchFromQWeather(): FetchOutcome {
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR).toString()
            val http = qWeatherApi.getStormList(basin = "NP", year = year)
            val relay = http.relayHttp()
            if (!http.isSuccessful) {
                return httpFailure("和风", relay)
            }
            val listResponse = http.body() ?: return FetchOutcome(error = "和风响应为空")
            if (listResponse.code != "200") {
                val msg = "和风列表错误 code=${listResponse.code}"
                Log.e(tag, msg)
                return FetchOutcome(error = msg)
            }

            val activeStorms = listResponse.storm.filter { it.isActive == "1" }
            if (activeStorms.isEmpty()) {
                Log.d(tag, "QWeather: no active storms in $year")
                return FetchOutcome(typhoons = emptyList(), stale = relay.stale)
            }

            val permits = Semaphore(QWEATHER_MAX_IN_FLIGHT)
            val pieces = supervisorScope {
                activeStorms.map { storm ->
                    async { fetchQWeatherStorm(storm, permits) }
                }.awaitAll()
            }
            Log.d(tag, "Fetched ${pieces.size} storm(s) from QWeather")
            FetchOutcome(
                typhoons = pieces.map { it.typhoon },
                stale = relay.stale || pieces.any { it.stale }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "QWeather request failed", e)
            FetchOutcome(error = "和风请求失败: ${e.message}")
        }
    }

    /**
     * Track and forecast share [permits] with every other active storm, so at most
     * [QWEATHER_MAX_IN_FLIGHT] QWeather calls are on the wire. A failure here becomes
     * a list-only storm; it does not cancel its siblings or the list.
     */
    private suspend fun fetchQWeatherStorm(
        storm: QWeatherStormInfo,
        permits: Semaphore
    ): QWeatherStormPiece {
        return try {
            val (trackHttp, forecastHttp) = coroutineScope {
                val trackDeferred = async {
                    permits.withPermit {
                        runCatchingCancellable { qWeatherApi.getStormTrack(storm.id) }.getOrNull()
                    }
                }
                val forecastDeferred = async {
                    permits.withPermit {
                        runCatchingCancellable { qWeatherApi.getStormForecast(storm.id) }.getOrNull()
                    }
                }
                trackDeferred.await() to forecastDeferred.await()
            }
            var stale = false
            trackHttp?.relayHttp()?.let { relay ->
                if (relay.stale) stale = true
                if (relay.limit != null) {
                    rememberRelayThrottle(relay.limit, relay.retryAfterUntilMs)
                }
            }
            forecastHttp?.relayHttp()?.let { relay ->
                if (relay.stale) stale = true
                if (relay.limit != null) {
                    rememberRelayThrottle(relay.limit, relay.retryAfterUntilMs)
                }
            }
            val track = trackHttp?.takeIf { it.isSuccessful }?.body()
            val forecast = forecastHttp?.takeIf { it.isSuccessful }?.body()
            QWeatherStormPiece(
                typhoon = storm.toDomain(
                    track = track?.track.orEmpty(),
                    now = track?.now,
                    forecast = forecast?.forecast.orEmpty()
                ),
                stale = stale
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(tag, "QWeather storm ${storm.id} failed", e)
            QWeatherStormPiece(
                typhoon = storm.toDomain(track = emptyList(), now = null, forecast = emptyList()),
                stale = false
            )
        }
    }

    private fun httpFailure(source: String, relay: RelayHttp): FetchOutcome {
        if (relay.limit != null) {
            rememberRelayThrottle(relay.limit, relay.retryAfterUntilMs)
            Log.w(tag, "$source HTTP ${relay.code} retryAt=${relay.retryAfterUntilMs}")
            return FetchOutcome(error = "$source HTTP ${relay.code}")
        }
        val msg = "$source HTTP ${relay.code}"
        Log.e(tag, msg)
        return FetchOutcome(error = msg)
    }

    /** Prefer a relay limit recorded during this attempt over a generic failure. */
    private fun limitedOr(fallback: Exception): Result<Typhoon> {
        val throttle = currentThrottle()
        val kind = throttle.kind
        return if (kind != null) {
            Result.failure(RelayLimitedException(kind, throttle.untilMs))
        } else {
            Result.failure(fallback)
        }
    }

    private data class RelayHttp(
        val code: Int,
        val limit: RelayLimitKind?,
        val retryAfterUntilMs: Long?,
        val stale: Boolean
    )

    private fun <T> Response<T>.relayHttp(): RelayHttp {
        val until = parseRetryAfterUntilMs(headers()[RETRY_AFTER_HEADER], clock.nowMs())
        val limit = if (isSuccessful) null else relayLimitKind(code())
        return RelayHttp(
            code = code(),
            limit = limit,
            retryAfterUntilMs = until,
            stale = isSuccessful && isRelayStale(headers()[RELAY_STALE_HEADER])
        )
    }

    private data class ThrottleSnapshot(val kind: RelayLimitKind?, val untilMs: Long?)

    /** Non-null when a `Retry-After` is still in the future. Does not clear the store. */
    private fun activeRetryUntil(now: Long): Long? = synchronized(relayThrottleLock) {
        syncStore.relayRetryAfterUntilMs?.takeIf { now < it }
    }

    /**
     * Drop an elapsed deadline (or a 429/503 that had no `Retry-After`) so the next
     * ordinary failure is not labeled with that old limit. List refresh owns this;
     * a detail read must not clear a deadline the list still needs.
     */
    private fun clearExpiredRelayThrottle(now: Long) {
        synchronized(relayThrottleLock) {
            val until = syncStore.relayRetryAfterUntilMs
            if (until != null && now < until) return
            if (until != null || syncStore.relayLimit != null) {
                syncStore.relayRetryAfterUntilMs = null
                syncStore.relayLimit = null
            }
        }
    }

    private fun currentThrottle(): ThrottleSnapshot = synchronized(relayThrottleLock) {
        ThrottleSnapshot(syncStore.relayLimit, syncStore.relayRetryAfterUntilMs)
    }

    private fun rememberRelayThrottle(kind: RelayLimitKind, untilMs: Long?) {
        synchronized(relayThrottleLock) {
            val existingUntil = syncStore.relayRetryAfterUntilMs
            if (untilMs != null && (existingUntil == null || untilMs > existingUntil)) {
                syncStore.relayRetryAfterUntilMs = untilMs
                syncStore.relayLimit = kind
                return
            }
            if (existingUntil == null) {
                syncStore.relayLimit = kind
            }
        }
    }

    private fun clearRelayThrottle() {
        synchronized(relayThrottleLock) {
            syncStore.relayRetryAfterUntilMs = null
            syncStore.relayLimit = null
        }
    }

    private suspend fun throttledFeed(now: Long, until: Long): Result<TyphoonFeed> {
        val kind = currentThrottle().kind
        val cached = runCatchingCancellable { localDataSource.getAll() }.getOrNull().orEmpty()
        if (cached.isNotEmpty()) {
            return Result.success(
                TyphoonFeed(
                    typhoons = cached.resolved(now),
                    fromCache = true,
                    fetchedAtEpochMs = syncStore.lastListFetchAtMs ?: latestCachedAt(),
                    relayLimit = kind,
                    retryAfterUntilEpochMs = until
                )
            )
        }
        return Result.failure(
            RelayLimitedException(kind ?: RelayLimitKind.Busy, until)
        )
    }

    /**
     * Like [runCatching], but cancellation must keep propagating so a superseded
     * refresh releases [listMutex] instead of being reported as a network error.
     */
    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    companion object {
        const val LIST_TTL_MS: Long = 10L * 60 * 1000
        const val MIN_FORCE_INTERVAL_MS: Long = 60L * 1000
        const val DETAIL_TTL_MS: Long = 30L * 60 * 1000

        /** Track and forecast requests in one QWeather list fetch. */
        const val QWEATHER_MAX_IN_FLIGHT: Int = 3
    }
}
