package io.github.hamzabhai.jhatpat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val Orange = Color(0xFFF97316)
val Yellow = Color(0xFFFACC15)
val Ink = Color(0xFF17110C)
val Panel = Color(0xFF2B2118)
val Cream = Color(0xFFFDE9CF)
val Muted = Color(0xFFB99874)

private val colors = darkColorScheme(
    primary = Orange,
    onPrimary = Ink,
    secondary = Yellow,
    onSecondary = Ink,
    background = Ink,
    onBackground = Cream,
    surface = Panel,
    onSurface = Cream,
    error = Color(0xFFFF8A80),
)

@Composable
fun JhatPatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

/** The big orange button. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Orange, contentColor = Ink),
    ) {
        Text(text, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, color = Cream, modifier = Modifier.padding(vertical = 2.dp))
    }
}

/** A rounded panel with an orange outline, like the cards on the web pages. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Panel,
        border = androidx.compose.foundation.BorderStroke(2.dp, Orange.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
