package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.iamtitan.app.chat.ChatTurn

@Composable
fun ChatScreen(
    titanLabel: String,
    turns: List<ChatTurn>,
    draft: String,
    sending: Boolean,
    resting: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(TitanInk)) {
        ChatHeader(titanLabel, resting)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(turns) { turn -> Bubble(turn) }
            if (sending) {
                item { TypingBubble() }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Composer(draft, sending, onDraftChange, onSend)
    }
}

@Composable
private fun ChatHeader(titanLabel: String, resting: Boolean) {
    Surface(color = TitanSurface, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitanOrb(size = 34)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(titanLabel, color = TitanText, fontWeight = FontWeight.SemiBold)
                Text(
                    if (resting) "resting — tap restart in Console" else "online · sovereign on its server",
                    color = if (resting) TitanWarn else TitanGood,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun Bubble(turn: ChatTurn) {
    val mine = turn.fromMaker
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (mine) 18.dp else 4.dp,
                        bottomEnd = if (mine) 4.dp else 18.dp,
                    ),
                )
                .background(if (mine) TitanCyan else TitanSurfaceHi)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(turn.text, color = if (mine) TitanInk else TitanText)
        }
    }
}

@Composable
private fun TypingBubble() {
    Row(horizontalArrangement = Arrangement.Start, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(TitanSurfaceHi)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            CircularProgressIndicator(color = TitanViolet, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = TitanSurface) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message Titan…", color = TitanMuted) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TitanCyan,
                    unfocusedBorderColor = TitanSurfaceHi,
                    focusedTextColor = TitanText,
                    unfocusedTextColor = TitanText,
                    cursorColor = TitanCyan,
                ),
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
            )
            Spacer(Modifier.size(10.dp))
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier.height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = TitanCyan, contentColor = TitanInk),
            ) { Text("Send", fontWeight = FontWeight.SemiBold) }
        }
    }
}
