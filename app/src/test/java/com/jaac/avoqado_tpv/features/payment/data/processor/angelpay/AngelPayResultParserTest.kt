package com.jaac.avoqado_tpv.features.payment.data.processor.angelpay

import android.app.Activity
import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AngelPayResultParserTest {
    @Test
    fun `review contradictory gateway timeout cannot become final issuer decline`() {
        for (call in listOf("""{"code":"G505","status":"ERROR"}""", """{"code":"G500","status":"TIMEOUT"}""")) {
            val data = Intent()
                .putExtra(AngelPayResultParser.EXTRA_TRANSACTION_RESULT, """{"approved":false,"code":"05","message":"declined"}""")
                .putExtra(AngelPayResultParser.EXTRA_CALL_RESULT, call)
            val result = AngelPayResultParser().parse(Activity.RESULT_OK, data)
            // With today's public result model the only lossless safe projection is UNKNOWN;
            // a richer model may preserve both codes, but cannot expose 05 alone to the consumer.
            assertThat(result).isInstanceOf(AngelPayResult.Failure::class.java)
            assertThat((result as AngelPayResult.Failure).code).isEqualTo("UNKNOWN")
        }
    }

    @Test
    fun `missing callback payload is unknown rather than cancellation`() {
        val result = AngelPayResultParser().parse(Activity.RESULT_CANCELED, null)
        assertThat(result).isInstanceOf(AngelPayResult.Failure::class.java)
        assertThat((result as AngelPayResult.Failure).code).isEqualTo("UNKNOWN")
    }

    @Test
    fun `malformed callback payload is unknown rather than cancellation`() {
        val data = Intent().putExtra(AngelPayResultParser.EXTRA_TRANSACTION_RESULT, "{broken")
            .putExtra(AngelPayResultParser.EXTRA_CALL_RESULT, "{broken")
        val result = AngelPayResultParser().parse(Activity.RESULT_OK, data)
        assertThat(result).isInstanceOf(AngelPayResult.Failure::class.java)
        assertThat((result as AngelPayResult.Failure).code).isEqualTo("UNKNOWN")
    }
}
