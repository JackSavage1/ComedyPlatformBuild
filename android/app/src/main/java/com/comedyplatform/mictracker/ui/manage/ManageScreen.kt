package com.comedyplatform.mictracker.ui.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.comedyplatform.mictracker.appViewModel
import com.comedyplatform.mictracker.ui.components.ErrorBanner
import com.comedyplatform.mictracker.ui.components.RefreshableBox
import com.comedyplatform.mictracker.ui.components.SmallSpinner
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Manage (spec 6.8) — a port of the web Settings page plus the map's
 * geocoding tools. Opened from the gear on Tonight and Calendar.
 *
 * The first page is a menu; Import, Scrape History, Manage Mics and
 * Geocoding open as sub-pages inside this screen (the system back button and
 * the top-bar arrow return to the menu). Add a Mic goes to the Mic Form, and
 * Account (email + Sign out, or Demo mode + Exit demo) sits at the bottom of
 * the menu.
 *
 * @param initialSection open this sub-page first (CI's `import` screenshot, spec 8.3)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageScreen(
    onOpenMic: (micId: Int, date: LocalDate?) -> Unit,
    onAddMic: () -> Unit,
    onBack: () -> Unit,
    initialSection: ManageSection? = null,
) {
    val viewModel = appViewModel { container ->
        ManageViewModel(
            repository = container.micRepository,
            geocoder = container.geocodingService,
            account = combine(container.authRepository.session, container.isDemo) { session, demo ->
                ManageAccount(email = session?.email, isDemo = demo)
            },
            // Sign-out runs in the app-wide scope: this screen is removed the
            // moment the session is cleared, which would cancel viewModelScope.
            performSignOut = { container.applicationScope.launch { container.signOut() } },
            performExitDemo = { container.exitDemo() },
        )
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Which sub-page is open (null = the menu). Saved by name across rotation.
    var sectionName by rememberSaveable { mutableStateOf(initialSection?.name) }
    val section = sectionName?.let { name -> ManageSection.entries.firstOrNull { it.name == name } }
    val closeSection = { sectionName = null }

    BackHandler(enabled = section != null) { closeSection() }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.messageShown()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.title ?: "⚙️ Manage") },
                navigationIcon = {
                    IconButton(onClick = { if (section != null) closeSection() else onBack() }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ErrorBanner(message = state.error, onDismiss = viewModel::dismissError)

            RefreshableBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                when (section) {
                    null -> ManageMenu(
                        state = state,
                        onAddMic = onAddMic,
                        onOpenSection = { sectionName = it.name },
                        onSignOut = viewModel::signOut,
                        onExitDemo = viewModel::exitDemo,
                    )

                    ManageSection.IMPORT -> ImportSection(
                        state = state,
                        onAdd = viewModel::importOne,
                        onDismiss = viewModel::dismissOne,
                        onAddAll = viewModel::addAll,
                    )

                    ManageSection.HISTORY -> ScrapeHistorySection(state = state)

                    ManageSection.MICS -> ManageMicsSection(
                        state = state,
                        onQueryChange = viewModel::setSearchQuery,
                        onOpenMic = { micId -> onOpenMic(micId, null) },
                    )

                    ManageSection.GEOCODING -> GeocodingSection(
                        state = state,
                        onGeocode = viewModel::geocodeMissing,
                        onStop = viewModel::stopGeocoding,
                        onOpenMic = { micId -> onOpenMic(micId, null) },
                    )
                }
            }
        }
    }
}

/** The first page: one row per section, then Account. */
@Composable
private fun ManageMenu(
    state: ManageUiState,
    onAddMic: () -> Unit,
    onOpenSection: (ManageSection) -> Unit,
    onSignOut: () -> Unit,
    onExitDemo: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "add") {
            MenuRow(title = "➕ Add a Mic", subtitle = "Add an open mic by hand", onClick = onAddMic)
        }
        item(key = "import") {
            val subtitle = when {
                !state.reviewLoaded -> "Checking for new mics…"
                state.reviewFailed -> "Couldn't load the review queue — pull down to retry"
                state.scrapedCount == 0 -> "You're up to date."
                state.scrapedCount == 1 -> "1 new mic to review"
                else -> "${state.scrapedCount} new mics to review"
            }
            MenuRow(title = ManageSection.IMPORT.title, subtitle = subtitle, onClick = { onOpenSection(ManageSection.IMPORT) })
        }
        item(key = "history") {
            val last = state.lastScrape
            val subtitle = if (last == null) "No scrapes logged yet." else "Last: ${last.sourceDisplayName} · ${last.lastScrapedText}"
            MenuRow(title = ManageSection.HISTORY.title, subtitle = subtitle, onClick = { onOpenSection(ManageSection.HISTORY) })
        }
        item(key = "mics") {
            MenuRow(
                title = ManageSection.MICS.title,
                subtitle = "${state.activeMics.size} active mics — search, edit or delete",
                onClick = { onOpenSection(ManageSection.MICS) },
            )
        }
        item(key = "geocoding") {
            val progress = state.geocode
            val subtitle = if (progress.isRunning) {
                "Geocoding… ${progress.done} / ${progress.total}"
            } else {
                state.coordinatesSummary
            }
            MenuRow(title = ManageSection.GEOCODING.title, subtitle = subtitle, onClick = { onOpenSection(ManageSection.GEOCODING) })
        }
        item(key = "account") {
            if (state.isDemo) {
                DemoAccountSection(onExitDemo = onExitDemo)
            } else {
                AccountSection(email = state.email, isSigningOut = state.isSigningOut, onSignOut = onSignOut)
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
}

/** Account — the signed-in email and Sign out. */
@Composable
private fun AccountSection(email: String?, isSigningOut: Boolean, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "👤 Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Signed in as ${email ?: "unknown email"}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onSignOut, enabled = !isSigningOut) {
            if (isSigningOut) {
                SmallSpinner()
            } else {
                Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (isSigningOut) "Signing out…" else "Sign out")
        }
    }
}

/** Account in demo mode (spec 8.1): "Demo mode" and Exit demo instead of Sign out. */
@Composable
private fun DemoAccountSection(onExitDemo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "👤 Account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = "Demo mode — sample data, nothing is saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onExitDemo) {
            Icon(imageVector = Icons.AutoMirrored.Filled.Logout, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Exit demo")
        }
    }
}
