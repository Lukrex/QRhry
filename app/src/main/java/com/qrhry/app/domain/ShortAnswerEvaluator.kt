package com.qrhry.app.domain

import java.text.Normalizer
import java.util.Locale

object ShortAnswerEvaluator {
    fun normalize(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun matches(expected: String, submitted: String): Boolean = normalize(expected) == normalize(submitted)
}
