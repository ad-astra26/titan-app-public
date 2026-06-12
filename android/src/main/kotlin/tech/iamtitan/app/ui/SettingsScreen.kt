package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tech.iamtitan.app.data.LockMode

/**
 * Settings — currently the App-lock policy (the first of what will grow as the app
 * does). All four lock modes are user-settable; the default (set in SecuritySettings)
 * is [LockMode.ON_LAUNCH]. In every mode there is NO per-chat-turn biometric — one
 * unlock opens the signing window (DeviceKey).
 */
@Composable
fun SettingsScreen(
    lockMode: LockMode,
    lockTimerMinutes: Int,
    onLockModeChange: (LockMode) -> Unit,
    onTimerChange: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(TitanInk)) {
        SettingsHeader(onClose)
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("App lock", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "When to ask for your fingerprint / screen lock. You're never asked per message — one unlock covers the session.",
                color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            LockOption(LockMode.ON_LAUNCH, "Lock on app launch", "Ask once when the app starts (default).",
                lockMode, onLockModeChange)
            LockOption(LockMode.TIMER, "Lock after a delay",
                "Ask after $lockTimerMinutes min in the background.", lockMode, onLockModeChange)
            if (lockMode == LockMode.TIMER) {
                TimerStepper(lockTimerMinutes, onTimerChange)
            }
            LockOption(LockMode.IMMEDIATE, "Lock immediately", "Ask every time you reopen the app.",
                lockMode, onLockModeChange)
            LockOption(LockMode.OFF, "Off", "No lock screen. (Signing still verifies you about every 8 hours.)",
                lockMode, onLockModeChange)
        }
    }
}

@Composable
private fun SettingsHeader(onClose: () -> Unit) {
    Surface(color = TitanSurface, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Settings", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = TitanSurfaceHi, contentColor = TitanText),
            ) { Text("Done") }
        }
    }
}

@Composable
private fun LockOption(
    mode: LockMode,
    title: String,
    subtitle: String,
    selected: LockMode,
    onSelect: (LockMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (mode == selected) TitanSurfaceHi else TitanSurface)
            .clickable { onSelect(mode) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = mode == selected,
            onClick = { onSelect(mode) },
            colors = RadioButtonDefaults.colors(selectedColor = TitanCyan, unselectedColor = TitanMuted),
        )
        Spacer(Modifier.size(8.dp))
        Column {
            Text(title, color = TitanText, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TimerStepper(minutes: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepButton("−") { onChange((minutes - 1).coerceAtLeast(1)) }
        Text("$minutes min", color = TitanText, fontWeight = FontWeight.SemiBold)
        StepButton("+") { onChange((minutes + 1).coerceAtMost(240)) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = TitanSurfaceHi, contentColor = TitanCyan),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}
