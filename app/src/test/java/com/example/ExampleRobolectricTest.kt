package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    // app_name is overridden per edition (res/values in src/pro), so asserting one
    // hardcoded label only passes for the standard flavour and fails for Pro.
    val expected = if (BuildConfig.FLAVOR == "pro") "LexiVoice Pro" else "LexiVoice"
    assertEquals(expected, appName)
  }
}
