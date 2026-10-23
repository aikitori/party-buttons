package de.aikitori.partybuttons

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat

// AppCompatActivity statt ComponentActivity: nur damit funktioniert AppCompatDelegate.setApplicationLocales
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PartyButtonsTheme {
                Surface(Modifier.fillMaxSize()) {
                    HomeScreen()
                }
            }
        }
    }
}

@Composable
private fun PartyButtonsTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun HomeScreen() {
    Column(
        Modifier.safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.placeholder))
        LanguagePicker()
    }
}

/** Sprache der App: System, Englisch oder Deutsch. Android merkt sich die Wahl selbst. */
@Composable
private fun LanguagePicker() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val choices = listOf(
        "" to R.string.language_system,
        "en" to R.string.language_en,
        "de" to R.string.language_de,
    )
    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((tag, label) in choices) {
            FilterChip(
                selected = current == tag,
                onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)) },
                label = { Text(stringResource(label)) },
            )
        }
    }
}
