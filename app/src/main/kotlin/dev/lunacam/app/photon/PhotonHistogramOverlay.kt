package dev.lunacam.app.photon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun PhotonHistogramOverlay(
    histogram: PhotonHistogram?,
    unavailable: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(modifier, color = Color.Black.copy(alpha = 0.65f), shape = MaterialTheme.shapes.small) {
        Column(Modifier.padding(6.dp)) {
            Text(
                if (unavailable) "히스토그램 미지원" else "프리뷰 밝기",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
            if (!unavailable) {
                Canvas(
                    Modifier.width(112.dp).height(44.dp).semantics {
                        contentDescription = if (histogram == null) "히스토그램 준비 중" else "프리뷰 밝기 히스토그램"
                    }
                ) {
                    val bins = histogram?.bins ?: return@Canvas
                    val maximum = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
                    val barWidth = size.width / bins.size
                    bins.forEachIndexed { index, count ->
                        val barHeight = count.toFloat() / maximum * size.height
                        drawRect(
                            Color.White,
                            Offset(index * barWidth, size.height - barHeight),
                            Size(barWidth, barHeight)
                        )
                    }
                }
            }
        }
    }
}
