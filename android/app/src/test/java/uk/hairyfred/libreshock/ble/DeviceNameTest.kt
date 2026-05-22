package uk.hairyfred.libreshock.ble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies [ShockDevice.isDeviceName] matches both vendor naming schemes
 *  (`Pavlok-3-XXXX` and `Pav4-8cbf`) without false-positiving on unrelated
 *  devices that merely contain "Pav". */
class DeviceNameTest {

    @Test
    fun matchesPavlokLongScheme() {
        assertTrue(ShockDevice.isDeviceName("Pavlok-3-A1B2"))
        assertTrue(ShockDevice.isDeviceName("Pavlok-S-0000"))
        assertTrue(ShockDevice.isDeviceName("pavlok-3-lower"))  // case-insensitive
    }

    @Test
    fun matchesShortPavScheme() {
        assertTrue(ShockDevice.isDeviceName("Pav4-8cbf"))
        assertTrue(ShockDevice.isDeviceName("Pav3-0001"))
        assertTrue(ShockDevice.isDeviceName("Pav4"))           // no id suffix
    }

    @Test
    fun rejectsUnrelatedNames() {
        assertFalse(ShockDevice.isDeviceName(null))
        assertFalse(ShockDevice.isDeviceName(""))
        assertFalse(ShockDevice.isDeviceName("MyPhone"))
        assertFalse(ShockDevice.isDeviceName("Paving Stone"))  // "Pav" + "i" — not lok/digit
        assertFalse(ShockDevice.isDeviceName("Pav"))           // nothing after "Pav"
        assertFalse(ShockDevice.isDeviceName("Pav-x"))         // "-" is not lok/digit
    }
}
