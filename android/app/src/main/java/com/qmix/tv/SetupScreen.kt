package com.qmix.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun SetupScreen(
    settings: HostingState.Setup,
    pending: Boolean,
    error: UserMessage?,
    onSettingsChanged: (String, String) -> Unit,
    onCreate: () -> Unit,
) {
    var backend by remember(settings.backendUrl) { mutableStateOf(settings.backendUrl) }
    var origin by remember(settings.guestOrigin) { mutableStateOf(settings.guestOrigin) }
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(pending, error) { actionFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.setup_title),
            style = MaterialTheme.typography.displayMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (error != null) {
            Text(
                stringResource(error.resourceId()),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.widthIn(max = 760.dp).padding(12.dp),
            )
        }
        if (pending) Text(
            stringResource(R.string.creating_room),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(12.dp),
        )
        FocusedButton(
            text = stringResource(if (error == null) R.string.create_room else R.string.retry),
            enabled = !pending,
            focusRequester = actionFocus,
            onClick = onCreate,
        )
        UrlInput(stringResource(R.string.backend_url), backend, !pending) {
            backend = it
            onSettingsChanged(backend, origin)
        }
        UrlInput(stringResource(R.string.guest_origin), origin, !pending) {
            origin = it
            onSettingsChanged(backend, origin)
        }
    }
}

@Composable
internal fun HttpWarningScreen(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { confirmFocus.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.http_warning_title),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            stringResource(R.string.http_warning_body),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.widthIn(max = 760.dp).padding(vertical = 24.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusedButton(stringResource(R.string.use_http), true, confirmFocus, onClick = onConfirm)
            FocusedButton(stringResource(R.string.cancel), true, remember { FocusRequester() }, onClick = onCancel)
        }
    }
}

@Composable
private fun UrlInput(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    val colors = MaterialTheme.colorScheme
    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(top = 16.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
                fontSize = 20.sp,
            ),
            cursorBrush = SolidColor(colors.onSurfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(colors.surfaceVariant, shape)
                // Foundation owns editing/focus; thickness makes focus visible without color alone.
                .border(if (focused) 4.dp else 1.dp, colors.onSurfaceVariant, shape)
                .padding(14.dp)
                .semantics { contentDescription = label },
        )
    }
}
