package tech.iamtitan.app.ui

import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
    onOpenSettings: () -> Unit,
    onFeedback: (Int?, String) -> Unit,
    onAction: (Int, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(TitanInk)) {
        ChatHeader(titanLabel, resting, onOpenSettings)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(turns) { turn -> Bubble(turn, onFeedback, onAction) }
            if (sending) {
                item { TypingBubble() }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        Composer(draft, sending, onDraftChange, onSend)
    }
}

@Composable
private fun ChatHeader(titanLabel: String, resting: Boolean, onOpenSettings: () -> Unit) {
    Surface(color = TitanSurface, shadowElevation = 2.dp) {
        Row(
            // statusBarsPadding: the colored header bleeds under the status bar, content
            // sits below it (so ⚙ / the title no longer collide with the clock/notch).
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitanOrb(size = 34)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(titanLabel, color = TitanText, fontWeight = FontWeight.SemiBold)
                Text(
                    if (resting) "resting — tap restart in Console" else "online · sovereign on its server",
                    color = if (resting) TitanWarn else TitanGood,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "⚙",
                color = TitanMuted,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    turn: ChatTurn,
    onFeedback: (Int?, String) -> Unit = { _, _ -> },
    onAction: (Int, String, String) -> Unit = { _, _, _ -> },
) {
    val mine = turn.fromMaker
    var menuOpen by remember { mutableStateOf(false) }
    var reacted by remember(turn.id) { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
          Box {
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
                    // Long-press a bubble → Copy / Share (the requested text actions).
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(turn.text, color = if (mine) TitanInk else TitanText)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy") },
                    onClick = {
                        clipboard.setText(AnnotatedString(turn.text))
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = {
                        menuOpen = false
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, turn.text)
                                },
                                null,
                            ),
                        )
                    },
                )
            }
          }
          // A Channel-2 actionable system card (RFP §7.3 3a) shows its buttons (or the
          // "✓ Acknowledged" confirmation); a regular Titan turn shows feedback chips.
          if (turn.actions.isNotEmpty()) {
              ActionRow(turn) { id, label -> onAction(seqOf(turn.id) ?: -1, id, label) }
          } else if (!mine) {
              FeedbackChips(reacted) { reaction ->
                  reacted = reaction
                  onFeedback(seqOf(turn.id), reaction)
              }
          }
        }
    }
}

@Composable
private fun ActionRow(turn: ChatTurn, onPick: (String, String) -> Unit) {
    val responded = turn.respondedAction
    if (responded != null) {
        val label = turn.actions.firstOrNull { it.id == responded }?.label ?: responded
        Text(
            "✓ $label",
            color = TitanCyan,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            turn.actions.forEach { a ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(TitanCyan)
                        .clickable { onPick(a.id, a.label) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(a.label, color = TitanInk, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Parse the originating event seq from a turn id ("evt-<seq>"); null for owner/manual turns. */
private fun seqOf(id: String): Int? = id.removePrefix("evt-").toIntOrNull()

@Composable
private fun FeedbackChips(picked: String?, onPick: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("👍" to "like", "👎" to "dislike", "useful" to "useful",
               "interesting" to "interesting").forEach { (label, reaction) ->
            val on = picked == reaction
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) TitanCyan else TitanSurfaceHi)
                    .clickable { onPick(reaction) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(label, color = if (on) TitanInk else TitanMuted,
                     style = MaterialTheme.typography.labelSmall)
            }
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
            // navigationBars + ime padding: the composer rides above the gesture/nav bar
            // and lifts above the keyboard instead of being drawn under them.
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
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
