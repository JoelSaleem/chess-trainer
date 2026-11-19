package com.joelsaleem.chesstrainer.training

import kotlin.math.abs
import kotlin.math.roundToLong

/** Two decimals, locale-independent — replaces String.format(Locale.ROOT, "%.2f", x). */
internal fun formatTwoDecimals(value: Double): String {
    val scaled = (abs(value) * 100).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
}

/**
 * The localisation fallback's minimal printf. Handles the only specifiers the English strings use:
 * sequential %s and %d, positional %1$s / %2$s, and %% for a literal percent. Not a general
 * String.format — extend here if a new string needs another specifier.
 */
internal fun formatTemplate(template: String, args: Array<out Any?>): String {
    val out = StringBuilder()
    var i = 0
    var next = 0
    while (i < template.length) {
        val c = template[i]
        if (c != '%') {
            out.append(c); i++; continue
        }
        if (i + 1 >= template.length) {
            out.append('%'); break
        }
        val spec = template[i + 1]
        when {
            spec == '%' -> {
                out.append('%'); i += 2
            }
            spec == 's' || spec == 'd' -> {
                out.append(args.getOrNull(next).toString()); next++; i += 2
            }
            spec.isDigit() -> {
                var j = i + 1
                var num = 0
                while (j < template.length && template[j].isDigit()) {
                    num = num * 10 + (template[j] - '0'); j++
                }
                if (j + 1 < template.length && template[j] == '$' &&
                    (template[j + 1] == 's' || template[j + 1] == 'd')
                ) {
                    out.append(args.getOrNull(num - 1).toString()); i = j + 2
                } else {
                    out.append('%'); i++
                }
            }
            else -> {
                out.append('%'); i++
            }
        }
    }
    return out.toString()
}
