package uk.hairyfred.libreshock.ui

import com.journeyapps.barcodescanner.CaptureActivity

/**
 *  zxing-android-embedded's default CaptureActivity is registered with
 *  `screenOrientation="sensorLandscape"`, which forces the phone into
 *  landscape regardless of the calling app's orientation. We re-register
 *  this subclass in our manifest with `screenOrientation="fullSensor"`
 *  so the scanner follows the device's current orientation instead.
 */
class PortraitCaptureActivity : CaptureActivity()
