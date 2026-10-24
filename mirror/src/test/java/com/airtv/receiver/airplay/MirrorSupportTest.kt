// SPDX-License-Identifier: GPL-3.0-only
package com.airtv.receiver.airplay
import org.junit.Assert.*
import org.junit.Test
class MirrorSupportTest {
    @Test fun androidSixKeepsTheNativeEngineDisabled() {
        assertFalse(MirrorSupport.isSupported(23, arrayOf("arm64-v8a", "armeabi-v7a")))
    }
    @Test fun supportedAndroidAndArmArchitecturesAreAllowed() {
        assertTrue(MirrorSupport.isSupported(24, arrayOf("armeabi-v7a")))
        assertTrue(MirrorSupport.isSupported(31, arrayOf("arm64-v8a")))
    }
    @Test fun missingOrUnsupportedArchitecturesDoNotLoadTheEngine() {
        assertFalse(MirrorSupport.isSupported(35, emptyArray()))
        assertFalse(MirrorSupport.isSupported(35, arrayOf("x86_64", "x86")))
    }
}
