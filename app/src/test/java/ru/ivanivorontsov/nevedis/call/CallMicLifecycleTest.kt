package ru.ivanivorontsov.nevedis.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.ivanivorontsov.nevedis.call.CallMicLifecycle.CallState
import ru.ivanivorontsov.nevedis.call.CallMicLifecycle.Command

class CallMicLifecycleTest {
    private fun CallMicLifecycle.go(s: CallState, enabled: Boolean = true, granted: Boolean = false, auto: Boolean = false, revoke: Boolean = true) =
        onCallState(s, enabled, granted, auto, revoke)

    @Test fun micNeverOpensWhileRingingOrIdle() {
        val l = CallMicLifecycle()
        assertEquals(Command.None, l.go(CallState.RINGING, granted = true, auto = true))
        assertFalse(l.canOpenMic)
        assertEquals(Command.None, l.onUserConsent(true))
    }

    @Test fun askUserWhenCallStartsWithoutPermission() {
        val l = CallMicLifecycle()
        l.go(CallState.RINGING)
        assertEquals(Command.AskUser, l.go(CallState.OFFHOOK))
        assertTrue(l.canOpenMic)
        assertEquals(Command.StartListening, l.onUserConsent(true))
        assertTrue(l.listening)
    }

    @Test fun autoStartOnlyWhenGrantedAndChosen() {
        val l = CallMicLifecycle()
        assertEquals(Command.StartListening, l.go(CallState.OFFHOOK, granted = true, auto = true))
        val l2 = CallMicLifecycle()
        assertEquals(Command.AskUser, l2.go(CallState.OFFHOOK, granted = true, auto = false))
    }

    @Test fun stopsImmediatelyOnIdleAndRevokes() {
        val l = CallMicLifecycle()
        l.go(CallState.OFFHOOK, granted = true, auto = true)
        assertEquals(Command.StopListening(revokePermission = true), l.go(CallState.IDLE))
        assertFalse(l.listening)
        assertFalse(l.canOpenMic)
    }

    @Test fun noRevokeWhenUserDisabledIt() {
        val l = CallMicLifecycle()
        l.go(CallState.OFFHOOK, granted = true, auto = true)
        assertEquals(Command.StopListening(revokePermission = false), l.go(CallState.IDLE, revoke = false))
    }

    @Test fun featureDisabledDoesNothing() {
        val l = CallMicLifecycle()
        assertEquals(Command.None, l.go(CallState.OFFHOOK, enabled = false, granted = true, auto = true))
        assertFalse(l.listening)
    }

    @Test fun consentAfterCallEndedIsIgnored() {
        val l = CallMicLifecycle()
        l.go(CallState.OFFHOOK)
        l.go(CallState.IDLE)
        assertEquals(Command.None, l.onUserConsent(true))
        assertFalse(l.listening)
    }

    @Test fun deniedConsentDoesNotStart() {
        val l = CallMicLifecycle()
        l.go(CallState.OFFHOOK)
        assertEquals(Command.None, l.onUserConsent(false))
        assertFalse(l.listening)
    }

    @Test fun askOnlyOncePerCall() {
        val l = CallMicLifecycle()
        assertEquals(Command.AskUser, l.go(CallState.OFFHOOK))
        assertEquals(Command.None, l.go(CallState.OFFHOOK))
        l.go(CallState.IDLE)
        assertEquals(Command.AskUser, l.go(CallState.OFFHOOK))
    }

    @Test fun telephonyExtras() {
        assertEquals(CallState.OFFHOOK, CallMicLifecycle.fromTelephonyExtra("OFFHOOK"))
        assertEquals(null, CallMicLifecycle.fromTelephonyExtra("???"))
    }
}
