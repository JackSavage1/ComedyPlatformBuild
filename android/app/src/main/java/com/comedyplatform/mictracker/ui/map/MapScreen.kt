package com.comedyplatform.mictracker.ui.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.core.model.Mic
import com.comedyplatform.mictracker.core.rules.Boroughs
import com.comedyplatform.mictracker.core.rules.GeoPoint
import com.comedyplatform.mictracker.core.rules.MapPreset
import com.comedyplatform.mictracker.core.rules.MapStyle
import com.comedyplatform.mictracker.core.rules.TextRules
import com.comedyplatform.mictracker.core.rules.Weekdays
import com.comedyplatform.mictracker.ui.components.EmptyState
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.LoadingBox
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import com.comedyplatform.mictracker.ui.components.UrgencyDot
import java.time.LocalDate

/**
 * Map (spec 6.5) — every active mic with coordinates as a circle colored by
 * borough, on a free Carto basemap drawn by MapLibre (no API key).
 *
 *   - Day / Borough filter chips and zoom presets above the map
 *   - style switcher (Voyager / Light / Dark) in the top bar
 *   - "My location" button (asks for permission; works fine without it)
 *   - tap a circle → card with the mic → "Details" opens Mic Detail for the
 *     mic's next occurrence (rule 16)
 *   - stats strip and borough legend underneath
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit) {
    val viewModel = appViewModel { container ->
        MapViewModel(repository = container.micRepository, today = { container.today() })
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.messageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🗺️ Mic Map") },
                actions = {
                    if (state.isRefreshing) {
                        SmallSpinner(modifier = Modifier.padding(horizontal = 12.dp))
                    } else {
                        IconButton(onClick = viewModel::refresh) {
                            Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                    StyleMenuButton(selected = state.style, onSelect = viewModel::setStyle)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                !state.hasLoaded && state.error == null -> LoadingBox(message = "Loading mics…")
                !state.hasLoaded || !state.anyCoordinates -> NoMapContent(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onDismissError = viewModel::dismissError,
                )
                else -> MapContent(state = state, viewModel = viewModel, onOpenMic = onOpenMic)
            }
        }
    }
}

/** The style switcher: a layers icon with Voyager / Light / Dark. */
@Composable
private fun StyleMenuButton(selected: MapStyle, onSelect: (MapStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(imageVector = Icons.Filled.Layers, contentDescription = "Map style")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MapStyle.entries.forEach { style ->
                DropdownMenuItem(
                    text = { Text(style.label) },
                    onClick = {
                        open = false
                        onSelect(style)
                    },
                    trailingIcon = {
                        if (style == selected) Icon(imageVector = Icons.Filled.Check, contentDescription = "Selected")
                    },
                )
            }
        }
    }
}

/** First load failed, or no mic has coordinates yet: a refreshable message instead of a map. */
@Composable
private fun NoMapContent(state: MapUiState, onRefresh: () -> Unit, onDismissError: () -> Unit) {
    RefreshableBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { ErrorBanner(message = state.error, onDismiss = onDismissError) }
            item {
                if (!state.hasLoaded) {
                    EmptyState(
                        title = "Couldn't load the mics",
                        message = "Check your connection, then pull down or tap Retry.",
                        action = { Button(onClick = onRefresh) { Text("Retry") } },
                    )
                } else {
                    EmptyState(
                        title = "No mics on the map yet",
                        message = "None of your mics have map coordinates. Open Manage (the ⚙️ button on " +
                            "Tonight or Calendar) → Geocoding to place them on the map.",
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapContent(
    state: MapUiState,
    viewModel: MapViewModel,
    onOpenMic: (micId: Int, date: LocalDate?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // ---- The MapView and the controller that drives it ----------------------
    // Coming back from Mic Detail starts where the user left the camera.
    val mapView = rememberMapViewWithLifecycle(initialCamera = state.savedCamera ?: CameraTarget.of(state.preset))
    val controller = remember(mapView) {
        MicMapController(
            tapRadiusPx = with(density) { TAP_RADIUS.toPx() },
            initialStyleUrl = state.style.url,
        )
    }
    DisposableEffect(controller) {
        controller.attach(mapView)
        onDispose {
            controller.currentCamera()?.let { camera -> viewModel.saveCamera(camera) }
            controller.detach()
        }
    }
    // Always route taps to the current ViewModel.
    SideEffect { controller.onMicTapped = { micId -> viewModel.selectMic(micId) } }

    // Push the latest state into the map whenever it changes.
    LaunchedEffect(controller, state.style) { controller.setStyleUrl(state.style.url) }
    LaunchedEffect(controller, state.pins) { controller.setPins(state.pins) }
    LaunchedEffect(controller, state.selectedPin) { controller.setSelected(state.selectedPin) }
    LaunchedEffect(controller, state.myLocation) { controller.setMyLocation(state.myLocation) }

    // ---- "My location" ---------------------------------------------------------
    val showMyLocation: (GeoPoint) -> Unit = { point ->
        viewModel.setMyLocation(point)
        controller.moveTo(CameraTarget(point.latitude, point.longitude, MY_LOCATION_ZOOM))
    }
    val findMyLocation: () -> Unit = {
        val cached = MyLocation.lastKnown(context)
        if (cached != null) {
            showMyLocation(cached)
        } else {
            viewModel.showMessage("Finding your location…")
            MyLocation.requestCurrent(context) { point ->
                if (point != null) {
                    showMyLocation(point)
                } else {
                    viewModel.showMessage("Couldn't find your location. Is location turned on?")
                }
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results.values.any { it }) {
            findMyLocation()
        } else {
            viewModel.showMessage("Location permission denied — the map works fine without it.")
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ErrorBanner(message = state.error, onDismiss = viewModel::dismissError)

        // ---- Filters + zoom presets ----------------------------------------------
        ChipRow {
            if (state.filtersActive) {
                item(key = "clear") {
                    AssistChip(onClick = viewModel::clearFilters, label = { Text("Clear filters") })
                }
            }
            items(items = Weekdays.ordered, key = { day -> "day-$day" }) { day ->
                FilterChip(
                    selected = day in state.selectedDays,
                    onClick = { viewModel.toggleDay(day) },
                    label = { Text(Weekdays.shortName(day)) },
                )
            }
        }
        ChipRow {
            items(items = Boroughs.all, key = { borough -> "borough-$borough" }) { borough ->
                FilterChip(
                    selected = borough in state.selectedBoroughs,
                    onClick = { viewModel.toggleBorough(borough) },
                    label = { Text(borough) },
                )
            }
        }
        ChipRow {
            items(items = MapPreset.entries, key = { preset -> "preset-${preset.name}" }) { preset ->
                FilterChip(
                    selected = preset == state.preset,
                    onClick = {
                        viewModel.choosePreset(preset)
                        controller.moveTo(CameraTarget.of(preset))
                    },
                    label = { Text("🔍 ${preset.label}") },
                )
            }
        }

        // ---- The map ----------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            SmallFloatingActionButton(
                onClick = {
                    if (MyLocation.hasPermission(context)) {
                        findMyLocation()
                    } else {
                        permissionLauncher.launch(MyLocation.permissions)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            ) {
                Icon(imageVector = Icons.Filled.MyLocation, contentDescription = "My location")
            }

            if (state.pins.isEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp, start = 16.dp, end = 72.dp),
                    shape = RoundedCornerShape(50),
                    tonalElevation = 3.dp,
                    shadowElevation = 3.dp,
                ) {
                    Text(
                        text = "No mics match your filters.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            val selected = state.selectedMic
            if (selected != null) {
                MicCallout(
                    mic = selected,
                    onDetails = { onOpenMic(selected.id, selected.nextOccurrence(state.today)) },
                    onClose = { viewModel.selectMic(null) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }

        // ---- Stats + legend ----------------------------------------------------------
        StatsStrip(state = state)
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            state.legend.forEach { entry -> LegendItem(entry) }
        }
    }
}

/** A horizontally scrolling row of chips. */
@Composable
private fun ChipRow(content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/**
 * The tapped mic: name, venue, "Mondays at 7:00 PM", cost, neighborhood (or
 * borough), and a Details button.
 */
@Composable
private fun MicCallout(mic: Mic, onDetails: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UrgencyDot(mic.urgencyLevel)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = mic.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                }
            }
            Column(modifier = Modifier.padding(end = 12.dp)) {
                if (mic.venueName.isNotEmpty()) CalloutLine("📍 ${mic.venueName}")
                CalloutLine("📅 ${mic.scheduleText}")
                CalloutLine("💰 ${mic.costText}")
                val area = TextRules.trimmedOrNull(mic.neighborhood) ?: TextRules.trimmedOrNull(mic.borough)
                if (area != null) CalloutLine("🏘️ $area")
                Button(
                    onClick = onDetails,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Text("Details")
                }
            }
        }
    }
}

@Composable
private fun CalloutLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Mics on map · Neighborhoods · Boroughs. */
@Composable
private fun StatsStrip(state: MapUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapStat(label = "Mics on map", value = state.pins.size, modifier = Modifier.weight(1f))
        MapStat(label = "Neighborhoods", value = state.neighborhoodCount, modifier = Modifier.weight(1f))
        MapStat(label = "Boroughs", value = state.boroughCount, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun MapStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Text(text = value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LegendItem(entry: LegendEntry) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = Color(entry.colorArgb), shape = CircleShape),
        )
        Spacer(Modifier.width(4.dp))
        Text(text = entry.label, style = MaterialTheme.typography.labelMedium)
    }
}

/** How close (dp) a tap must be to a circle's center to select it. */
private val TAP_RADIUS = 16.dp

/** Street level, for "My location". */
private const val MY_LOCATION_ZOOM = 14.0
