package com.qmix.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    error: String?,
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
        Text("QMix TV", fontSize = 42.sp)
        if (error != null) Text(error, color = Color(0xFFFFB4AB), modifier = Modifier.padding(12.dp))
        if (pending) Text("Creating room…", modifier = Modifier.padding(12.dp))
        FocusedButton(
            text = if (error == null) "Create room" else "Retry",
            enabled = !pending,
            focusRequester = actionFocus,
            onClick = onCreate,
        )
        UrlInput("Backend URL", backend, !pending) {
            backend = it
            onSettingsChanged(backend, origin)
        }
        UrlInput("Guest origin", origin, !pending) {
            origin = it
            onSettingsChanged(backend, origin)
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
