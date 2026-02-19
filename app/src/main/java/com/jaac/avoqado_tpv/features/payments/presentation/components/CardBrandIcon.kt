package com.jaac.avoqado_tpv.features.payments.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jaac.avoqado_tpv.features.payments.domain.models.CardBrand

/**
 * Card brand icon badge — renders actual SVG logos for Visa, Mastercard, Amex.
 * SVG paths sourced from avoqado-web-dashboard/src/utils/getIcon.tsx.
 * Other brands show text fallback inside the same bordered container.
 */
@Composable
fun CardBrandIcon(
    cardBrand: CardBrand,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(width = 34.dp, height = 24.dp),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, Color(0xFFE0E0E0)),
        color = Color.White
    ) {
        when (cardBrand) {
            CardBrand.VISA -> VisaLogo(
                Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 5.dp)
            )
            CardBrand.MASTERCARD -> MastercardLogo(
                Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 3.dp)
            )
            CardBrand.AMERICAN_EXPRESS -> AmexLogo(
                Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 4.dp)
            )
            else -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = cardBrand.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = Color(0xFF333333),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// — SVG paths from getIcon.tsx —

@Composable
private fun VisaLogo(modifier: Modifier = Modifier) {
    val path = remember {
        PathParser().parsePathString(
            "M13.027 0c-1.42 0-2.69.85-2.69 2.42 0 1.802 2.251 1.926 2.251 2.831 " +
            "0 .381-.378.722-1.023.722a3.073 3.073 0 0 1-1.602-.476L9.67 7.082" +
            "s.79.402 1.837.402c1.552 0 2.774-.891 2.774-2.489 0-1.903-2.26-2.024" +
            "-2.26-2.864 0-.298.31-.625.954-.625.727 0 1.32.346 1.32.346l.286-1.53" +
            "S13.936 0 13.027 0ZM.034.116 0 .346s.597.127 1.136.379c.692.289.742.457" +
            ".858.98l1.272 5.66H4.97L7.596.115h-1.7l-1.688 4.93L3.52.866" +
            "c-.063-.478-.383-.75-.775-.75H.035Zm8.246 0-1.334 7.25h1.622L9.897.115" +
            "H8.28Zm9.044 0c-.39 0-.598.241-.75.664l-2.376 6.585h1.7l.33-1.097" +
            "h2.071l.2 1.097H20L18.691.115h-1.367Zm.221 1.958.504 2.72H16.7l.846-2.72Z"
        ).toPath()
    }
    val color = Color(0xFF1434CB)
    Canvas(modifier) {
        val s = minOf(size.width / 20f, size.height / 7.48f)
        val ox = (size.width - 20f * s) / 2f
        val oy = (size.height - 7.48f * s) / 2f
        translate(left = ox, top = oy) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(path, color)
            }
        }
    }
}

@Composable
private fun MastercardLogo(modifier: Modifier = Modifier) {
    // Three paths: orange overlap rect, red left circle, yellow right circle
    // viewBox 0 0 16 ~10 (circles only, text omitted for badge size)
    val orangePath = remember {
        PathParser().parsePathString(
            "M10.158 1.06h-4.33v7.79h4.33V1.06Z"
        ).toPath()
    }
    val redPath = remember {
        PathParser().parsePathString(
            "M6.117 4.955c0-1.582.743-2.987 1.883-3.895" +
            "A4.917 4.917 0 0 0 4.948 0 4.949 4.949 0 0 0 0 4.955" +
            "a4.949 4.949 0 0 0 4.948 4.956c1.154 0 2.213-.4 3.052-1.06" +
            "a4.948 4.948 0 0 1-1.883-3.896Z"
        ).toPath()
    }
    val yellowPath = remember {
        PathParser().parsePathString(
            "M16 4.955a4.949 4.949 0 0 1-4.948 4.956c-1.155 0-2.213-.4-3.052-1.06" +
            "a4.931 4.931 0 0 0 1.883-3.896A4.972 4.972 0 0 0 8 1.06" +
            "A4.909 4.909 0 0 1 11.05 0C13.787 0 16 2.23 16 4.955Z"
        ).toPath()
    }
    Canvas(modifier) {
        val vw = 16f; val vh = 10f
        val s = minOf(size.width / vw, size.height / vh)
        val ox = (size.width - vw * s) / 2f
        val oy = (size.height - vh * s) / 2f
        translate(left = ox, top = oy) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(orangePath, Color(0xFFFE5A00))
                drawPath(redPath, Color(0xFFEB001B))
                drawPath(yellowPath, Color(0xFFF79E1C))
            }
        }
    }
}

@Composable
private fun AmexLogo(modifier: Modifier = Modifier) {
    val path = remember {
        PathParser().parsePathString(
            "M1.113.43.004 3.022h.722l.204-.52h1.19l.205.52h.74L1.954.429h-.842Z" +
            "m.411.6.363.907h-.726l.363-.906Z" +
            "M3.14 3.02V.425L4.166.43l.596 1.672.583-1.676h1.018v2.593H5.72V1.11" +
            "l-.684 1.91H4.47l-.686-1.91v1.91h-.645Z" +
            "M6.807 3.02V.425h2.105v.58H7.46v.444h1.417v.546h-1.42v.46H8.91v.563H6.807Z" +
            "M9.284.428v2.594h.645V2.1h.27l.775.922h.787l-.849-.956" +
            "a.788.788 0 0 0 .708-.796c0-.546-.426-.84-.902-.84L9.284.428Z" +
            "m.645.58h.737c.176 0 .305.139.305.271 0 .173-.167.272-.297.272H9.93v-.544Z" +
            "M12.542 3.02h-.658V.425h.658v2.593Z" +
            "M14.104 3.02h-.143c-.689 0-1.104-.544-1.104-1.285 0-.759.413-1.307 1.28-1.307" +
            "h.713v.615h-.737c-.352 0-.603.276-.603.698 0 .502.286.712.695.712h.17l-.271.567Z" +
            "M15.508.428l-1.109 2.591h.722l.204-.52h1.19l.205.52h.737L16.348.426l-.84.002Z" +
            "m.409.601.363.907h-.726l.363-.907Z" +
            "M17.532 3.02V.425h.82l1.047 1.63V.425h.645v2.593h-.794l-1.074-1.671v1.671h-.644Z" +
            "M4.701 7.557V4.963h2.106v.58H5.355v.444h1.419v.546h-1.42v.46h1.453v.564H4.7Z" +
            "M15.013 7.557V4.963h2.105v.58h-1.452v.444h1.412v.546h-1.412v.46h1.452v.564h-2.105Z" +
            "M6.888 7.557l1.025-1.28-1.05-1.314h.813l.624.812.627-.812h.781L8.672 6.26 9.7 7.555" +
            "h-.812l-.607-.799-.592.799-.801.002Z" +
            "M9.777 4.965V7.56h.662v-.818h.68c.574 0 1.01-.307 1.01-.902 0-.493-.341-.871-.927-.871" +
            "H9.777v-.003Zm.662.586h.715c.185 0 .319.115.319.299 0 .172-.132.298-.321.298h-.713v-.597Z" +
            "M12.406 4.963v2.594h.644v-.922h.27l.775.922h.788l-.85-.955" +
            "a.788.788 0 0 0 .709-.796c0-.547-.427-.84-.902-.84l-1.434-.003Z" +
            "m.646.582h.737c.176 0 .306.139.306.272 0 .172-.167.272-.297.272h-.746v-.544Z" +
            "M17.417 7.557v-.564h1.292c.191 0 .272-.104.272-.217 0-.108-.081-.219-.272-.219h-.583" +
            "c-.506 0-.79-.31-.79-.776 0-.415.26-.816 1.012-.816h1.256l-.27.584h-1.087" +
            "c-.207 0-.271.11-.271.215 0 .108.08.225.238.225h.611c.566 0 .81.323.81.745 " +
            "0 .454-.273.825-.84.825h-1.378v-.002Z" +
            "M19.784 7.557v-.564h1.292c.191 0 .273-.104.273-.217 0-.108-.082-.219-.273-.219h-.583" +
            "c-.506 0-.79-.31-.79-.776 0-.415.26-.816 1.012-.816h1.256l-.27.584h-1.087" +
            "c-.207 0-.27.11-.27.215 0 .108.078.225.237.225h.611c.566 0 .81.323.81.745 " +
            "0 .454-.273.825-.84.825h-1.378v-.002Z"
        ).toPath()
    }
    val color = Color(0xFF016FD0)
    Canvas(modifier) {
        val s = minOf(size.width / 22f, size.height / 8f)
        val ox = (size.width - 22f * s) / 2f
        val oy = (size.height - 8f * s) / 2f
        translate(left = ox, top = oy) {
            scale(s, s, pivot = Offset.Zero) {
                drawPath(path, color)
            }
        }
    }
}
