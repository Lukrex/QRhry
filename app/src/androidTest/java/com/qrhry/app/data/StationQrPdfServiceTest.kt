package com.qrhry.app.data

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.common.HybridBinarizer
import com.qrhry.app.data.local.StationQrPdfService
import com.qrhry.app.domain.Game
import com.qrhry.app.domain.Station
import com.qrhry.app.qr.StationQrPayload
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StationQrPdfServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pdfPaginatesIncludesEveryStationAndPreservesQrPayloadIdentity() {
        val game = sampleGame(7)
        val pdf = StationQrPdfService(context).create(game)
        try {
            PdfRenderer(ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertEquals(2, renderer.pageCount)
                val decoded = mutableSetOf<String>()
                val reader = MultiFormatReader()
                val multipleReader = GenericMultipleBarcodeReader(reader)
                for (index in 0 until renderer.pageCount) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
                        runCatching {
                            multipleReader.decodeMultiple(BinaryBitmap(HybridBinarizer(source))).forEach { decoded += it.text }
                        }
                        bitmap.recycle()
                    }
                }
                assertTrue(game.stations.map { StationQrPayload.create(it.qrToken) }.all { it in decoded })
            }
        } finally { pdf.delete() }
    }

    @Test
    fun longSlovakStationNamesWithDiacriticsGeneratePrintablePdf() {
        val game = sampleGame(1).copy(stations = listOf(sampleGame(1).stations.single().copy(
            title = "Železničné múzeum — najstaršia výstavná miestnosť v meste"
        )))
        val pdf = StationQrPdfService(context).create(game)
        try { assertTrue(pdf.length() > 1000L) } finally { pdf.delete() }
    }

    @Test
    fun fileProviderIsRegisteredAndPdfShareableThroughContentUri() {
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertTrue(provider != null)
        assertEquals(false, provider!!.exported)
        assertTrue(provider.grantUriPermissions)
        val sample = StationQrPdfService(context).create(sampleGame(1))
        try {
            val uri = FileProvider.getUriForFile(context, authority, sample)
            assertEquals("content", uri.scheme)
            assertEquals("application/pdf", context.contentResolver.getType(uri))
        } finally { sample.delete() }
    }

    private fun sampleGame(count: Int): Game {
        val gameId = 17L
        return Game(
            id = gameId,
            title = "QR game",
            stations = (0 until count).map { index ->
                Station(index + 1L, gameId, "Stanovište ${index + 1}", "", index, UUID.randomUUID().toString())
            }
        )
    }
}
