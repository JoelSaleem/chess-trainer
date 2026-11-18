package com.joelsaleem.chesstrainer.training

import com.joelsaleem.chesstrainer.core.PieceColor
import java.util.Locale

/**
 * Text the training layer produces for the player to read.
 *
 * On iOS these keys resolve against the app bundle, falling back to the English
 * source when there is no catalog (as in the test bundle). The Android training
 * layer returns the English fallback; the real strings.xml lookup happens in the
 * UI layer (M9). Kept behind keys because a sentence this layer builds is shown
 * on screen and cannot be translated if assembled from English fragments.
 */
object L {
    fun t(key: String, english: String): String = english

    fun t(key: String, english: String, vararg arguments: Any?): String =
        String.format(Locale.ROOT, english, *arguments)

    fun color(color: PieceColor): String =
        if (color == PieceColor.white) t("color.white", "White") else t("color.black", "Black")
}
