package com.streamcloud.app.ui.theme

import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

/**
 * Attach [requester] before this modifier and a focus group after it.
 *
 * Unlike Compose 1.7's focusRestorer, this never pins a lazy item. A NavHost can
 * dispose or replace its content while focus is elsewhere, so retained pins
 * are unsafe here. Detached or removed children use normal focus selection.
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun Modifier.tvUnpinnedFocusRestorer(requester: FocusRequester): Modifier = composed {
    val policy = remember(requester) {
        TvFocusRestorePolicy(
            saveChild = { requester.saveFocusedChild() },
            restoreChild = { requester.restoreFocusedChild() },
        )
    }
    focusProperties {
        exit = {
            policy.onExit()
            FocusRequester.Default
        }
        enter = {
            if (policy.onEnter()) FocusRequester.Cancel else FocusRequester.Default
        }
    }
}
