package com.qrhry.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.storage.FirebaseStorage

class QRhryApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initializeFirebase()
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("sk"))
        }
    }

    private fun initializeFirebase() {
        if (!BuildConfig.CATALOG_CONFIGURED) return
        runCatching {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .setStorageBucket(BuildConfig.FIREBASE_STORAGE_BUCKET)
                .build()
            if (FirebaseApp.getApps(this).isEmpty()) FirebaseApp.initializeApp(this, options)
            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                if (BuildConfig.DEBUG) DebugAppCheckProviderFactory.getInstance()
                else PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            if (BuildConfig.DEBUG && BuildConfig.FIREBASE_EMULATOR_HOST.isNotBlank()) {
                val host = BuildConfig.FIREBASE_EMULATOR_HOST
                FirebaseAuth.getInstance().useEmulator(host, 9099)
                FirebaseFirestore.getInstance().useEmulator(host, 8080)
                FirebaseStorage.getInstance().useEmulator(host, 9199)
                FirebaseFunctions.getInstance("europe-west1").useEmulator(host, 5001)
            }
        }
    }
}
