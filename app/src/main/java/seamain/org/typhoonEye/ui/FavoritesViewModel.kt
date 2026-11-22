package seamain.org.typhoonEye.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import seamain.org.typhoonEye.domain.favorites.FavoriteDraft
import seamain.org.typhoonEye.domain.favorites.FavoriteOpenPlan
import seamain.org.typhoonEye.domain.favorites.FavoriteTyphoon
import seamain.org.typhoonEye.domain.favorites.planFavoriteOpen
import seamain.org.typhoonEye.domain.model.Typhoon
import seamain.org.typhoonEye.domain.repository.FavoriteRepository
import seamain.org.typhoonEye.domain.util.WallClock
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: FavoriteRepository,
    private val clock: WallClock
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteTyphoon>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun favorite(draft: FavoriteDraft) {
        viewModelScope.launch { repository.add(draft) }
    }

    fun unfavorite(sourceId: String) {
        viewModelScope.launch { repository.remove(sourceId) }
    }

    /**
     * How to open a saved storm. Does not remove the favorite when the storm
     * has dissipated or its year is outside the history window.
     */
    fun planOpen(favorite: FavoriteTyphoon, loadedTyphoons: List<Typhoon>): FavoriteOpenPlan =
        planFavoriteOpen(favorite, loadedTyphoons, clock.nowMs())
}
