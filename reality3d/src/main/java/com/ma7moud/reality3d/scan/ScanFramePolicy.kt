package com.ma7moud.reality3d.scan

/**
 * Live capture policy for AR scanning.
 *
 * The coach already tells the user when these conditions are bad; this policy makes those same conditions
 * actionable so warned frames do not become reconstruction input or coverage photos. The TSDF also keeps
 * its own defensive geometry/depth checks as a second line of protection.
 */
internal object ScanFramePolicy {

    fun distanceUsable(boxSize: Float, distance: Float): Boolean =
        distance in ScanCoach.nearLimit(boxSize)..ScanCoach.farLimit(boxSize)

    fun captureUsable(boxSize: Float, distance: Float, boxInView: Boolean, steady: Boolean): Boolean =
        boxInView && steady && distanceUsable(boxSize, distance)
}
