package com.qmix.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var state by remember { mutableStateOf(StartupState()) }
            QMixTheme {
                StartupScreen(
                    state = state,
                    onActivate = { state = state.activate() },
                )
            }
        }
    }
}

@Composable
internal fun StartupScreen(
    state: StartupState,
    onActivate: () -> Unit,
) {
    val actionFocusRequester = remember { FocusRequester() }
    var actionFocused by remember { mutableStateOf(false) }

    LaunchedEffect(actionFocusRequester) {
        actionFocusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101218))
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.startup_title),
            fontSize = 42.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp),
        )
        Button(
            onClick = onActivate,
            enabled = !state.isActivated,
            modifier = Modifier
                .focusRequester(actionFocusRequester)
                .onFocusChanged { actionFocused = it.isFocused }
                .border(
                    width = if (actionFocused) 4.dp else 1.dp,
                    color = if (actionFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                ),
        ) {
            Text(
                text = stringResource(
                    if (state.isActivated) R.string.startup_activated else R.string.startup_action,
                ),
            )
        }
    }
}

@Composable
private fun QMixTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
