package seamain.org.typhoonEye.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seamain.org.typhoonEye.domain.history.HistoricalCoverage
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.history.HistoricalStormFeed
import seamain.org.typhoonEye.domain.history.HistoricalTrack
import seamain.org.typhoonEye.domain.history.HistoryPhase
import seamain.org.typhoonEye.domain.history.HistoryPresentation
import seamain.org.typhoonEye.domain.history.TrackPhase
import seamain.org.typhoonEye.domain.history.presentHistory
import seamain.org.typhoonEye.domain.history.presentTrack
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.TyphoonDataError
import seamain.org.typhoonEye.domain.repository.TyphoonRepository
import seamain.org.typhoonEye.domain.util.WallClock
import javax.inject.Inject

data class HistoryUi(
    val years: List<Int>,
    val year: Int,
    val query: String,
    val loading: Boolean,
    val presentation: HistoryPresentation
)

data class HistoryDetailUi(
    val phase: TrackPhase,
    val track: HistoricalTrack?,
    val loading: Boolean
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: TyphoonRepository,
    private val clock: WallClock
) : ViewModel() {

    val years: List<Int> = HistoricalCoverage.supportedYears(clock.nowMs())

    private val year = MutableStateFlow(years.first())
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val feed = MutableStateFlow<HistoricalStormFeed?>(null)
    private val error = MutableStateFlow<TyphoonDataError?>(null)
    private var generation = 0
    private var detailGeneration = 0

    val ui: StateFlow<HistoryUi> = combine(year, query, loading, feed, error) { selected, text, busy, loaded, failure ->
        HistoryUi(
            years = years,
            year = selected,
            query = text,
            loading = busy,
            presentation = presentHistory(selected, text, loaded, failure)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HistoryUi(
            years = years,
            year = years.first(),
            query = "",
            loading = true,
            presentation = HistoryPresentation(HistoryPhase.Loading, emptyList(), false)
        )
    )

    private val detail = MutableStateFlow(HistoryDetailUi(TrackPhase.Loading, null, false))
    val detailUi: StateFlow<HistoryDetailUi> = detail

    init {
        load(force = false)
    }

    fun setYear(next: Int) {
        if (next == year.value || next !in years) return
        year.value = next
        feed.value = null
        error.value = null
        load(force = false)
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun refresh() {
        load(force = true)
    }

    fun loadDetail(storm: HistoricalStorm) {
        val gen = ++detailGeneration
        viewModelScope.launch {
            detail.value = HistoryDetailUi(TrackPhase.Loading, null, true)
            val result = repository.getHistoricalTrack(storm.id, storm.name)
            if (gen != detailGeneration) return@launch
            result.fold(
                onSuccess = { loaded ->
                    detail.value = HistoryDetailUi(presentTrack(loaded, null), loaded, false)
                },
                onFailure = { failure ->
                    val typed = failure as? TyphoonDataError ?: DataSourcesFailedError(emptyList())
                    detail.value = HistoryDetailUi(presentTrack(null, typed), null, false)
                }
            )
        }
    }

    private fun load(force: Boolean) {
        val requested = year.value
        val gen = ++generation
        viewModelScope.launch {
            loading.value = true
            val result = repository.getHistoricalStorms(requested, forceRefresh = force)
            if (gen != generation || year.value != requested) return@launch
            result.fold(
                onSuccess = { loaded ->
                    feed.value = loaded
                    error.value = null
                },
                onFailure = { failure ->
                    feed.value = null
                    error.value = failure as? TyphoonDataError ?: DataSourcesFailedError(emptyList())
                }
            )
            loading.value = false
        }
    }
}
