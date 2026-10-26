package com.android.mysystemapp.feature.vehiclecontrol

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VehicleGroupTransitionTest {
    @Test fun selectionPrecedesContentAndNavigationStaysLockedUntilPresentation() =
        runTest {
            for (destination in ControlGroup.entries) {
                val transition = VehicleGroupTransition()
                val frames = Channel<Unit>(Channel.UNLIMITED)
                val changes = mutableListOf<ControlGroup>()
                transition.request(destination)
                assertEquals(destination, transition.target)
                val job =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        transition.complete(awaitFrame = { frames.receive() }, changeGroup = changes::add)
                    }
                repeat(4) { index ->
                    assertTrue(transition.isLoading)
                    ControlGroup.entries.forEach(transition::request)
                    assertEquals("Repeated input must not replace the pending destination", destination, transition.target)
                    assertEquals(if (index < 2) emptyList() else listOf(destination), changes)
                    frames.send(Unit)
                    runCurrent()
                }
                job.join()
                assertFalse(transition.isLoading)
                assertEquals(listOf(destination), changes)
                transition.request(ControlGroup.MODES)
                assertEquals(ControlGroup.MODES, transition.target)
            }
        }

    @Test fun leavingTheScreenCancelsPendingNavigationAndUnlocksTheRail() =
        runTest {
            val transition = VehicleGroupTransition()
            transition.request(ControlGroup.SEATS)
            val changes = mutableListOf<ControlGroup>()
            val frames = Channel<Unit>()
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    transition.complete(awaitFrame = { frames.receive() }, changeGroup = changes::add)
                }
            job.cancel()
            job.join()
            assertFalse(transition.isLoading)
            assertTrue(changes.isEmpty())
        }
}
