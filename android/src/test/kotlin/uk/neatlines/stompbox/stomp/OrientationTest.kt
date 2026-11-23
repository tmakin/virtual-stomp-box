package uk.neatlines.stompbox.stomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationTest {
    @Test fun findsAngleConvention() {
        val truth = AngleConvention(-1, 1)
        val g = truth.gravity(20.0, 35.0)
        val (fit, err) = AngleConvention.fit(g, 20.0, 35.0)
        assertEquals(truth, fit); assertTrue(err < 0.01)
    }

    @Test fun tiltIsSignedTowardsTiltPose() {
        val c = AngleConvention()
        val p = Placement.calibrate(c.gravity(0.0, 0.0), 0.0, 0.0, tiltAcc = c.gravity(0.0, 15.0))
        assertEquals(12.0, p.tiltDeg(c.gravity(0.0, 12.0)), 1e-6)
        assertEquals(-10.0, p.tiltDeg(c.gravity(0.0, -10.0)), 1e-6)
        assertEquals(Mode.SHOE, p.suggestedMode)
    }

    @Test fun tiltWorksWhenSensorIsTurnedOnTheStrap() {
        // Sensor mounted on its side and rotated: the axis comes from calibration, not assumptions.
        val c = AngleConvention()
        val rest = c.gravity(70.0, 10.0)
        val p = Placement.calibrate(rest, 70.0, 10.0, tiltAcc = c.gravity(70.0, 25.0))
        assertTrue(p.tiltDeg(c.gravity(70.0, 22.0)) > 10)
        assertTrue(p.tiltDeg(c.gravity(70.0, -2.0)) < -10)
        assertEquals(Mode.LEG, p.suggestedMode)
    }

    @Test fun noAxisWhenTiltPoseDidNotMove() {
        val g = AngleConvention().gravity(0.0, 0.0)
        assertNull(Placement.calibrate(g, 0.0, 0.0, tiltAcc = AngleConvention().gravity(0.0, 2.0)).tiltAxis)
    }
}
