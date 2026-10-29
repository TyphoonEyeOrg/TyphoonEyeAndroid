package seamain.org.typhoonEye.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import seamain.org.typhoonEye.data.api.DataSourceConfig
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.api.RelayHeaderCapture
import seamain.org.typhoonEye.data.api.RelayHeaders
import seamain.org.typhoonEye.data.local.TyphoonLocalDataSource
import seamain.org.typhoonEye.data.model.qWeatherTypeToStrong
import seamain.org.typhoonEye.data.model.toDomain
import seamain.org.typhoonEye.data.sync.FeedSyncStore
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.model.TyphoonDetailUnavailableError
import seamain.org.typhoonEye.domain.model.TyphoonFeed
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.util.TyphoonActivity
import seamain.org.typhoonEye.domain.util.WallClock
import java.util.Calendar
import javax.inject.Inject

/**
 * Cache-first typhoon repository:
 * 1) Active list is served from Room while younger than [LIST_TTL_MS]; pull-to-refresh may
 *    force a fetch, but no more often than [MIN_FORCE_INTERVAL_MS].
 * 2) Remote: prefer Juhe, fallback QWeather. A Juhe detail is only re-fetched when the list's
 *    update time (`endtime`) for that storm changed, so a refresh costs 1 + changed storms.
 * 3) On remote failure → serve Room cache with a typed stale reason.
 * 4) Relay `Retry-After` skips that source until it expires (pull-to-refresh included).
 *    `X-Relay-Stale: 1` is saved, but the feed is marked cached so the UI does not call it live.
 * 5) Status is resolved client-side ([TyphoonActivity]): no observation for 24 h → dissipated.
 *
 * Failures are typed ([TyphoonDataError]); the UI resolves localized text.
 * A direct-mode build without any API key (e.g. a local GitHub-flavor build without
 * `local.properties` keys) yields [NoDataSourceConfiguredError] without touching the network.
 * The F-Droid build goes through TyphoonEye's relay ([DataSourceConfig.viaRelay]): both
 * sources are always available there and the app sends no key.
 */
class DefaultTyphoonRepository @Inject constructor(
    private val juheApi: JuheTyphoonApi,
    private val qWeatherApi: QWeatherTyphoonApi,
    private val config: DataSourceConfig,
    private val localDataSource: TyphoonLocalDataSource,
    private val syncStore: FeedSyncStore,
    private val clock: WallClock,
    private val relayHeaders: RelayHeaderCapture
) : TyphoonRepository {

    private val qWeatherConfigured: Boolean
        get() = config.qWeatherEnabled

    private val juheConfigured: Boolean
        get() = config.juheEnabled

    /** Null in relay mode: no `key` parameter is sent. */
    private val juheKey: String?
        get() = config.juheKeyParam

    /** True when at least one remote data source is usable in this build (always via relay). */
    val hasAnyDataSource: Boolean
        get() = config.hasAnyDataSource

    private val tag = "TyphoonRepository"

    /** Serialises list loads so UI + background worker never fetch in parallel. */
    private val listMutex = Mutex()

    private class FetchOutcome(
        val typhoons: List<Typhoon>? = null,
        val failure: SourceFailure? = null,
        /** List response carried `X-Relay-Stale: 1` (older data, not a live observation). */
        val relayStale: Boolean = false
    )

    private class RemoteActive(
        val typhoons: List<Typhoon>,
        val relayStale: Boolean
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
            remote.onSuccess { loaded ->
                val list = loaded.typhoons
                runCatchingCancellable { localDataSource.replaceAll(list) }
                    .onFailure { Log.w(tag, "Failed to cache active typhoons", it) }
                syncStore.lastListFetchAtMs = now
                return@withLock Result.success(
                    TyphoonFeed(
                        typhoons = list.resolved(now),
                        // Stale relay bodies reuse the cache chip; they are not live observations.
                        fromCache = loaded.relayStale,
                        fetchedAtEpochMs = now
                    )
                )
            }

            val error = remote.exceptionOrNull() as? TyphoonDataError
                ?: DataSourcesFailedError(emptyList())
            if (cachedList.isNotEmpty()) {
                Log.i(tag, "Serving ${cachedList.size} typhoon(s) from Room cache (${error.message})")
                return@withLock Result.success(
                    TyphoonFeed(
                        typhoons = cachedList.resolved(now),
                        fromCache = true,
                        staleReason = error,
                        fetchedAtEpochMs = lastFetch ?: latestCachedAt()
                    )
                )
            }
            Result.failure(error)
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

    private suspend fun fetchRemoteActive(cachedById: Map<String, Typhoon>): Result<RemoteActive> {
        if (!hasAnyDataSource) {
            Log.i(tag, "No data source configured in this build (no relay, no JUHE_KEY / QWeather credentials)")
            return Result.failure(NoDataSourceConfiguredError())
        }

        val failures = ArrayList<SourceFailure>()

        if (juheConfigured) {
            val blocked = relayBlock(DataSource.Juhe)
            if (blocked != null) {
                Log.d(tag, "Juhe skipped until ${blocked.retryAfterEpochMs}")
                failures.add(blocked)
            } else {
                val juhe = fetchFromJuhe(cachedById)
                juhe.typhoons?.let { return Result.success(RemoteActive(it, juhe.relayStale)) }
                juhe.failure?.let { failures.add(it) }
            }
        }

        if (qWeatherConfigured) {
            val blocked = relayBlock(DataSource.QWeather)
            if (blocked != null) {
                Log.d(tag, "QWeather skipped until ${blocked.retryAfterEpochMs}")
                failures.add(blocked)
            } else {
                val qWeather = fetchFromQWeather()
                qWeather.typhoons?.let { return Result.success(RemoteActive(it, qWeather.relayStale)) }
                qWeather.failure?.let { failures.add(it) }
            }
        }

        return Result.failure(DataSourcesFailedError(failures))
    }

    private suspend fun fetchRemoteDetail(id: String): Result<Typhoon> {
        if (!hasAnyDataSource) {
            return Result.failure(NoDataSourceConfiguredError())
        }
        val failures = ArrayList<SourceFailure>()

        if (!id.startsWith("NP_") && juheConfigured) {
            val blocked = relayBlock(DataSource.Juhe)
            if (blocked != null) {
                failures.add(blocked)
            } else try {
                val response = juheApi.getTyphoonDetail(juheKey, id)
                if (response.errorCode == 0 && response.result?.data != null) {
                    return Result.success(response.result.data.toDomain())
                }
                Log.w(tag, "Juhe detail error: ${response.reason} (${response.errorCode})")
                failures.add(juheFailure(response.errorCode, response.reason))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "Juhe detail request failed", e)
                val failure = exceptionFailure(DataSource.Juhe, e)
                noteFailureThrottle(DataSource.Juhe, failure)
                failures.add(failure)
            }
        }

        if (!qWeatherConfigured) {
            return Result.failure(TyphoonDetailUnavailableError(id, failures))
        }

        relayBlock(DataSource.QWeather)?.let { blocked ->
            failures.add(blocked)
            return Result.failure(TyphoonDetailUnavailableError(id, failures))
        }

        // Keep the caller/nav id stable. QWeather storm id is only for the HTTP path;
        // returning NP_* here used to desync Detail route matching (flash → endless loading).
        val stormId = if (id.startsWith("NP_")) id else "NP_${id.takeLast(4)}"
        try {
            val track = qWeatherApi.getStormTrack(stormId)
            if (track.code == "200") {
                val forecast = runCatchingCancellable { qWeatherApi.getStormForecast(stormId) }.getOrNull()
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
            failures.add(qWeatherFailure(track.code))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "QWeather track request failed", e)
            val failure = exceptionFailure(DataSource.QWeather, e)
            noteFailureThrottle(DataSource.QWeather, failure)
            failures.add(failure)
        }

        return Result.failure(TyphoonDetailUnavailableError(id, failures))
    }

    private suspend fun fetchFromJuhe(cachedById: Map<String, Typhoon>): FetchOutcome {
        return try {
            val listResponse = juheApi.getActiveTyphoons(juheKey)
            val relay = relayHeaders.consumeEndingWith(RelayHeaders.JUHE_ACTIVE_SUFFIX)
            noteRelayResponse(DataSource.Juhe, relay)
            when (listResponse.errorCode) {
                0 -> {
                    val active = listResponse.result?.data.orEmpty()
                    if (active.isEmpty()) {
                        Log.d(tag, "Juhe: no active typhoons")
                        return FetchOutcome(typhoons = emptyList(), relayStale = relay?.stale == true)
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
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(tag, "Juhe detail failed for ${info.tfid}, using list snapshot", e)
                            info.toDomain()
                        }
                    }
                    Log.d(tag, "Fetched ${typhoons.size} typhoon(s) from Juhe, reused $reused cached detail(s)")
                    FetchOutcome(typhoons = typhoons, relayStale = relay?.stale == true)
                }
                else -> {
                    val failure = juheFailure(listResponse.errorCode, listResponse.reason)
                    Log.w(tag, "Juhe list failed: ${failure.describe()}, falling back")
                    FetchOutcome(failure = failure)
                }
            }
        } catch (e: CancellationException) {
            relayHeaders.consumeEndingWith(RelayHeaders.JUHE_ACTIVE_SUFFIX)
            throw e
        } catch (e: Exception) {
            relayHeaders.consumeEndingWith(RelayHeaders.JUHE_ACTIVE_SUFFIX)
            Log.e(tag, "Juhe request failed", e)
            val failure = exceptionFailure(DataSource.Juhe, e)
            noteFailureThrottle(DataSource.Juhe, failure)
            FetchOutcome(failure = failure)
        }
    }

    private suspend fun fetchFromQWeather(): FetchOutcome {
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR).toString()
            val listResponse = qWeatherApi.getStormList(basin = "NP", year = year)
            val relay = relayHeaders.consumeEndingWith(RelayHeaders.QWEATHER_LIST_SUFFIX)
            noteRelayResponse(DataSource.QWeather, relay)
            if (listResponse.code != "200") {
                Log.e(tag, "QWeather list error code=${listResponse.code}")
                return FetchOutcome(failure = qWeatherFailure(listResponse.code))
            }

            val activeStorms = listResponse.storm.filter { it.isActive == "1" }
            if (activeStorms.isEmpty()) {
                Log.d(tag, "QWeather: no active storms in $year")
                return FetchOutcome(typhoons = emptyList(), relayStale = relay?.stale == true)
            }

            val typhoons = activeStorms.map { storm ->
                val track = runCatchingCancellable { qWeatherApi.getStormTrack(storm.id) }.getOrNull()
                val forecast = runCatchingCancellable { qWeatherApi.getStormForecast(storm.id) }.getOrNull()
                storm.toDomain(
                    track = track?.track.orEmpty(),
                    now = track?.now,
                    forecast = forecast?.forecast.orEmpty()
                )
            }
            Log.d(tag, "Fetched ${typhoons.size} storm(s) from QWeather")
            FetchOutcome(typhoons = typhoons, relayStale = relay?.stale == true)
        } catch (e: CancellationException) {
            relayHeaders.consumeEndingWith(RelayHeaders.QWEATHER_LIST_SUFFIX)
            throw e
        } catch (e: Exception) {
            relayHeaders.consumeEndingWith(RelayHeaders.QWEATHER_LIST_SUFFIX)
            Log.e(tag, "QWeather request failed", e)
            val failure = exceptionFailure(DataSource.QWeather, e)
            noteFailureThrottle(DataSource.QWeather, failure)
            FetchOutcome(failure = failure)
        }
    }

    /**
     * Non-null when this source's relay `Retry-After` is still in the future.
     * The caller records it as a failure and does not open a socket.
     */
    private fun relayBlock(source: DataSource): SourceFailure? {
        val until = syncStore.relayNotBeforeEpochMs(source) ?: return null
        if (clock.nowMs() >= until) return null
        val code = syncStore.relayThrottleHttpCode(source)
        val kind = when (code) {
            "503" -> SourceFailureKind.RelayDailyQuota
            "429", null -> SourceFailureKind.RelayBusy
            else -> SourceFailureKind.ApiError
        }
        return SourceFailure(source, kind, code = code, retryAfterEpochMs = until)
    }

    /** HTTP 200 (including `X-Relay-Stale`) may still carry `Retry-After`. A missing snapshot leaves any stored wait alone. */
    private fun noteRelayResponse(source: DataSource, relay: RelayHeaderCapture.Snapshot?) {
        if (relay == null) return
        val until = RelayHeaders.retryAfterEpochMs(relay.retryAfter, clock.nowMs())
        if (until != null) {
            syncStore.setRelayThrottle(source, until, httpCode = null)
        } else {
            syncStore.clearRelayThrottle(source)
        }
    }

    private fun noteFailureThrottle(source: DataSource, failure: SourceFailure) {
        val until = failure.retryAfterEpochMs
        if (until != null) {
            syncStore.setRelayThrottle(source, until, failure.code)
        } else {
            syncStore.clearRelayThrottle(source)
        }
    }

    private fun juheFailure(errorCode: Int, reason: String?): SourceFailure {
        val kind = when (errorCode) {
            10001, 10002 -> keyRejected()
            10012, 10013, 10022, 10023 -> SourceFailureKind.QuotaExceeded
            else -> SourceFailureKind.ApiError
        }
        return SourceFailure(DataSource.Juhe, kind, code = errorCode.toString(), detail = reason)
    }

    /** QWeather v7 status codes: 401/403 auth, 402/429 quota. */
    private fun qWeatherFailure(code: String?): SourceFailure {
        val kind = when (code) {
            "401", "403" -> keyRejected()
            "402", "429" -> SourceFailureKind.QuotaExceeded
            else -> SourceFailureKind.ApiError
        }
        return SourceFailure(DataSource.QWeather, kind, code = code)
    }

    /**
     * A rejected key is the user's to fix only in direct mode. Through the relay the key is
     * ours, so the user sees a plain service error instead of "API key rejected".
     */
    private fun keyRejected(): SourceFailureKind =
        if (config.viaRelay) SourceFailureKind.ApiError else SourceFailureKind.InvalidKey

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

    /**
     * HTTP errors from the relay keep their status: 429 is "busy", 503 is the daily
     * limit, and a `Retry-After` header becomes [SourceFailure.retryAfterEpochMs].
     * Everything else (offline, timeout, TLS, parse error) is a network failure.
     * Cancellation is rethrown so a superseded refresh releases [listMutex].
     */
    private fun exceptionFailure(source: DataSource, e: Exception): SourceFailure {
        if (e is CancellationException) throw e
        return if (e is HttpException) {
            val code = e.code()
            val kind = when (code) {
                429 -> SourceFailureKind.RelayBusy
                503 -> SourceFailureKind.RelayDailyQuota
                401, 403 -> keyRejected()
                else -> SourceFailureKind.ApiError
            }
            val retryAfter = RelayHeaders.retryAfterEpochMs(
                e.response()?.headers()?.get(RelayHeaders.RETRY_AFTER),
                clock.nowMs()
            )
            SourceFailure(
                source,
                kind,
                code = code.toString(),
                detail = e.message(),
                retryAfterEpochMs = retryAfter
            )
        } else {
            SourceFailure(source, SourceFailureKind.Network, detail = e.message)
        }
    }

    companion object {
        const val LIST_TTL_MS: Long = 10L * 60 * 1000
        const val MIN_FORCE_INTERVAL_MS: Long = 60L * 1000
        const val DETAIL_TTL_MS: Long = 30L * 60 * 1000
    }
}
