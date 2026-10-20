package com.comedyplatform.mictracker.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh wrapper (spec section 5: "Every list supports pull-to-refresh").
 *
 * The content must be scrollable (LazyColumn, or a Column with
 * verticalScroll) for the pull gesture to reach this box.
 * Wrapping Material 3's experimental PullToRefreshBox here keeps the opt-in
 * in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        content = content,
    )
}
