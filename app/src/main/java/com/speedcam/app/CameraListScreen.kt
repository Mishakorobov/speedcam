package com.speedcam.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.speedcam.app.cameras.CameraFilter
import com.speedcam.app.cameras.CameraFilterEngine
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.ui.components.CompactSpeedLimitBadge
import com.speedcam.app.ui.components.getCameraIcon
import com.speedcam.app.ui.theme.BrandPrimaryDark
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.core.data.HazardReportType
import com.speedcam.core.detect.CameraKind

/**
 * Modernized Speed Camera List Screen.
 *
 * Provides real-time filtering, instant search, road-sign limit badges,
 * camera type classification icons, and community reporting dialog.
 */
@Composable
fun CameraListScreen(
    cameras: List<com.speedcam.core.detect.Camera>,
    blocked: List<Pair<Double, Double>>,
    onUnblock: (CameraRow) -> Unit,
    modifier: Modifier = Modifier,
    onReportCamera: ((CameraRow, HazardReportType, Int?, String?) -> Unit)? = null,
) {
    var filter by remember { mutableStateOf(CameraFilter()) }
    var reportingCamera by remember { mutableStateOf<CameraRow?>(null) }

    val rows =
        remember(cameras, filter, blocked) {
            CameraFilterEngine.apply(cameras, filter, blocked)
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        CameraFilterSection(filter = filter, onFilterChange = { filter = it })

        CameraSummaryBar(
            filteredCount = rows.size,
            totalCount = cameras.size,
            filterActive = filter.isActive,
            onReset = { filter = CameraFilter() },
        )

        if (rows.isEmpty()) {
            EmptyListState(
                filterActive = filter.isActive,
                onReset = { filter = CameraFilter() },
                modifier = Modifier.weight(1f),
            )
        } else {
            CameraListItems(
                rows = rows,
                onRowClick = { row ->
                    if (row.blocked) {
                        onUnblock(row)
                    } else if (onReportCamera != null) {
                        reportingCamera = row
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    reportingCamera?.let { camera ->
        CameraReportDialog(
            camera = camera,
            onDismiss = { reportingCamera = null },
            onSubmit = { type, limit, comment ->
                reportingCamera = null
                onReportCamera?.invoke(camera, type, limit, comment)
            },
        )
    }
}

@Composable
private fun CameraFilterSection(
    filter: CameraFilter,
    onFilterChange: (CameraFilter) -> Unit,
) {
    CameraSearchField(
        query = filter.query,
        onQueryChange = { onFilterChange(filter.copy(query = it)) },
    )
    KindFilterRow(selected = filter.kinds) { kinds ->
        onFilterChange(filter.copy(kinds = kinds))
    }
    AttributesFilterRow(filter = filter) { updated ->
        onFilterChange(updated)
    }
}

@Composable
private fun CameraSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search by ID, operator, or road...") },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun CameraSummaryBar(
    filteredCount: Int,
    totalCount: Int,
    filterActive: Boolean,
    onReset: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Showing $filteredCount of $totalCount cameras",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (filterActive) {
            Text(
                text = "Reset filters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onReset() },
            )
        }
    }
}

@Composable
private fun CameraListItems(
    rows: List<CameraRow>,
    onRowClick: (CameraRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { it.id }) { row ->
            CameraCardItem(
                row = row,
                onClick = { onRowClick(row) },
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun CameraCardItem(
    row: CameraRow,
    onClick: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (row.blocked) {
                        Color(0xFF261217)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        border =
            androidx.compose.foundation.BorderStroke(
                1.dp,
                if (row.blocked) SpeedDanger.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant,
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CameraCardIcon(kind = row.kind, blocked = row.blocked)
            CameraCardInfo(row = row, modifier = Modifier.weight(1f))
            CameraCardTrailing(limitKmh = row.limitKmh, blocked = row.blocked)
        }
    }
}

@Composable
private fun CameraCardIcon(
    kind: CameraKind,
    blocked: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (blocked) {
                        SpeedDanger.copy(alpha = 0.2f)
                    } else {
                        BrandPrimaryDark.copy(alpha = 0.15f)
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = getCameraIcon(kind),
            contentDescription = null,
            tint = if (blocked) SpeedDanger else BrandPrimaryDark,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun CameraCardInfo(
    row: CameraRow,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text =
                row.kind.name
                    .lowercase()
                    .replace('_', ' ')
                    .replaceFirstChar { it.uppercase() },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 2.dp),
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = String.format(java.util.Locale.UK, "%.5f, %.5f", row.latitude, row.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = row.source.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }

            row.operator?.let { op ->
                Text(
                    text = op,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CameraCardTrailing(
    limitKmh: Int?,
    blocked: Boolean,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CompactSpeedLimitBadge(limitKmh = limitKmh)

        if (blocked) {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SpeedDanger,
            ) {
                Text(
                    text = "BLOCKED",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
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
                .PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(CameraKind.entries.filter { it != CameraKind.UNKNOWN }.toList()) { kind ->
            val isSelected = kind in selected
            FilterChip(
                selected = isSelected,
                onClick = { onChange(selected.toggle(kind)) },
                label = { Text(kind.name.lowercase().replace('_', ' ')) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

private fun Set<CameraKind>.toggle(kind: CameraKind): Set<CameraKind> = if (kind in this) this - kind else this + kind

@Composable
private fun AttributesFilterRow(
    filter: CameraFilter,
    onChange: (CameraFilter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.requireLimit,
            onClick = {
                onChange(filter.copy(requireLimit = !filter.requireLimit, onlyUnknownLimit = false))
            },
            label = { Text("Has limit") },
        )
        FilterChip(
            selected = filter.onlyUnknownLimit,
            onClick = {
                onChange(filter.copy(onlyUnknownLimit = !filter.onlyUnknownLimit, requireLimit = false))
            },
            label = { Text("No limit") },
        )
        FilterChip(
            selected = filter.excludeBlocked,
            onClick = {
                onChange(filter.copy(excludeBlocked = !filter.excludeBlocked))
            },
            label = { Text("Hide blocked") },
        )
    }
}

@Composable
private fun EmptyListState(
    filterActive: Boolean,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp),
            )
            Text(
                text = "No cameras found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text =
                    if (filterActive) {
                        "No cameras match your current search or filters."
                    } else {
                        "No camera data loaded. Please check data sync in settings."
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filterActive) {
                OutlinedButton(onClick = onReset) {
                    Text("Reset all filters")
                }
            }
        }
    }
}

@Composable
private fun CameraReportDialog(
    camera: CameraRow,
    onDismiss: () -> Unit,
    onSubmit: (HazardReportType, Int?, String?) -> Unit,
) {
    var selectedType by remember { mutableStateOf(HazardReportType.WRONG_SPEED_LIMIT) }
    var speedText by remember { mutableStateOf(camera.limitKmh?.toString() ?: "60") }
    var commentText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { CameraReportDialogTitle(camera = camera) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportReasonSelector(
                    selectedType = selectedType,
                    onSelect = { selectedType = it },
                )
                if (selectedType == HazardReportType.WRONG_SPEED_LIMIT) {
                    OutlinedTextField(
                        value = speedText,
                        onValueChange = { speedText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Correct speed limit (km/h)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    label = { Text("Note / comment (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val limit = speedText.toIntOrNull()
                    onSubmit(selectedType, limit, commentText.ifBlank { null })
                },
            ) {
                Text("Submit Report")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun CameraReportDialogTitle(camera: CameraRow) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactSpeedLimitBadge(limitKmh = camera.limitKmh)
        Column {
            Text(
                text = "Report Camera",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "ID #${camera.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportReasonSelector(
    selectedType: HazardReportType,
    onSelect: (HazardReportType) -> Unit,
) {
    Text("Select report reason:", style = MaterialTheme.typography.labelMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = selectedType == HazardReportType.WRONG_SPEED_LIMIT,
            onClick = { onSelect(HazardReportType.WRONG_SPEED_LIMIT) },
            label = { Text("Limit") },
        )
        FilterChip(
            selected = selectedType == HazardReportType.REMOVED_CAMERA,
            onClick = { onSelect(HazardReportType.REMOVED_CAMERA) },
            label = { Text("Removed") },
        )
        FilterChip(
            selected = selectedType == HazardReportType.FALSE_POSITIVE,
            onClick = { onSelect(HazardReportType.FALSE_POSITIVE) },
            label = { Text("False alarm") },
        )
    }
}
