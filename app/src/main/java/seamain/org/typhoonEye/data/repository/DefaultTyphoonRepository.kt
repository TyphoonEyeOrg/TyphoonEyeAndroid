package seamain.org.typhoonEye.data.repository

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import seamain.org.typhoonEye.data.api.JuheTyphoonApi
import seamain.org.typhoonEye.data.api.QWeatherTyphoonApi
import seamain.org.typhoonEye.data.credentials.DataSourceCredentials
import seamain.org.typhoonEye.data.credentials.hasAnyDataSource
import seamain.org.typhoonEye.data.credentials.hasJuhe
import seamain.org.typhoonEye.data.credentials.hasQWeather
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
 * 4) Status is resolved client-side ([TyphoonActivity]): no observation for 24 h → dissipated.
 *
 * Failures are typed ([TyphoonDataError]); the UI resolves localized text.
 * A build without any API key (e.g. F-Droid) yields [NoDataSourceConfiguredError]
 * without touching the network.
 */
class DefaultTyphoonRepository @Inject constructor(
    private val juheApi: JuheTyphoonApi,
    private val qWeatherApi: QWeatherTyphoonApi,
    private val credentials: DataSourceCredentials,
    private val localDataSource: TyphoonLocalDataSource,
    private val syncStore: FeedSyncStore,
    private val clock: WallClock
) : TyphoonRepository {

    // Credentials are evaluated on every call (Settings key → build key), never cached here,
    // so entering or removing a key takes effect without recreating the repository.
    private val qWeatherConfigured: Boolean
        get() = credentials.hasQWeather

    private val juheConfigured: Boolean
        get() = credentials.hasJuhe

    /** True when at least one remote data source currently has credentials. */
    val hasAnyDataSource: Boolean
        get() = credentials.hasAnyDataSource

    private val tag = "TyphoonRepository"

    /** Serialises list loads so UI + background worker never fetch in parallel. */
    private val listMutex = Mutex()

    private class FetchOutcome(
        val typhoons: List<Typhoon>? = null,
        val failure: SourceFailure? = null
    )

    override suspend fun getCachedFeed(): TyphoonFeed? {
        // Without any key the UI shows the no-data-source page, not leftovers from an old key.
        if (!hasAnyDataSource) return null
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
            if (!hasAnyDataSource) {
                // Checked before the TTL so removing the last key is reflected immediately.
                // Room cache is kept (not wiped) but not served without a data source.
                Log.i(tag, "No data source configured (no user or build-time key)")
                return@withLock Result.failure(NoDataSourceConfiguredError())
            }
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

    override suspend fun invalidateListFreshness() {
        listMutex.withLock { syncStore.lastListFetchAtMs = null }
    }

    override suspend fun getTyphoonDetail(id: String): Result<Typhoon> {
        if (!hasAnyDataSource) {
            // Same rule as the list: no key → no data, not a cached detail from an old key
            // (reachable from a posted notification / deep link).
            return Result.failure(NoDataSourceConfiguredError())
        }
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
        if (!hasAnyDataSource) {
            return Result.failure(NoDataSourceConfiguredError())
        }

        val failures = ArrayList<SourceFailure>()

        if (juheConfigured) {
            val juhe = fetchFromJuhe(cachedById)
            juhe.typhoons?.let { return Result.success(it) }
            juhe.failure?.let { failures.add(it) }
        }

        if (qWeatherConfigured) {
            val qWeather = fetchFromQWeather()
            qWeather.typhoons?.let { return Result.success(it) }
            qWeather.failure?.let { failures.add(it) }
        }

        return Result.failure(DataSourcesFailedError(failures))
    }

    private suspend fun fetchRemoteDetail(id: String): Result<Typhoon> {
        if (!hasAnyDataSource) {
            return Result.failure(NoDataSourceConfiguredError())
        }
        val failures = ArrayList<SourceFailure>()

        val juheKey = credentials.juheKey()
        if (!id.startsWith("NP_") && juheKey.isNotBlank()) {
            try {
                val response = juheApi.getTyphoonDetail(juheKey, id)
                if (response.errorCode == 0 && response.result?.data != null) {
                    return Result.success(response.result.data.toDomain())
                }
                Log.w(tag, "Juhe detail error: ${response.reason} (${response.errorCode})")
                failures.add(juheFailure(response.errorCode, response.reason))
            } catch (e: Exception) {
                Log.e(tag, "Juhe detail request failed", e)
                failures.add(SourceFailure(DataSource.Juhe, SourceFailureKind.Network, detail = e.message))
            }
        }

        if (!qWeatherConfigured) {
            return Result.failure(TyphoonDetailUnavailableError(id, failures))
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
                    englishName = stormId,
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
        } catch (e: Exception) {
            Log.e(tag, "QWeather track request failed", e)
            failures.add(SourceFailure(DataSource.QWeather, SourceFailureKind.Network, detail = e.message))
        }

        return Result.failure(TyphoonDetailUnavailableError(id, failures))
    }

    private suspend fun fetchFromJuhe(cachedById: Map<String, Typhoon>): FetchOutcome {
        val juheKey = credentials.juheKey()
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
                else -> {
                    val failure = juheFailure(listResponse.errorCode, listResponse.reason)
                    Log.w(tag, "Juhe list failed: ${failure.describe()}, falling back")
                    FetchOutcome(failure = failure)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Juhe request failed", e)
            FetchOutcome(
                failure = SourceFailure(DataSource.Juhe, SourceFailureKind.Network, detail = e.message)
            )
        }
    }

    private suspend fun fetchFromQWeather(): FetchOutcome {
        return try {
            val year = Calendar.getInstance().get(Calendar.YEAR).toString()
            val listResponse = qWeatherApi.getStormList(basin = "NP", year = year)
            if (listResponse.code != "200") {
                Log.e(tag, "QWeather list error code=${listResponse.code}")
                return FetchOutcome(failure = qWeatherFailure(listResponse.code))
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
            FetchOutcome(
                failure = SourceFailure(DataSource.QWeather, SourceFailureKind.Network, detail = e.message)
            )
        }
    }

    private fun juheFailure(errorCode: Int, reason: String?): SourceFailure {
        val kind = when (errorCode) {
            10001, 10002 -> SourceFailureKind.InvalidKey
            10012, 10013, 10022, 10023 -> SourceFailureKind.QuotaExceeded
            else -> SourceFailureKind.ApiError
        }
        return SourceFailure(DataSource.Juhe, kind, code = errorCode.toString(), detail = reason)
    }

    /** QWeather v7 status codes: 401/403 auth, 402/429 quota. */
    private fun qWeatherFailure(code: String?): SourceFailure {
        val kind = when (code) {
            "401", "403" -> SourceFailureKind.InvalidKey
            "402", "429" -> SourceFailureKind.QuotaExceeded
            else -> SourceFailureKind.ApiError
        }
        return SourceFailure(DataSource.QWeather, kind, code = code)
    }

    companion object {
        const val LIST_TTL_MS: Long = 10L * 60 * 1000
        const val MIN_FORCE_INTERVAL_MS: Long = 60L * 1000
        const val DETAIL_TTL_MS: Long = 30L * 60 * 1000
    }
}
