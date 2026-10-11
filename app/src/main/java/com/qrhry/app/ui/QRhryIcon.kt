package com.qrhry.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

/** Small, shared outline icon set drawn in a 24 dp coordinate space. */
internal enum class QRhryIconKind {
    QR, PLAY, CREATE, LOCATION, SETTINGS, LANGUAGE, THEME, SUN, BACK, FORWARD, SCAN,
    IMPORT, EXPORT, ADD, REMOVE, SAVE, EDIT, IMAGE, AUDIO, TASK,
    UP, DOWN, RESULTS, CLOSE, CHECK
}

@Composable
internal fun QRhryIcon(
    kind: QRhryIconKind,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier = modifier.size(size)) {
        val factor = this.size.minDimension / 24f
        scale(factor, pivot = Offset.Zero) {
            drawQRhryIcon(kind, tint)
        }
    }
}

@Composable
internal fun IconLabel(
    kind: QRhryIconKind,
    label: String,
    iconTint: Color? = null,
    iconSize: Dp = 20.dp,
    maxLines: Int = 1
) {
    val resolvedTint = iconTint ?: LocalContentColor.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.defaultMinSize(minHeight = 24.dp)
    ) {
        QRhryIcon(kind, size = iconSize, tint = resolvedTint)
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = maxLines, softWrap = maxLines > 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.drawQRhryIcon(kind: QRhryIconKind, color: Color) {
    val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = 1.8f) = drawLine(
        color, Offset(x1, y1), Offset(x2, y2), strokeWidth = width,
        cap = StrokeCap.Round
    )
    fun rect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 0f) {
        val size = Size(right - left, bottom - top)
        if (radius > 0f) drawRoundRect(color, Offset(left, top), size, androidx.compose.ui.geometry.CornerRadius(radius), style = stroke)
        else drawRect(color, Offset(left, top), size, style = stroke)
    }
    fun path(block: Path.() -> Unit) = drawPath(Path().apply(block), color, style = stroke)
    fun circle(x: Float, y: Float, radius: Float, filled: Boolean = false) = drawCircle(
        color, radius, Offset(x, y), style = if (filled) androidx.compose.ui.graphics.drawscope.Fill else stroke
    )
    when (kind) {
        QRhryIconKind.QR, QRhryIconKind.SCAN -> {
            rect(3f, 3f, 9f, 9f, 1f); rect(15f, 3f, 21f, 9f, 1f); rect(3f, 15f, 9f, 21f, 1f)
            rect(5f, 5f, 7f, 7f); rect(17f, 5f, 19f, 7f); rect(5f, 17f, 7f, 19f)
            rect(15f, 15f, 18f, 18f, 0.5f); rect(19f, 19f, 21f, 21f, 0.5f)
            line(18.5f, 15f, 21f, 15f); line(21f, 15f, 21f, 17.5f)
        }
        QRhryIconKind.PLAY -> path { moveTo(7f, 4f); lineTo(20f, 12f); lineTo(7f, 20f); close() }
        QRhryIconKind.CREATE -> {
            path { moveTo(12f, 22f); cubicTo(12f, 22f, 4f, 13f, 4f, 9f); cubicTo(4f, 4.5f, 7.5f, 2f, 12f, 2f); cubicTo(16.5f, 2f, 20f, 4.5f, 20f, 9f); cubicTo(20f, 13f, 12f, 22f, 12f, 22f) }
            circle(12f, 9f, 2.4f)
            line(16.5f, 14.5f, 16.5f, 21f); line(13.5f, 17.75f, 19.5f, 17.75f)
        }
        QRhryIconKind.LOCATION -> {
            path { moveTo(12f, 21f); cubicTo(12f, 21f, 4f, 13.5f, 4f, 9.5f); cubicTo(4f, 4.5f, 7.5f, 2f, 12f, 2f); cubicTo(16.5f, 2f, 20f, 4.5f, 20f, 9.5f); cubicTo(20f, 13.5f, 12f, 21f, 12f, 21f) }
            circle(12f, 9f, 2.5f)
        }
        QRhryIconKind.SETTINGS -> {
            circle(12f, 12f, 4f)
            for (angle in 0 until 360 step 45) {
                val radians = Math.toRadians(angle.toDouble())
                val dx = kotlin.math.cos(radians).toFloat()
                val dy = kotlin.math.sin(radians).toFloat()
                line(12f + dx * 6f, 12f + dy * 6f, 12f + dx * 9f, 12f + dy * 9f, 2.6f)
            }
        }
        QRhryIconKind.LANGUAGE -> {
            circle(12f, 12f, 9f); path { moveTo(3.5f, 9f); lineTo(20.5f, 9f); moveTo(3.5f, 15f); lineTo(20.5f, 15f) }
            path { moveTo(12f, 3f); cubicTo(7f, 7f, 7f, 17f, 12f, 21f); cubicTo(17f, 17f, 17f, 7f, 12f, 3f) }
        }
        QRhryIconKind.THEME -> {
            path { moveTo(19.5f, 15.5f); cubicTo(12f, 17f, 7f, 12f, 8.5f, 4.5f); cubicTo(4f, 6f, 3f, 10f, 4.5f, 14f); cubicTo(7f, 21f, 16f, 22f, 20f, 16f); close() }
        }
        QRhryIconKind.SUN -> {
            circle(12f, 12f, 4f)
            for (angle in 0 until 360 step 45) {
                val radians = Math.toRadians(angle.toDouble())
                val dx = kotlin.math.cos(radians).toFloat()
                val dy = kotlin.math.sin(radians).toFloat()
                line(12f + dx * 7f, 12f + dy * 7f, 12f + dx * 9f, 12f + dy * 9f)
            }
        }
        QRhryIconKind.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
        QRhryIconKind.FORWARD -> { line(5f, 12f, 19f, 12f); line(19f, 12f, 13f, 6f); line(19f, 12f, 13f, 18f) }
        QRhryIconKind.IMPORT -> { line(12f, 3f, 12f, 15f); line(7f, 10f, 12f, 15f); line(12f, 15f, 17f, 10f); path { moveTo(5f, 16f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 16f) } }
        QRhryIconKind.EXPORT -> { line(12f, 21f, 12f, 9f); line(7f, 14f, 12f, 9f); line(12f, 9f, 17f, 14f); path { moveTo(5f, 8f); lineTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 8f) } }
        QRhryIconKind.ADD -> { circle(12f, 12f, 9f); line(12f, 7f, 12f, 17f); line(7f, 12f, 17f, 12f) }
        QRhryIconKind.REMOVE -> { rect(5f, 7f, 19f, 20f, 2f); path { moveTo(3f, 5f); lineTo(21f, 5f); moveTo(9f, 4f); lineTo(15f, 4f) }; line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f) }
        QRhryIconKind.SAVE -> { path { moveTo(5f, 3f); lineTo(17f, 3f); lineTo(21f, 7f); lineTo(21f, 21f); lineTo(3f, 21f); lineTo(3f, 5f); close() }; rect(7f, 3f, 16f, 9f); rect(7f, 13f, 17f, 21f, 1f) }
        QRhryIconKind.EDIT -> { path { moveTo(4f, 17.5f); lineTo(3f, 21f); lineTo(6.5f, 20f); lineTo(19.5f, 7f); lineTo(17f, 4.5f); close() }; line(15.5f, 6f, 18f, 8.5f) }
        QRhryIconKind.IMAGE -> { rect(3f, 4f, 21f, 20f, 2f); circle(8f, 9f, 1.5f); path { moveTo(4f, 17f); lineTo(9f, 12f); lineTo(13f, 16f); lineTo(16f, 13f); lineTo(20f, 17f) } }
        QRhryIconKind.AUDIO -> { path { moveTo(4f, 10f); lineTo(8f, 10f); lineTo(13f, 6f); lineTo(13f, 18f); lineTo(8f, 14f); lineTo(4f, 14f); close() }; path { moveTo(16f, 9f); cubicTo(19f, 11f, 19f, 13f, 16f, 15f); moveTo(18f, 6f); cubicTo(23f, 10f, 23f, 14f, 18f, 18f) } }
        QRhryIconKind.TASK -> { rect(4f, 3f, 20f, 21f, 2f); line(8f, 8f, 10f, 10f); line(10f, 10f, 13f, 6f); line(15f, 8f, 17f, 8f); line(8f, 15f, 10f, 17f); line(10f, 17f, 13f, 13f); line(15f, 15f, 17f, 15f) }
        QRhryIconKind.UP -> { line(12f, 20f, 12f, 4f); line(6f, 10f, 12f, 4f); line(12f, 4f, 18f, 10f) }
        QRhryIconKind.DOWN -> { line(12f, 4f, 12f, 20f); line(6f, 14f, 12f, 20f); line(12f, 20f, 18f, 14f) }
        QRhryIconKind.RESULTS -> { path { moveTo(4f, 20f); lineTo(20f, 20f); moveTo(6f, 17f); lineTo(6f, 12f); moveTo(12f, 17f); lineTo(12f, 7f); moveTo(18f, 17f); lineTo(18f, 4f) } }
        QRhryIconKind.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        QRhryIconKind.CHECK -> { line(4f, 12f, 9f, 17f); line(9f, 17f, 20f, 6f) }
    }
}
