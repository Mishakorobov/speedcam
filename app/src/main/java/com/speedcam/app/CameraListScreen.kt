package com.speedcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.speedcam.app.cameras.CameraFilter
import com.speedcam.app.cameras.CameraFilterEngine
import com.speedcam.app.cameras.CameraRow
import com.speedcam.core.detect.CameraKind

/**
 * The camera list.
 *
 * Sortable by limit, filterable by type, searchable, and marking blocked cameras so they can
 * be unblocked from the same place they were blocked. The original has no equivalent screen —
 * its list is of hazard categories, not of cameras — so this is our own, built to the one
 * question a driver asks: what is near me, and what does it measure.
 */
@Composable
fun CameraListScreen(
    cameras: List<com.speedcam.core.detect.Camera>,
    blocked: List<Pair<Double, Double>>,
    onUnblock: (CameraRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(CameraFilter()) }

    val rows = remember(cameras, filter, blocked) { CameraFilterEngine.apply(cameras, filter, blocked) }

    Column(modifier = modifier.fillMaxSize()) {
        TextField(
            value = filter.query,
            onValueChange = { filter = filter.copy(query = it) },
            label = { Text("Search cameras") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        KindFilterRow(selected = filter.kinds) { kinds ->
            filter = filter.copy(kinds = kinds)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = filter.requireLimit,
                onClick = { filter = filter.copy(requireLimit = !filter.requireLimit, onlyUnknownLimit = false) },
                label = { Text("Has limit") },
            )
            FilterChip(
                selected = filter.onlyUnknownLimit,
                onClick = { filter = filter.copy(onlyUnknownLimit = !filter.onlyUnknownLimit, requireLimit = false) },
                label = { Text("No limit") },
            )
            FilterChip(
                selected = filter.excludeBlocked,
                onClick = { filter = filter.copy(excludeBlocked = !filter.excludeBlocked) },
                label = { Text("Hide blocked") },
            )
        }

        Text(
            text = "${rows.size} of ${cameras.size}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { row ->
                CameraRowItem(row = row, onClick = { if (row.blocked) onUnblock(row) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun KindFilterRow(
    selected: Set<CameraKind>,
    onChange: (Set<CameraKind>) -> Unit,
) {
    LazyRow(
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(CameraKind.entries.filter { it != CameraKind.UNKNOWN }.toList()) { kind ->
            FilterChip(
                selected = kind in selected,
                onClick = { onChange(selected.toggle(kind)) },
                label = { Text(kind.name.lowercase().replace('_', ' ')) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

private fun Set<CameraKind>.toggle(kind: CameraKind): Set<CameraKind> = if (kind in this) this - kind else this + kind

@Composable
private fun CameraRowItem(
    row: CameraRow,
    onClick: () -> Unit,
) {
    Surface(
        color = if (row.blocked) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text =
                        row.kind.name
                            .lowercase()
                            .replace('_', ' '),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text =
                        buildString {
                            append(row.source.name.lowercase())
                            row.operator?.let { append(" · ").append(it) }
                            append(" · ")
                            append(String.format(java.util.Locale.UK, "%.5f, %.5f", row.latitude, row.longitude))
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BoxLimit(row.limitLabel)

            if (row.blocked) {
                Text(
                    text = "blocked",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier =
                        Modifier
                            .background(
                                MaterialTheme.colorScheme.errorContainer,
                                RoundedCornerShape(6.dp),
                            ).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun BoxLimit(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
