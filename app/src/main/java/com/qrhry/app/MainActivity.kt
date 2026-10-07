package com.qrhry.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.qrhry.app.ui.GameScreen
import com.qrhry.app.ui.GameViewModel

class MainActivity : ComponentActivity() {
    private val gameViewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GameScreen(
                    viewModel = gameViewModel,
                    onLaunchQrScanner = ::launchQrScanner
                )
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
}