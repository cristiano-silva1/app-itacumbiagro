package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `verify app name resource`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Itacumbi Agro", appName)
  }

  @Test
  fun `verify rainfall logging with date volume and notes`() {
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    val testDate = "18/09/2026"
    val parsedDate = sdf.parse(testDate)
    assertEquals(testDate, sdf.format(parsedDate ?: Date()))

    val volumeInput = "35,5"
    val parsedVolume = volumeInput.replace(',', '.').toDoubleOrNull() ?: 0.0
    assertEquals(35.5, parsedVolume, 0.001)

    val log = RainfallLog(
      id = UUID.randomUUID().toString(),
      date = testDate,
      farmName = "Fazenda Itacumbi",
      volumeMm = parsedVolume,
      notes = "Chuva forte à tarde"
    )

    assertEquals("18/09/2026", log.date)
    assertEquals(35.5, log.volumeMm, 0.001)
    assertEquals("Chuva forte à tarde", log.notes)
    assertEquals("Fazenda Itacumbi", log.farmName)
  }
}
