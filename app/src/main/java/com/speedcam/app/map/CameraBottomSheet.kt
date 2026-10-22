package com.speedcam.app.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.sp
import com.speedcam.app.cameras.CameraFilter
import com.speedcam.app.cameras.CameraFilterEngine
import com.speedcam.app.cameras.CameraRow
import com.speedcam.app.ui.components.CompactSpeedLimitBadge
import com.speedcam.app.ui.components.getCameraIcon
import com.speedcam.app.ui.theme.BrandPrimaryDark
import com.speedcam.app.ui.theme.SpeedDanger
import com.speedcam.app.util.toRussianTitle
import com.speedcam.core.detect.Camera
import com.speedcam.core.detect.CameraKind
import com.speedcam.core.geo.LatLon

/**
 * Modern modal bottom sheet presenting cameras directly over the interactive map.
 * Supports distance sorting, instant search, kind filtering, and one-tap camera inspection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraListBottomSheet(
    cameras: List<Camera>,
    blocked: List<LatLon>,
    userLocation: LatLon?,
    onDismissRequest: () -> Unit,
    onSelectCamera: (Camera) -> Unit,
    onUnblockCamera: (CameraRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var filter by remember { mutableStateOf(CameraFilter()) }
    var sortByDistance by remember { mutableStateOf(userLocation != null) }

    val blockedPairs = remember(blocked) { blocked.map { it.lat to it.lon } }
    val cameraMap = remember(cameras) { cameras.associateBy { it.id } }

    val rows =
        remember(cameras, filter, blockedPairs, userLocation, sortByDistance) {
            CameraFilterEngine.apply(
                cameras = cameras,
                filter = filter,
                blocked = blockedPairs,
                referenceLocation = userLocation,
                sortByDistance = sortByDistance,
            )
        }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF64748B)) },
        containerColor = Color(0xF50F172A),
        contentColor = Color.White,
        modifier = modifier.fillMaxHeight(0.85f),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
        ) {
            BottomSheetHeader(
                filteredCount = rows.size,
                totalCount = cameras.size,
                onClose = onDismissRequest,
            )

            Spacer(modifier = Modifier.height(10.dp))

            SearchAndFilterControls(
                filter = filter,
                onFilterChange = { filter = it },
                sortByDistance = sortByDistance,
                hasLocation = userLocation != null,
                onToggleSortByDistance = { sortByDistance = !sortByDistance },
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (rows.isEmpty()) {
                EmptyCameraSheetState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        CameraSheetItem(
                            row = row,
                            onClick = {
                                cameraMap[row.id]?.let { cam ->
                                    onSelectCamera(cam)
                                }
                            },
                            onUnblock = { onUnblockCamera(row) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomSheetHeader(
    filteredCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Объекты контроля",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Найдено: $filteredCount из $totalCount",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
            )
        }

        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Закрыть",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun SearchAndFilterControls(
    filter: CameraFilter,
    onFilterChange: (CameraFilter) -> Unit,
    sortByDistance: Boolean,
    hasLocation: Boolean,
    onToggleSortByDistance: () -> Unit,
) {
    OutlinedTextField(
        value = filter.query,
        onValueChange = { onFilterChange(filter.copy(query = it)) },
        placeholder = { Text("Поиск по номеру, типу или оператору...", color = Color(0xFF94A3B8)) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = Color(0xFF94A3B8),
            )
        },
        trailingIcon = {
            if (filter.query.isNotEmpty()) {
                IconButton(onClick = { onFilterChange(filter.copy(query = "")) }) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Очистить",
                        tint = Color(0xFF94A3B8),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1E293B),
                unfocusedContainerColor = Color(0xFF1E293B),
                focusedBorderColor = Color(0xFF0284C7),
                unfocusedBorderColor = Color(0xFF334155),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
            ),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    val kindChips =
        listOf(
            null to "Все",
            CameraKind.FIXED to "Скорость",
            CameraKind.RED_LIGHT to "Светофоры",
            CameraKind.AVERAGE_ENTRY to "КСС",
            CameraKind.MOBILE to "Засады",
            CameraKind.BUS_LANE to "Полоса ОТ",
            CameraKind.ROAD_MARKING to "Разметка",
            CameraKind.DUMMY to "Муляжи",
        )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (hasLocation) {
            item {
                FilterChip(
                    selected = sortByDistance,
                    onClick = onToggleSortByDistance,
                    label = { Text("Ближайшие", fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.NearMe,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            containerColor = Color(0xFF1E293B),
                            labelColor = Color(0xFF94A3B8),
                            selectedContainerColor = Color(0xFF0284C7),
                            selectedLabelColor = Color.White,
                            selectedLeadingIconColor = Color.White,
                        ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        }

        items(kindChips) { (kind, label) ->
            val isSelected =
                if (kind == null) {
                    filter.kinds.isEmpty()
                } else {
                    filter.kinds.contains(kind)
                }
            FilterChip(
                selected = isSelected,
                onClick = {
                    val newKinds =
                        if (kind == null) {
                            emptySet()
                        } else if (filter.kinds.contains(kind)) {
                            filter.kinds - kind
                        } else {
                            filter.kinds + kind
                        }
                    onFilterChange(filter.copy(kinds = newKinds))
                },
                label = { Text(label, fontSize = 11.sp) },
                colors =
                    FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF1E293B),
                        labelColor = Color(0xFF94A3B8),
                        selectedContainerColor = Color(0xFF0284C7),
                        selectedLabelColor = Color.White,
                    ),
                shape = RoundedCornerShape(12.dp),
            )
        }
    }
}

@Composable
private fun CameraSheetItem(
    row: CameraRow,
    onClick: () -> Unit,
    onUnblock: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = if (row.blocked) Color(0xFF2E1015) else Color(0xFF1E293B),
            ),
        border =
            BorderStroke(
                width = 1.dp,
                color = if (row.blocked) SpeedDanger.copy(alpha = 0.6f) else Color(0xFF334155),
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onClick() },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            if (row.blocked) {
                                SpeedDanger.copy(alpha = 0.2f)
                            } else {
                                BrandPrimaryDark.copy(alpha = 0.2f)
                            },
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = getCameraIcon(row.kind),
                    contentDescription = null,
                    tint = if (row.blocked) SpeedDanger else Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${row.kind.toRussianTitle()} #${row.id}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.blocked) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ЗАБЛОКИРОВАНА",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black,
                            color = SpeedDanger,
                        )
                    }
                }

                row.distanceM?.let { dist ->
                    val distLabel =
                        if (dist >= 1000) {
                            "%.1f км".format(dist / 1000.0)
                        } else {
                            "${dist.toInt()} м"
                        }
                    Text(
                        text = "Дистанция: $distLabel",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF38BDF8),
                    )
                }

                Text(
                    text =
                        row.operator
                            ?: "%.4f, %.4f • %s".format(row.latitude, row.longitude, row.source.name),
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            row.limitKmh?.let { limit ->
                if (limit > 0) {
                    CompactSpeedLimitBadge(limitKmh = limit)
                }
            }

            if (row.blocked) {
                IconButton(onClick = onUnblock) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Разблокировать",
                        tint = Color(0xFF4ADE80),
                    )
                }
            } else {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "На карте",
                        tint = Color(0xFF38BDF8),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyCameraSheetState() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Block,
                contentDescription = null,
                tint = Color(0xFF64748B),
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Камеры не найдены",
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = "Попробуйте изменить запрос или фильтры",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8),
            )
        }
    }
}
