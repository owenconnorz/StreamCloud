package com.streamcloud.app.ui.screens.adult

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.streamcloud.app.ui.viewmodel.AdultViewModel

@Composable
fun RedditFeedView(
    vm: AdultViewModel,
    customSubs: List<String> = emptyList(),
    redditUsername: String = "",
    onLoginClick: () -> Unit = {},
    onLogoutClick: () -> Unit = {},
    onAddSub: (String) -> Unit = {},
    onRemoveSub: (String) -> Unit = {},
    onSwitchAccount: (String, String) -> Unit = { _, _ -> },
    accounts: List<String> = emptyList(),
    onSwitchSource: () -> Unit = {},
) {
    Box(
        Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Reddit source is unavailable.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
