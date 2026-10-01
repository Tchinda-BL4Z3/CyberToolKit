package com.example

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.ui.CyberToolkitViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for the tab state that [CyberToolkitViewModel] keeps in a
 * [SavedStateHandle].
 *
 * ### The behaviour this pins
 * The visible tab used to be a plain `mutableIntStateOf(0)` on the ViewModel. That
 * survives a configuration change, so it looked correct, but the system reclaims
 * the process whenever it needs the memory, and the operator was silently returned
 * to the Encoder with no warning and no trace of where they had been.
 *
 * `SavedStateHandle` is what fixes it: its contents are written into the activity
 * instance-state `Bundle`, which the platform persists and hands back after the
 * process is recreated. The tests below rebuild the ViewModel from the *same*
 * handle, which is exactly what the framework does on process death.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SelectedTabStateTest {

  private lateinit var application: Application

  @Before
  fun setUp() {
    application = ApplicationProvider.getApplicationContext()
  }

  private fun viewModel(handle: SavedStateHandle = SavedStateHandle()) =
    CyberToolkitViewModel(application, handle)

  @Test
  fun `a fresh process starts on the encoder tab`() {
    assertEquals(0, viewModel().selectedTab.value)
  }

  @Test
  fun `selecting a tab is reflected immediately`() {
    val viewModel = viewModel()
    viewModel.selectTab(3)
    assertEquals(3, viewModel.selectedTab.value)
  }

  @Test
  fun `the selected tab survives process death`() {
    // A handle that stands in for the saved instance state.
    val handle = SavedStateHandle()
    val before = viewModel(handle)
    before.selectTab(4)

    // The process dies and is rebuilt from the persisted bundle. In production the
    // platform re-creates the handle from the Bundle; sharing the same instance
    // models that round trip exactly.
    val after = viewModel(handle)
    assertEquals(
      "the tab was lost when the process was reclaimed",
      4,
      after.selectedTab.value
    )
  }

  @Test
  fun `every tab index round-trips`() {
    listOf(0, 1, 2, 3, 4).forEach { index ->
      val handle = SavedStateHandle()
      viewModel(handle).selectTab(index)
      assertEquals(
        "tab $index did not survive process death",
        index,
        viewModel(handle).selectedTab.value
      )
    }
  }

  @Test
  fun `the handle is written into a Bundle that can be persisted`() {
    val handle = SavedStateHandle()
    viewModel(handle).selectTab(2)

    // Round-trip through the platform's own serialisation rather than reading the
    // Bundle's internal layout, which is a private detail of SavedStateHandle.
    val bundle = handle.savedStateProvider().saveState()
    val restored = SavedStateHandle.createHandle(bundle, null)
    assertEquals(2, restored.get<Int>("selected_tab"))
  }

  @Test
  fun `a restored handle keeps the tab`() {
    val handle = SavedStateHandle()
    viewModel(handle).selectTab(3)

    val bundle = handle.savedStateProvider().saveState()
    val restored = SavedStateHandle.createHandle(bundle, null)
    assertEquals(3, viewModel(restored).selectedTab.value)
  }

  @Test
  fun `no security state is written to the persisted bundle`() {
    val handle = SavedStateHandle()
    val viewModel = viewModel(handle)

    // The whole reason the lock state lives outside the handle: the instance-state
    // Bundle is written to disk, so an unlock flag must never reach it.
    viewModel.unlock()
    viewModel.selectTab(1)

    assertEquals(
      "only the tab index may live in the handle that reaches the disk",
      setOf("selected_tab"),
      handle.keys()
    )

    // And the same after a full serialisation round trip.
    val restored = SavedStateHandle.createHandle(handle.savedStateProvider().saveState(), null)
    assertEquals("only the tab index survives serialisation", setOf("selected_tab"), restored.keys())
  }
}
