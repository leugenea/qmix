package com.qmix.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.setup_title), fontSize = 42.sp)
        if (error != null) {
            Text(stringResource(error.resourceId()), color = Color(0xFFFFB4AB), modifier = Modifier.padding(12.dp))
        }
        if (pending) Text(stringResource(R.string.creating_room), modifier = Modifier.padding(12.dp))
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
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.http_warning_title), fontSize = 36.sp, color = Color(0xFFFFB4AB))
        Text(
            stringResource(R.string.http_warning_body),
            fontSize = 22.sp,
            modifier = Modifier.width(760.dp).padding(vertical = 24.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusedButton(stringResource(R.string.use_http), true, confirmFocus, onClick = onConfirm)
            FocusedButton(stringResource(R.string.cancel), true, remember { FocusRequester() }, onClick = onCancel)
        }
    }
}

@Composable
private fun UrlInput(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Column(Modifier.width(620.dp).padding(top = 16.dp)) {
        Text(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            modifier = Modifier
                .width(620.dp)
                .background(Color(0xFF252833), RoundedCornerShape(8.dp))
                .padding(14.dp)
                .semantics { contentDescription = label },
        )
    }
}
