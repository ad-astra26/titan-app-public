package tech.iamtitan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
    alwaysConnected: Boolean,
    availability: String,
    presenceLocation: Boolean,
    presenceTime: Boolean,
    presenceBattery: Boolean,
    advancedOpsEnabled: Boolean,
    onLockModeChange: (LockMode) -> Unit,
    onTimerChange: (Int) -> Unit,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onAvailabilityChange: (String) -> Unit,
    onPresenceLocationChange: (Boolean) -> Unit,
    onPresenceTimeChange: (Boolean) -> Unit,
    onPresenceBatteryChange: (Boolean) -> Unit,
    onAdvancedOpsChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(TitanInk).systemBarsPadding()) {
        SettingsHeader(onClose)
        // Scrollable content — the section list now exceeds a phone screen (Connection +
        // App-lock), so without this the lower options were clipped + unreachable.
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Connection", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            ToggleRow(
                title = "Stay connected",
                subtitle = "Get Titan's messages instantly in the background. Shows a quiet ongoing " +
                    "notification and uses a little more battery. Off = messages arrive every ~15 min.",
                checked = alwaysConnected,
                onChange = onAlwaysConnectedChange,
            )
            Spacer(Modifier.height(8.dp))
            Text("Availability", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "What you tell Titan about your time. He reasons about this — it's a hint to him, not a hard mute; he decides whether to reach out.",
                color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            AvailabilityOption("available", "Available", "Open to hear from Titan.",
                availability, onAvailabilityChange)
            AvailabilityOption("busy", "Busy", "He'll tend to hold non-urgent messages.",
                availability, onAvailabilityChange)
            AvailabilityOption("dnd", "Do not disturb", "Only the most important, by his judgment.",
                availability, onAvailabilityChange)
            Spacer(Modifier.height(8.dp))
            Text("Presence", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "What you let Titan sense about your world. Each is opt-in and off by default; " +
                    "it's collected only while on and sampled in the background about every 15 min.",
                color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Share location",
                subtitle = "Your approximate location (GPS/network — no Google services). Lets Titan " +
                    "know where in the world you are.",
                checked = presenceLocation,
                onChange = onPresenceLocationChange,
            )
            ToggleRow(
                title = "Share local time",
                subtitle = "Your time zone and local clock — so Titan knows what hour it is in your world.",
                checked = presenceTime,
                onChange = onPresenceTimeChange,
            )
            ToggleRow(
                title = "Share battery",
                subtitle = "Your phone's battery level.",
                checked = presenceBattery,
                onChange = onPresenceBatteryChange,
            )
            Spacer(Modifier.height(8.dp))
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
            Spacer(Modifier.height(8.dp))
            Text("Advanced", color = TitanText, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "Unlocks the per-layer ops console — restart individual workers, reload the API, " +
                    "reboot the VPS, and clean up stale processes. For advanced users; off by default. " +
                    "Turning it on asks for your app lock.",
                color = TitanMuted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            ToggleRow(
                title = "Advanced ops console",
                subtitle = "Show the Advanced ops surface on the Home screen. Every action there is confirmed.",
                checked = advancedOpsEnabled,
                onChange = onAdvancedOpsChange,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(TitanSurface)
            .clickable { onChange(!checked) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TitanText, fontWeight = FontWeight.Medium)
            Text(subtitle, color = TitanMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.size(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = TitanInk,
                checkedTrackColor = TitanCyan,
                uncheckedThumbColor = TitanMuted,
                uncheckedTrackColor = TitanSurfaceHi,
            ),
        )
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
private fun AvailabilityOption(
    value: String,
    title: String,
    subtitle: String,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (value == selected) TitanSurfaceHi else TitanSurface)
            .clickable { onSelect(value) }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = value == selected,
            onClick = { onSelect(value) },
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
