package com.qmix.tv

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
internal fun InvitationScreen(
    invite: GuestInvite,
    onAction: () -> Unit,
    actionText: String = "Enter room",
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { actionFocus.requestFocus() }
    val qr = remember(invite.guestUrl) { QrCodeGenerator.generate(invite.guestUrl, 360) }
    val bitmap = remember(qr) {
        Bitmap.createBitmap(qr.pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Row(
        Modifier.fillMaxSize().background(Color(0xFF101218)).padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR code for ${invite.guestUrl}",
            modifier = Modifier.size(360.dp),
        )
        Spacer(Modifier.width(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Join this room", fontSize = 32.sp)
            Text(invite.code, fontSize = 64.sp, modifier = Modifier.padding(12.dp))
            Text(invite.guestUrl, fontSize = 20.sp, modifier = Modifier.padding(bottom = 24.dp))
            FocusedButton(
                text = actionText,
                enabled = true,
                focusRequester = actionFocus,
                onClick = onAction,
            )
        }
    }
}
