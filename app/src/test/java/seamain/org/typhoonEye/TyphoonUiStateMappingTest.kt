package seamain.org.typhoonEye

import org.junit.Assert.assertEquals
import org.junit.Test
import seamain.org.typhoonEye.domain.model.DataSource
import seamain.org.typhoonEye.domain.model.DataSourcesFailedError
import seamain.org.typhoonEye.domain.model.NoDataSourceConfiguredError
import seamain.org.typhoonEye.domain.model.SourceFailure
import seamain.org.typhoonEye.domain.model.SourceFailureKind
import seamain.org.typhoonEye.ui.TyphoonUiState
import seamain.org.typhoonEye.ui.toTyphoonUiState

/** Pure mapping from repository failures to Home UI state (no Android needed). */
class TyphoonUiStateMappingTest {

    @Test
    fun `no data source maps to dedicated NoDataSource state, not Error`() {
        assertEquals(TyphoonUiState.NoDataSource, NoDataSourceConfiguredError().toTyphoonUiState())
    }

    @Test
    fun `source failures map to Error with typed failures`() {
        val failures = listOf(
            SourceFailure(DataSource.Juhe, SourceFailureKind.QuotaExceeded, code = "10012"),
            SourceFailure(DataSource.QWeather, SourceFailureKind.Network, detail = "timeout")
        )
        assertEquals(
            TyphoonUiState.Error(failures = failures),
            DataSourcesFailedError(failures).toTyphoonUiState()
        )
    }

    @Test
    fun `unexpected exceptions keep raw message only as detail`() {
        assertEquals(
            TyphoonUiState.Error(detail = "boom"),
            IllegalStateException("boom").toTyphoonUiState()
        )
    }
}
