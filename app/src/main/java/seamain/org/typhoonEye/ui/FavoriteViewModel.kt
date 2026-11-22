package seamain.org.typhoonEye.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seamain.org.typhoonEye.domain.favorites.FavoriteOpen
import seamain.org.typhoonEye.domain.favorites.canonicalFavoriteId
import seamain.org.typhoonEye.domain.favorites.favoriteFrom
import seamain.org.typhoonEye.domain.favorites.resolveFavoriteOpen
import seamain.org.typhoonEye.domain.history.HistoricalStorm
import seamain.org.typhoonEye.domain.model.FavoriteTyphoon
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.util.WallClock
import javax.inject.Inject

@HiltViewModel
class FavoriteViewModel @Inject constructor(
    private val repository: FavoriteRepository,
    private val clock: WallClock
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteTyphoon>> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val favoriteIds: StateFlow<Set<String>> = favorites
        .map { rows -> rows.mapTo(linkedSetOf()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun isFavorite(rawId: String): Boolean =
        canonicalFavoriteId(rawId) in favorites.value.map { it.id }

    fun toggleTyphoon(typhoon: Typhoon) {
        val draft = favoriteFrom(typhoon, clock.nowMs())
        if (draft.id.isEmpty()) return
        viewModelScope.launch { repository.toggle(draft) }
    }

    fun toggleHistorical(storm: HistoricalStorm) {
        val draft = favoriteFrom(storm, clock.nowMs())
        if (draft.id.isEmpty()) return
        viewModelScope.launch { repository.toggle(draft) }
    }

    fun remove(favorite: FavoriteTyphoon) {
        viewModelScope.launch { repository.remove(favorite.id) }
    }

    fun openTarget(favorite: FavoriteTyphoon, activeTyphoons: List<Typhoon>): FavoriteOpen =
        resolveFavoriteOpen(favorite, activeTyphoons, clock.nowMs())
}
