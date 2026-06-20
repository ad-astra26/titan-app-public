package tech.iamtitan.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Standard Android swipe-down-to-refresh wrapper. Shows the spinner while
 * [refreshing] and fires [onRefresh] on a downward pull. Wrap a screen's scrollable content
 * so every data view refreshes the way Android users expect — no header button needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitanPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) { content() }
}
