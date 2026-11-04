package com.jaac.avoqado_tpv.features.verification.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.jaac.avoqado_tpv.core.data.local.AvoqadoDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [28])
class ProofOfSaleQueueDaoTest {
    private lateinit var db: AvoqadoDatabase
    private lateinit var dao: ProofOfSaleQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AvoqadoDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.proofOfSaleQueueDao()
    }

    @After fun tearDown() = db.close()

    private fun row(key: String, venue: String = "v1", created: Long = 1L, retry: Int = 0, status: String = "PENDING", claimedAt: Long? = null) =
        ProofOfSaleQueueEntity(
            idempotencyKey = key, kind = "PHOTO", venueId = venue, staffId = "s1", paymentId = "p-$key", verificationId = null,
            slot = "VINCULACION", localPath = "/x/$key.jpg", linkingNumber = "5512345678", portedNumber = null,
            capturedAt = created, captureSeq = created, captureDeviceId = "dev", syncStatus = status, retryCount = retry,
            claimedAt = claimedAt, claimToken = if (status == "SYNCING") "viejo" else null, createdAt = created,
        )

    @Test fun `insertRow ignora una llave repetida`() = runTest {
        assertThat(dao.insertRow(row("a"))).isGreaterThan(0)
        assertThat(dao.insertRow(row("a", created = 99))).isEqualTo(-1)
    }

    @Test fun `claimBatch toma filas de cualquier tienda, las que menos fallaron primero`() = runTest {
        dao.insertRow(row("falla", venue = "v1", created = 1, retry = 5))
        dao.insertRow(row("nueva", venue = "v2", created = 9, retry = 0))
        val claimed = dao.claimBatch(10, "t1", now = 100, staleBefore = 0)
        assertThat(claimed.map { it.idempotencyKey }).containsExactly("nueva", "falla").inOrder()
        assertThat(claimed.all { it.syncStatus == "SYNCING" && it.claimToken == "t1" }).isTrue()
    }

    @Test fun `claimBatch rescata un SYNCING vencido y no uno reciente`() = runTest {
        dao.insertRow(row("vencido", status = "SYNCING", claimedAt = 10))
        dao.insertRow(row("reciente", status = "SYNCING", claimedAt = 500))
        val claimed = dao.claimBatch(10, "t2", now = 1000, staleBefore = 100)
        assertThat(claimed.map { it.idempotencyKey }).containsExactly("vencido")
    }

    @Test fun `release con token ajeno no cambia nada y con el propio vuelve a PENDING`() = runTest {
        dao.insertRow(row("a"))
        dao.claimBatch(1, "mio", now = 1, staleBefore = 0)
        assertThat(dao.release("a", "ajeno", 3, "boom")).isEqualTo(0)
        assertThat(dao.observeKey("a").first()!!.syncStatus).isEqualTo("SYNCING")
        assertThat(dao.release("a", "mio", 3, "boom")).isEqualTo(1)
        val r = dao.observeKey("a").first()!!
        assertThat(r.syncStatus).isEqualTo("PENDING")
        assertThat(r.retryCount).isEqualTo(3)
        assertThat(r.claimToken).isNull()
    }

    @Test fun `NEEDS_ATTENTION y FAILED permanente no se vuelven a reclamar`() = runTest {
        dao.insertRow(row("a"))
        dao.insertRow(row("b"))
        dao.claimBatch(2, "t", now = 1, staleBefore = 0)
        assertThat(dao.markNeedsAttention("a", "t", "SALE_ALREADY_APPROVED")).isEqualTo(1)
        assertThat(dao.markPermanentlyFailed("b", "t", "PERMANENT", "400")).isEqualTo(1)
        assertThat(dao.claimBatch(10, "t2", now = 1_000_000, staleBefore = 999_999)).isEmpty()
        assertThat(dao.observeKey("a").first()!!.outcome).isEqualTo("SALE_ALREADY_APPROVED")
        assertThat(dao.observeKey("b").first()!!.permanent).isTrue()
    }

    @Test fun `markSuccess hace CAS por token`() = runTest {
        dao.insertRow(row("a"))
        dao.claimBatch(1, "mio", now = 1, staleBefore = 0)
        assertThat(dao.markSuccess("a", "otro")).isEqualTo(0)
        assertThat(dao.markSuccess("a", "mio")).isEqualTo(1)
        assertThat(dao.observeStaff("s1").first().single().syncStatus).isEqualTo("SUCCESS")
        assertThat(dao.delete("a")).isEqualTo(1)
    }

    @Test fun `markPermanentlyFailed y markNeedsAttention con token ajeno devuelven 0 y no tocan la fila`() = runTest {
        dao.insertRow(row("a"))
        dao.claimBatch(1, "mio", now = 1, staleBefore = 0)
        assertThat(dao.markPermanentlyFailed("a", "ajeno", "PERMANENT", "400")).isEqualTo(0)
        assertThat(dao.markNeedsAttention("a", "ajeno", "SALE_ALREADY_APPROVED")).isEqualTo(0)
        val r = dao.observeKey("a").first()!!
        assertThat(r.syncStatus).isEqualTo("SYNCING")
        assertThat(r.claimToken).isEqualTo("mio")
        assertThat(r.permanent).isFalse()
        assertThat(r.outcome).isNull()
    }

    @Test fun `savePhotoUrl hace CAS por token y localPaths trae los archivos que siguen en la cola`() = runTest {
        dao.insertRow(row("a"))
        dao.insertRow(row("b", created = 2).copy(localPath = null, kind = "NUMBER_ONLY"))
        dao.claimBatch(1, "mio", now = 1, staleBefore = 0)
        assertThat(dao.savePhotoUrl("a", "ajeno", "https://fb/a.jpg")).isEqualTo(0)
        assertThat(dao.observeKey("a").first()!!.photoUrl).isNull()
        assertThat(dao.savePhotoUrl("a", "mio", "https://fb/a.jpg")).isEqualTo(1)
        assertThat(dao.observeKey("a").first()!!.photoUrl).isEqualTo("https://fb/a.jpg")
        assertThat(dao.localPaths()).containsExactly("/x/a.jpg")
    }

    @Test fun `claimBatch respeta el limite y toma por retry_count y created_at`() = runTest {
        dao.insertRow(row("c", created = 3, retry = 0))
        dao.insertRow(row("a", created = 1, retry = 1))
        dao.insertRow(row("b", created = 2, retry = 0))
        val claimed = dao.claimBatch(2, "t", now = 100, staleBefore = 0)
        assertThat(claimed.map { it.idempotencyKey }).containsExactly("b", "c").inOrder()
        assertThat(dao.observeKey("a").first()!!.syncStatus).isEqualTo("PENDING")
    }
}
