package seamain.org.typhoonEye.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import seamain.org.typhoonEye.R
import seamain.org.typhoonEye.domain.model.TyphoonPoint
import seamain.org.typhoonEye.ui.util.displayIntensity
import seamain.org.typhoonEye.ui.util.formatCoordinate
import seamain.org.typhoonEye.ui.util.formatObservationTime
import seamain.org.typhoonEye.ui.util.intensityColor
import seamain.org.typhoonEye.ui.util.moveLabel
import seamain.org.typhoonEye.ui.util.resolveIntensity

@Composable
fun PointTimelineItem(
    point: TyphoonPoint,
    isForecast: Boolean = false,
    showConnector: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val level = resolveIntensity(point.strong, point.power)
    val color = if (isForecast) {
        MaterialTheme.colorScheme.tertiary
    } else {
        intensityColor(level)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            if (showConnector) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(72.dp)
                        .background(color.copy(alpha = 0.3f))
                )
            }
        }

        Card(
            modifier = Modifier.weight(1f),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = formatObservationTime(point.time, context = context, showRelative = !isForecast),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = formatCoordinate(point.lat, point.lng),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (point.speedReported) {
                            stringResource(R.string.timeline_wind_speed, point.speed)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (point.pressureReported) {
                            stringResource(R.string.timeline_pressure, point.pressure)
                        } else {
                            "—"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.timeline_intensity, point.displayIntensity(context)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.timeline_move, point.moveLabel(context)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
