/**
 * BoxSnapping.kt
 *
 * A self-contained snapping system for box dragging and resizing in Field4.
 * When Caps Lock is enabled, this system:
 * - Aligns boxes to edges (top, bottom, left, right) and centers of other boxes
 * - Maintains even spacing between boxes (drag only)
 * - Applies edge snapping while resizing (all four edges)
 * - Draws cyan SVG guide lines showing the detected alignments
 *
 * Integrates with the existing drag/resize system by adjusting computed positions
 * (and sizes, during resize) before they are sent to the server, so the
 * round-trip that prevents visual jitter is preserved.
 *
 * Debug console output is gated behind [DEBUG_SNAPPING] (false by default);
 * nothing is logged in normal use unless the flag is flipped to true and the
 * module recompiled.
 */

package afield

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGElement
import org.w3c.dom.svg.SVGLineElement
import org.w3c.dom.svg.SVGRectElement
import org.w3c.dom.svg.SVGSVGElement
import kotlin.math.abs

private const val DEBUG_SNAPPING = false
private fun snapLog(message: String) {
    if (DEBUG_SNAPPING) console.info(message)
}

/**
 * Data class representing a potential snap target
 */
data class SnapTarget(
    val x: Double,
    val y: Double,
    val distance: Double,
    val type: SnapType,
    val referenceBox: HTMLElement? = null,  // The box being snapped to
    val snapLine: SnapLine? = null  // Information about the visual snap line
)

enum class SnapType {
    LEFT_EDGE,
    RIGHT_EDGE,
    TOP_EDGE,
    BOTTOM_EDGE,
    HORIZONTAL_SPACING,
    VERTICAL_SPACING
}

/**
 * Data class for visual snap line information
 */
data class SnapLine(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val isVertical: Boolean
)

/**
 * Main snapping system class.
 *
 * Lifecycle / integration (all call sites live in Main.kt):
 * - [initialize] is called once at startup; it installs keydown/keyup listeners
 *   that track Caps Lock via `getModifierState("CapsLock")` (also toggled on the
 *   "CapsLock" key). All snapping is gated on that [capsLockEnabled] flag.
 * - Drag: [applySnapping] is called from the drag move handler and returns the
 *   snapped (x, y); [clearSnapGuides] is called on drag end.
 * - Resize: [applySnappingForResize] is called from the resize move handler and
 *   returns a snapped [Rect] (position + size); [clearSnapGuides] is called on
 *   resize end.
 * - [activeSnapReferenceBoxes] exposes the boxes currently participating in a
 *   snap, for the renderer.
 *
 * Thresholds (fields below):
 * - [SNAP_THRESHOLD] = 10px is the base applied-snap gate. [applySnapping] commits the
 *   globally-closest target on each axis within a per-type gate ([applyThresholdFor]):
 *   edge/center snaps within 10px, even-spacing snaps within SNAP_THRESHOLD*3 (30px).
 *   [applySnappingForResize] snaps an edge within 10px.
 * - Candidate *detection* uses matching windows: edge/center candidates are collected out
 *   to SNAP_THRESHOLD*2 (20px, then gated at 10px on apply) and even-spacing candidates out
 *   to SNAP_THRESHOLD*3 (30px), which is also their apply gate — so spacing engages across
 *   its full detection window.
 * - [MIN_SPACING] = 5px is the minimum gap that qualifies for an even-spacing
 *   suggestion.
 *
 * Axis independence: horizontal and vertical snaps are computed and applied
 * independently, so a box can snap on both axes at once (drag).
 *
 * [SnapType] has no CENTER value: horizontal center-alignment targets are tagged
 * LEFT_EDGE and vertical center-alignment targets are tagged TOP_EDGE.
 *
 * Visual guides ([drawSnapGuides], [addEdgeMarkers], [addSpacingMarkers],
 * [clearSnapGuides]) render into an SVG `<g id="snap-guides">` created lazily
 * under `#overlaysvglayer` (throws via `error(...)` if that layer is missing —
 * no graceful fallback). Style: dashed lines `stroke-dasharray "4 3"`,
 * stroke-width 1.5, colour `rgba(72, 201, 255, 0.6)` (lines) / `0.8` (markers);
 * edge markers are r=3 circles; spacing markers are ticks of half-length 5px
 * (10px total span). Drag ([applySnapping]) draws at most one guide per axis
 * (the closest); resize ([applySnappingForResize]) can draw several simultaneous
 * edge guides from different boxes, since it appends every in-range edge match
 * without picking a single closest.
 *
 * Complexity: O(N log N) per move (the even-spacing search sorts the other
 * boxes by position).
 */
class BoxSnappingSystem {
    // Snap threshold in pixels
    private val SNAP_THRESHOLD = 10.0
    
    // Minimum spacing to consider for even spacing detection
    private val MIN_SPACING = 5.0

    /**
     * Per-type distance gate for actually engaging a snap. Even-spacing candidates are searched out to
     * [SNAP_THRESHOLD]*3, so they engage within that window; edge/center snaps engage within [SNAP_THRESHOLD].
     * (Previously every type was gated at [SNAP_THRESHOLD], making the wider even-spacing search dead.)
     */
    private fun applyThresholdFor(type: SnapType): Double = when (type) {
        SnapType.HORIZONTAL_SPACING, SnapType.VERTICAL_SPACING -> SNAP_THRESHOLD * 3
        else -> SNAP_THRESHOLD
    }
    
    // Track Caps Lock state
    private var capsLockEnabled = false
    
    // SVG group for snap guides
    private var snapGuidesGroup: SVGElement? = null
    
    // Track active snaps for visual feedback
    private var activeSnapTargets = mutableListOf<SnapTarget>()
    
    /**
     * Initialize the snapping system by setting up Caps Lock detection
     */
    fun initialize() {
        // Listen for keyboard events to track Caps Lock state
        document.addEventListener("keydown", { event ->
            val e = event as KeyboardEvent
            if (e.key == "CapsLock") {
                // Toggle Caps Lock state
                capsLockEnabled = !capsLockEnabled
            }
            // Also check getModifierState for more reliable detection
            val newState = e.getModifierState("CapsLock")
            if (newState != capsLockEnabled) {
                capsLockEnabled = newState
                // Notify user about snapping state change
                if (capsLockEnabled) {
                    snapLog("Box snapping: ENABLED (Caps Lock ON)")
                } else {
                    snapLog("Box snapping: DISABLED (Caps Lock OFF)")
                }
            }
        })
        
        document.addEventListener("keyup", { event ->
            val e = event as KeyboardEvent
            val newState = e.getModifierState("CapsLock")
            if (newState != capsLockEnabled) {
                capsLockEnabled = newState
                // Notify user about snapping state change
                if (capsLockEnabled) {
                    snapLog("Box snapping: ENABLED (Caps Lock ON)")
                } else {
                    snapLog("Box snapping: DISABLED (Caps Lock OFF)")
                }
            }
        })
        
        // Check on any mouse event as well
        document.addEventListener("mousemove", { event ->
            val e = event as MouseEvent
            // We can't directly check Caps Lock from MouseEvent in a reliable way
            // but KeyboardEvent handlers above should keep state updated
        })
    }
    
    /**
     * Check if Caps Lock is currently enabled
     */
    fun isCapsLockEnabled(): Boolean = capsLockEnabled
    
    /**
     * Get or create the SVG group for snap guides
     */
    private fun getSnapGuidesGroup(): SVGElement {
        if (snapGuidesGroup == null) {
            val svg = document.getElementById("overlaysvglayer") as? SVGSVGElement
            if (svg != null) {
                // Try to find existing group
                var group = svg.querySelector("#snap-guides") as? SVGElement
                if (group == null) {
                    // Create new group
                    group = document.createElementNS("http://www.w3.org/2000/svg", "g") as SVGElement
                    group.setAttribute("id", "snap-guides")
                    svg.appendChild(group)
                }
                snapGuidesGroup = group
            }
        }
        return snapGuidesGroup ?: error("Could not create snap guides group")
    }
    
    /**
     * Draw visual snap guides for the active snap targets
     */
    private fun drawSnapGuides(targets: List<SnapTarget>, draggedBox: Rect) {
        val group = getSnapGuidesGroup()
        
        // Clear previous guides
        group.innerHTML = ""
        
        // Store active targets for reference
        activeSnapTargets.clear()
        activeSnapTargets.addAll(targets)
        
        targets.forEach { target ->
            target.snapLine?.let { line ->
                // Create line element
                val lineElement = document.createElementNS("http://www.w3.org/2000/svg", "line") as SVGLineElement
                lineElement.setAttribute("x1", line.x1.toString())
                lineElement.setAttribute("y1", line.y1.toString())
                lineElement.setAttribute("x2", line.x2.toString())
                lineElement.setAttribute("y2", line.y2.toString())
                lineElement.setAttribute("stroke", "rgba(72, 201, 255, 0.6)")  // Subtle cyan
                lineElement.setAttribute("stroke-width", "1.5")
                lineElement.setAttribute("stroke-dasharray", "4 3")
                lineElement.setAttribute("pointer-events", "none")
                group.appendChild(lineElement)
                
                // Add small markers at key points
                when (target.type) {
                    SnapType.HORIZONTAL_SPACING, SnapType.VERTICAL_SPACING -> {
                        // For spacing, draw measurement markers
                        addSpacingMarkers(group, line, target.type)
                    }
                    else -> {
                        // For edge alignment, draw subtle markers at snap points
                        addEdgeMarkers(group, line, line.isVertical)
                    }
                }
            }
        }
    }
    
    /**
     * Add edge alignment markers
     */
    private fun addEdgeMarkers(group: SVGElement, line: SnapLine, isVertical: Boolean) {
        // Small circle markers at the line endpoints
        listOf(line.x1 to line.y1, line.x2 to line.y2).forEach { (x, y) ->
            val circle = document.createElementNS("http://www.w3.org/2000/svg", "circle")
            circle.setAttribute("cx", x.toString())
            circle.setAttribute("cy", y.toString())
            circle.setAttribute("r", "3")
            circle.setAttribute("fill", "rgba(72, 201, 255, 0.4)")
            circle.setAttribute("stroke", "rgba(72, 201, 255, 0.8)")
            circle.setAttribute("stroke-width", "1")
            circle.setAttribute("pointer-events", "none")
            group.appendChild(circle)
        }
    }
    
    /**
     * Add spacing measurement markers
     */
    private fun addSpacingMarkers(group: SVGElement, line: SnapLine, type: SnapType) {
        // Draw measurement ticks at endpoints
        val tickSize = 5.0
        
        if (type == SnapType.HORIZONTAL_SPACING) {
            // Vertical ticks for horizontal spacing
            listOf(line.x1, line.x2).forEach { x ->
                val tick = document.createElementNS("http://www.w3.org/2000/svg", "line")
                tick.setAttribute("x1", x.toString())
                tick.setAttribute("y1", (line.y1 - tickSize).toString())
                tick.setAttribute("x2", x.toString())
                tick.setAttribute("y2", (line.y1 + tickSize).toString())
                tick.setAttribute("stroke", "rgba(72, 201, 255, 0.8)")
                tick.setAttribute("stroke-width", "1.5")
                tick.setAttribute("pointer-events", "none")
                group.appendChild(tick)
            }
        } else if (type == SnapType.VERTICAL_SPACING) {
            // Horizontal ticks for vertical spacing
            listOf(line.y1, line.y2).forEach { y ->
                val tick = document.createElementNS("http://www.w3.org/2000/svg", "line")
                tick.setAttribute("x1", (line.x1 - tickSize).toString())
                tick.setAttribute("y1", y.toString())
                tick.setAttribute("x2", (line.x1 + tickSize).toString())
                tick.setAttribute("y2", y.toString())
                tick.setAttribute("stroke", "rgba(72, 201, 255, 0.8)")
                tick.setAttribute("stroke-width", "1.5")
                tick.setAttribute("pointer-events", "none")
                group.appendChild(tick)
            }
        }
    }
    
    /**
     * Clear visual snap guides
     */
    fun clearSnapGuides() {
        snapGuidesGroup?.let { it.innerHTML = "" }
        activeSnapTargets.clear()
    }

    fun activeSnapReferenceBoxes(): List<HTMLElement> =
        activeSnapTargets.mapNotNull { it.referenceBox }.distinct()
    
    /**
     * Apply snapping to a proposed position while dragging a box
     * 
     * @param draggedBox The box being dragged
     * @param proposedX The proposed X position
     * @param proposedY The proposed Y position
     * @param proposedW The width of the box
     * @param proposedH The height of the box
     * @return Pair of snapped (x, y) coordinates
     */
    fun applySnapping(
        draggedBox: HTMLElement,
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double
    ): Pair<Double, Double> {
        if (!capsLockEnabled) {
            clearSnapGuides()
            return proposedX to proposedY
        }
        
        // Get all other boxes
        val allBoxes = getAllBoxes().filter { it != draggedBox }
        
        if (allBoxes.isEmpty()) {
            clearSnapGuides()
            return proposedX to proposedY
        }
        
        // Find snap targets for horizontal and vertical axes independently
        val horizontalSnaps = findHorizontalSnapTargets(proposedX, proposedY, proposedW, proposedH, allBoxes)
        val verticalSnaps = findVerticalSnapTargets(proposedX, proposedY, proposedW, proposedH, allBoxes)
        
        // Find the best snap for each axis
        val bestHorizontalSnap = horizontalSnaps.minByOrNull { it.distance }
        val bestVerticalSnap = verticalSnaps.minByOrNull { it.distance }
        
        var snappedX = proposedX
        var snappedY = proposedY
        var snapped = false
        
        val activeTargets = mutableListOf<SnapTarget>()
        
        // Apply horizontal snap if within threshold
        if (bestHorizontalSnap != null && bestHorizontalSnap.distance <= applyThresholdFor(bestHorizontalSnap.type)) {
            snappedX = bestHorizontalSnap.x
            snapped = true
            activeTargets.add(bestHorizontalSnap)
            snapLog("Snapping horizontally: ${bestHorizontalSnap.type} (distance: ${bestHorizontalSnap.distance.toInt()}px)")
        }
        
        // Apply vertical snap if within threshold
        if (bestVerticalSnap != null && bestVerticalSnap.distance <= applyThresholdFor(bestVerticalSnap.type)) {
            snappedY = bestVerticalSnap.y
            snapped = true
            activeTargets.add(bestVerticalSnap)
            snapLog("Snapping vertically: ${bestVerticalSnap.type} (distance: ${bestVerticalSnap.distance.toInt()}px)")
        }
        
        if (snapped) {
            snapLog("Caps Lock snapping: ($proposedX, $proposedY) → ($snappedX, $snappedY)")
            // Draw visual guides for active snaps
            drawSnapGuides(activeTargets, Rect(snappedX, snappedY, proposedW, proposedH))
        } else {
            clearSnapGuides()
        }
        
        return snappedX to snappedY
    }
    
    /**
     * Apply snapping to a proposed size/position while resizing a box
     * 
     * @param resizedBox The box being resized
     * @param proposedX The proposed X position
     * @param proposedY The proposed Y position
     * @param proposedW The proposed width
     * @param proposedH The proposed height
     * @return Rect with snapped position and size
     */
    fun applySnappingForResize(
        resizedBox: HTMLElement,
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double
    ): Rect {
        if (!capsLockEnabled) {
            clearSnapGuides()
            return Rect(proposedX, proposedY, proposedW, proposedH)
        }
        
        // Get all other boxes
        val allBoxes = getAllBoxes().filter { it != resizedBox }
        
        if (allBoxes.isEmpty()) {
            clearSnapGuides()
            return Rect(proposedX, proposedY, proposedW, proposedH)
        }
        
        var snappedX = proposedX
        var snappedY = proposedY
        var snappedW = proposedW
        var snappedH = proposedH
        
        val activeTargets = mutableListOf<SnapTarget>()
        
        // Check edges for snapping
        val proposedLeft = proposedX
        val proposedRight = proposedX + proposedW
        val proposedTop = proposedY
        val proposedBottom = proposedY + proposedH
        
        // Find potential snap points for each edge
        allBoxes.forEach { box ->
            val boxData = getBoxBounds(box)
            val boxLeft = boxData.x
            val boxRight = boxData.x + boxData.w
            val boxTop = boxData.y
            val boxBottom = boxData.y + boxData.h
            
            // Snap right edge
            val rightToRightDist = abs(proposedRight - boxRight)
            if (rightToRightDist < SNAP_THRESHOLD) {
                val lineTop = minOf(proposedTop, boxTop)
                val lineBottom = maxOf(proposedBottom, boxBottom)
                val snapLine = SnapLine(boxRight, lineTop, boxRight, lineBottom, isVertical = true)
                activeTargets.add(SnapTarget(
                    proposedX, proposedY, rightToRightDist, 
                    SnapType.RIGHT_EDGE, box, snapLine
                ))
                snappedW = boxRight - proposedX
                snapLog("Resize snap: right edge to right edge (${rightToRightDist.toInt()}px)")
            }
            
            val rightToLeftDist = abs(proposedRight - boxLeft)
            if (rightToLeftDist < SNAP_THRESHOLD) {
                val lineTop = minOf(proposedTop, boxTop)
                val lineBottom = maxOf(proposedBottom, boxBottom)
                val snapLine = SnapLine(boxLeft, lineTop, boxLeft, lineBottom, isVertical = true)
                activeTargets.add(SnapTarget(
                    proposedX, proposedY, rightToLeftDist,
                    SnapType.RIGHT_EDGE, box, snapLine
                ))
                snappedW = boxLeft - proposedX
                snapLog("Resize snap: right edge to left edge (${rightToLeftDist.toInt()}px)")
            }
            
            // Snap left edge
            val leftToLeftDist = abs(proposedLeft - boxLeft)
            if (leftToLeftDist < SNAP_THRESHOLD) {
                val lineTop = minOf(proposedTop, boxTop)
                val lineBottom = maxOf(proposedBottom, boxBottom)
                val snapLine = SnapLine(boxLeft, lineTop, boxLeft, lineBottom, isVertical = true)
                activeTargets.add(SnapTarget(
                    boxLeft, proposedY, leftToLeftDist,
                    SnapType.LEFT_EDGE, box, snapLine
                ))
                snappedW = proposedW + (proposedX - boxLeft)
                snappedX = boxLeft
                snapLog("Resize snap: left edge to left edge (${leftToLeftDist.toInt()}px)")
            }
            
            val leftToRightDist = abs(proposedLeft - boxRight)
            if (leftToRightDist < SNAP_THRESHOLD) {
                val lineTop = minOf(proposedTop, boxTop)
                val lineBottom = maxOf(proposedBottom, boxBottom)
                val snapLine = SnapLine(boxRight, lineTop, boxRight, lineBottom, isVertical = true)
                activeTargets.add(SnapTarget(
                    boxRight, proposedY, leftToRightDist,
                    SnapType.LEFT_EDGE, box, snapLine
                ))
                snappedW = proposedW + (proposedX - boxRight)
                snappedX = boxRight
                snapLog("Resize snap: left edge to right edge (${leftToRightDist.toInt()}px)")
            }
            
            // Snap bottom edge
            val bottomToBottomDist = abs(proposedBottom - boxBottom)
            if (bottomToBottomDist < SNAP_THRESHOLD) {
                val lineLeft = minOf(proposedLeft, boxLeft)
                val lineRight = maxOf(proposedRight, boxRight)
                val snapLine = SnapLine(lineLeft, boxBottom, lineRight, boxBottom, isVertical = false)
                activeTargets.add(SnapTarget(
                    proposedX, proposedY, bottomToBottomDist,
                    SnapType.BOTTOM_EDGE, box, snapLine
                ))
                snappedH = boxBottom - proposedY
                snapLog("Resize snap: bottom edge to bottom edge (${bottomToBottomDist.toInt()}px)")
            }
            
            val bottomToTopDist = abs(proposedBottom - boxTop)
            if (bottomToTopDist < SNAP_THRESHOLD) {
                val lineLeft = minOf(proposedLeft, boxLeft)
                val lineRight = maxOf(proposedRight, boxRight)
                val snapLine = SnapLine(lineLeft, boxTop, lineRight, boxTop, isVertical = false)
                activeTargets.add(SnapTarget(
                    proposedX, proposedY, bottomToTopDist,
                    SnapType.BOTTOM_EDGE, box, snapLine
                ))
                snappedH = boxTop - proposedY
                snapLog("Resize snap: bottom edge to top edge (${bottomToTopDist.toInt()}px)")
            }
            
            // Snap top edge
            val topToTopDist = abs(proposedTop - boxTop)
            if (topToTopDist < SNAP_THRESHOLD) {
                val lineLeft = minOf(proposedLeft, boxLeft)
                val lineRight = maxOf(proposedRight, boxRight)
                val snapLine = SnapLine(lineLeft, boxTop, lineRight, boxTop, isVertical = false)
                activeTargets.add(SnapTarget(
                    proposedX, boxTop, topToTopDist,
                    SnapType.TOP_EDGE, box, snapLine
                ))
                snappedH = proposedH + (proposedY - boxTop)
                snappedY = boxTop
                snapLog("Resize snap: top edge to top edge (${topToTopDist.toInt()}px)")
            }
            
            val topToBottomDist = abs(proposedTop - boxBottom)
            if (topToBottomDist < SNAP_THRESHOLD) {
                val lineLeft = minOf(proposedLeft, boxLeft)
                val lineRight = maxOf(proposedRight, boxRight)
                val snapLine = SnapLine(lineLeft, boxBottom, lineRight, boxBottom, isVertical = false)
                activeTargets.add(SnapTarget(
                    proposedX, boxBottom, topToBottomDist,
                    SnapType.TOP_EDGE, box, snapLine
                ))
                snappedH = proposedH + (proposedY - boxBottom)
                snappedY = boxBottom
                snapLog("Resize snap: top edge to bottom edge (${topToBottomDist.toInt()}px)")
            }
        }
        
        if (activeTargets.isNotEmpty()) {
            snapLog("Caps Lock resize snapping: ($proposedX, $proposedY, $proposedW, $proposedH) → ($snappedX, $snappedY, $snappedW, $snappedH)")
            drawSnapGuides(activeTargets, Rect(snappedX, snappedY, snappedW, snappedH))
        } else {
            clearSnapGuides()
        }
        
        return Rect(snappedX, snappedY, snappedW, snappedH)
    }
    
    /**
     * Find horizontal snap targets (left/right edge alignment and horizontal spacing)
     */
    private fun findHorizontalSnapTargets(
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double,
        otherBoxes: List<HTMLElement>
    ): List<SnapTarget> {
        val targets = mutableListOf<SnapTarget>()
        
        val proposedLeft = proposedX
        val proposedRight = proposedX + proposedW
        val proposedCenterX = proposedX + proposedW / 2
        val proposedTop = proposedY
        val proposedBottom = proposedY + proposedH
        
        for (box in otherBoxes) {
            val boxData = getBoxBounds(box)
            val boxLeft = boxData.x
            val boxRight = boxData.x + boxData.w
            val boxCenterX = boxData.x + boxData.w / 2
            val boxTop = boxData.y
            val boxBottom = boxData.y + boxData.h
            
            // Calculate visual line extent (span across both boxes vertically)
            val lineTop = minOf(proposedTop, boxTop)
            val lineBottom = maxOf(proposedBottom, boxBottom)
            
            // Left edge alignment
            val leftToLeftDistance = abs(proposedLeft - boxLeft)
            if (leftToLeftDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(boxLeft, lineTop, boxLeft, lineBottom, isVertical = true)
                targets.add(SnapTarget(boxLeft, proposedY, leftToLeftDistance, SnapType.LEFT_EDGE, box, snapLine))
            }
            
            val leftToRightDistance = abs(proposedLeft - boxRight)
            if (leftToRightDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(boxRight, lineTop, boxRight, lineBottom, isVertical = true)
                targets.add(SnapTarget(boxRight, proposedY, leftToRightDistance, SnapType.LEFT_EDGE, box, snapLine))
            }
            
            // Right edge alignment
            val rightToRightDistance = abs(proposedRight - boxRight)
            if (rightToRightDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(boxRight, lineTop, boxRight, lineBottom, isVertical = true)
                targets.add(SnapTarget(boxRight - proposedW, proposedY, rightToRightDistance, SnapType.RIGHT_EDGE, box, snapLine))
            }
            
            val rightToLeftDistance = abs(proposedRight - boxLeft)
            if (rightToLeftDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(boxLeft, lineTop, boxLeft, lineBottom, isVertical = true)
                targets.add(SnapTarget(boxLeft - proposedW, proposedY, rightToLeftDistance, SnapType.RIGHT_EDGE, box, snapLine))
            }
            
            // Center alignment
            val centerDistance = abs(proposedCenterX - boxCenterX)
            if (centerDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(boxCenterX, lineTop, boxCenterX, lineBottom, isVertical = true)
                targets.add(SnapTarget(boxCenterX - proposedW / 2, proposedY, centerDistance, SnapType.LEFT_EDGE, box, snapLine))
            }
        }
        
        // Check for even spacing (more complex, requires analyzing multiple boxes)
        val spacingTargets = findEvenHorizontalSpacing(proposedX, proposedY, proposedW, proposedH, otherBoxes)
        targets.addAll(spacingTargets)
        
        return targets
    }
    
    /**
     * Find vertical snap targets (top/bottom edge alignment and vertical spacing)
     */
    private fun findVerticalSnapTargets(
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double,
        otherBoxes: List<HTMLElement>
    ): List<SnapTarget> {
        val targets = mutableListOf<SnapTarget>()
        
        val proposedTop = proposedY
        val proposedBottom = proposedY + proposedH
        val proposedCenterY = proposedY + proposedH / 2
        val proposedLeft = proposedX
        val proposedRight = proposedX + proposedW
        
        for (box in otherBoxes) {
            val boxData = getBoxBounds(box)
            val boxTop = boxData.y
            val boxBottom = boxData.y + boxData.h
            val boxCenterY = boxData.y + boxData.h / 2
            val boxLeft = boxData.x
            val boxRight = boxData.x + boxData.w
            
            // Calculate visual line extent (span across both boxes horizontally)
            val lineLeft = minOf(proposedLeft, boxLeft)
            val lineRight = maxOf(proposedRight, boxRight)
            
            // Top edge alignment
            val topToTopDistance = abs(proposedTop - boxTop)
            if (topToTopDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(lineLeft, boxTop, lineRight, boxTop, isVertical = false)
                targets.add(SnapTarget(proposedX, boxTop, topToTopDistance, SnapType.TOP_EDGE, box, snapLine))
            }
            
            val topToBottomDistance = abs(proposedTop - boxBottom)
            if (topToBottomDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(lineLeft, boxBottom, lineRight, boxBottom, isVertical = false)
                targets.add(SnapTarget(proposedX, boxBottom, topToBottomDistance, SnapType.TOP_EDGE, box, snapLine))
            }
            
            // Bottom edge alignment
            val bottomToBottomDistance = abs(proposedBottom - boxBottom)
            if (bottomToBottomDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(lineLeft, boxBottom, lineRight, boxBottom, isVertical = false)
                targets.add(SnapTarget(proposedX, boxBottom - proposedH, bottomToBottomDistance, SnapType.BOTTOM_EDGE, box, snapLine))
            }
            
            val bottomToTopDistance = abs(proposedBottom - boxTop)
            if (bottomToTopDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(lineLeft, boxTop, lineRight, boxTop, isVertical = false)
                targets.add(SnapTarget(proposedX, boxTop - proposedH, bottomToTopDistance, SnapType.BOTTOM_EDGE, box, snapLine))
            }
            
            // Center alignment
            val centerDistance = abs(proposedCenterY - boxCenterY)
            if (centerDistance < SNAP_THRESHOLD * 2) {
                val snapLine = SnapLine(lineLeft, boxCenterY, lineRight, boxCenterY, isVertical = false)
                targets.add(SnapTarget(proposedX, boxCenterY - proposedH / 2, centerDistance, SnapType.TOP_EDGE, box, snapLine))
            }
        }
        
        // Check for even spacing
        val spacingTargets = findEvenVerticalSpacing(proposedX, proposedY, proposedW, proposedH, otherBoxes)
        targets.addAll(spacingTargets)
        
        return targets
    }
    
    /**
     * Find even horizontal spacing targets
     * Looks for situations where the dragged box can maintain equal spacing with nearby boxes
     */
    private fun findEvenHorizontalSpacing(
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double,
        otherBoxes: List<HTMLElement>
    ): List<SnapTarget> {
        val targets = mutableListOf<SnapTarget>()
        
        // Get boxes sorted by X position
        val sortedBoxes = otherBoxes.map { getBoxBounds(it) to it }
            .sortedBy { it.first.x }
        
        // Look for pairs of boxes where we could fit in between with equal spacing
        for (i in 0 until sortedBoxes.size - 1) {
            val leftBox = sortedBoxes[i].first
            val rightBox = sortedBoxes[i + 1].first
            val leftBoxElement = sortedBoxes[i].second
            
            // Check if proposed box is roughly between these two
            val leftBoxRight = leftBox.x + leftBox.w
            val rightBoxLeft = rightBox.x
            
            if (proposedX + proposedW / 2 > leftBoxRight && proposedX + proposedW / 2 < rightBoxLeft) {
                // Calculate position for even spacing
                val totalGap = rightBoxLeft - leftBoxRight
                val evenGap = (totalGap - proposedW) / 2.0
                
                if (evenGap >= MIN_SPACING) {
                    val evenX = leftBoxRight + evenGap
                    val distance = abs(proposedX - evenX)
                    
                    if (distance < SNAP_THRESHOLD * 3) {
                        // Draw spacing measurement line
                        val centerY = (maxOf(leftBox.y, rightBox.y, proposedY) + 
                                       minOf(leftBox.y + leftBox.h, rightBox.y + rightBox.h, proposedY + proposedH)) / 2
                        val snapLine = SnapLine(leftBoxRight, centerY, rightBoxLeft, centerY, isVertical = false)
                        targets.add(SnapTarget(evenX, proposedY, distance, SnapType.HORIZONTAL_SPACING, leftBoxElement, snapLine))
                    }
                }
            }
        }
        
        return targets
    }
    
    /**
     * Find even vertical spacing targets
     * Looks for situations where the dragged box can maintain equal spacing with nearby boxes
     */
    private fun findEvenVerticalSpacing(
        proposedX: Double,
        proposedY: Double,
        proposedW: Double,
        proposedH: Double,
        otherBoxes: List<HTMLElement>
    ): List<SnapTarget> {
        val targets = mutableListOf<SnapTarget>()
        
        // Get boxes sorted by Y position
        val sortedBoxes = otherBoxes.map { getBoxBounds(it) to it }
            .sortedBy { it.first.y }
        
        // Look for pairs of boxes where we could fit in between with equal spacing
        for (i in 0 until sortedBoxes.size - 1) {
            val topBox = sortedBoxes[i].first
            val bottomBox = sortedBoxes[i + 1].first
            val topBoxElement = sortedBoxes[i].second
            
            // Check if proposed box is roughly between these two
            val topBoxBottom = topBox.y + topBox.h
            val bottomBoxTop = bottomBox.y
            
            if (proposedY + proposedH / 2 > topBoxBottom && proposedY + proposedH / 2 < bottomBoxTop) {
                // Calculate position for even spacing
                val totalGap = bottomBoxTop - topBoxBottom
                val evenGap = (totalGap - proposedH) / 2.0
                
                if (evenGap >= MIN_SPACING) {
                    val evenY = topBoxBottom + evenGap
                    val distance = abs(proposedY - evenY)
                    
                    if (distance < SNAP_THRESHOLD * 3) {
                        // Draw spacing measurement line
                        val centerX = (maxOf(topBox.x, bottomBox.x, proposedX) + 
                                       minOf(topBox.x + topBox.w, bottomBox.x + bottomBox.w, proposedX + proposedW)) / 2
                        val snapLine = SnapLine(centerX, topBoxBottom, centerX, bottomBoxTop, isVertical = true)
                        targets.add(SnapTarget(proposedX, evenY, distance, SnapType.VERTICAL_SPACING, topBoxElement, snapLine))
                    }
                }
            }
        }
        
        return targets
    }
    
    /**
     * Get all boxes in the document
     */
    private fun getAllBoxes(): List<HTMLElement> {
        val boxElements = document.getElementsByClassName("box")
        return (0 until boxElements.length)
            .mapNotNull { boxElements.item(it) as? HTMLElement }
            .filter { it.unsafeCast<dynamic>().uid != undefined && hasRenderableFrame(it) }
    }
    
    /**
     * Get bounds of a box from its data properties
     */
    private fun getBoxBounds(box: HTMLElement): Rect {
        val d = box.unsafeCast<dynamic>()
        return Rect(
            x = (d.data_x as? Double) ?: 0.0,
            y = (d.data_y as? Double) ?: 0.0,
            w = (d.data_w as? Double) ?: 0.0,
            h = (d.data_h as? Double) ?: 0.0
        )
    }
}

// Global instance of the snapping system
val boxSnappingSystem = BoxSnappingSystem()
