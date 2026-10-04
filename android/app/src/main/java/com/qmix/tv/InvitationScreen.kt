package com.qmix.tv

import android.graphics.Bitmap
import androidx.annotation.StringRes
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

@Composable
internal fun InvitationScreen(
    invite: GuestInvite,
    onAction: () -> Unit,
    @StringRes actionText: Int = R.string.enter_room,
    roomReplacementNotice: Boolean = false,
) {
    val actionFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { actionFocus.requestFocus() }
    val qr = remember(invite.guestUrl) { QrCodeGenerator.generate(invite.guestUrl, 360) }
    val bitmap = remember(qr) {
        Bitmap.createBitmap(qr.pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Row(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.invitation_qr_description, invite.guestUrl),
            modifier = Modifier.size(360.dp),
        )
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            if (roomReplacementNotice) {
                Text(
                    stringResource(R.string.replacement_invitation_notice),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(stringResource(R.string.join_this_room), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.invitation_room_code, invite.code),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.padding(12.dp),
            )
            Text(
                invite.guestUrl,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 24.dp),
            )
            FocusedButton(
                text = stringResource(actionText),
                enabled = true,
                focusRequester = actionFocus,
                onClick = onAction,
            )
        }
    }
}
