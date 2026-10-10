package com.qrhry.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.qrhry.app.ui.GameScreen
import com.qrhry.app.ui.GameViewModel

class MainActivity : AppCompatActivity() {
    private val gameViewModel: GameViewModel by viewModels()
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private var isDarkMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDarkMode = preferences.getBoolean(PREFERENCE_DARK_MODE, false)
        setContent {
            val colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
            SideEffect {
                window.statusBarColor = colorScheme.surface.toArgb()
                window.navigationBarColor = colorScheme.surface.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !isDarkMode
                    isAppearanceLightNavigationBars = !isDarkMode
                }
            }
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GameScreen(
                        viewModel = gameViewModel,
                        isDarkMode = isDarkMode,
                        onDarkModeChange = { enabled ->
                            isDarkMode = enabled
                            preferences.edit().putBoolean(PREFERENCE_DARK_MODE, enabled).apply()
                        },
                        onLaunchQrScanner = ::launchQrScanner
                    )
                }
            }
        }
    }

    private fun launchQrScanner() {
        GmsBarcodeScanning.getClient(this)
            .startScan()
            .addOnSuccessListener { barcode: Barcode? ->
                gameViewModel.processQrPayload(barcode?.rawValue)
            }
            .addOnCanceledListener {
                gameViewModel.onQrScanCancelled()
            }
            .addOnFailureListener {
                gameViewModel.onQrScannerError()
            }
    }

    companion object {
        const val PREFERENCES_NAME = "qrhry_preferences"
        const val PREFERENCE_DARK_MODE = "dark_mode"
    }
}