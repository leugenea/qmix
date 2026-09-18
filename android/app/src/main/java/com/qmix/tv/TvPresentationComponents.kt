package com.qmix.tv

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

@Composable
internal fun FocusedButton(
    text: String,
    enabled: Boolean,
    focusRequester: FocusRequester,
    focusProperties: FocusProperties.() -> Unit = {},
    onFocused: () -> Unit = {},
    testTag: String? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .focusProperties(focusProperties)
            .focusRequester(focusRequester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .border(
                if (focused) 4.dp else 1.dp,
                if (focused) Color.White else Color.Transparent,
                RoundedCornerShape(16.dp),
            ),
    ) { Text(text) }
}
