/**
 * BoxSnapping.kt
 * 
 * A self-contained snapping system for box dragging in Field4.
 * When Caps Lock is enabled during dragging, this system:
 * - Aligns boxes to edges (top, bottom, left, right) of other boxes
 * - Maintains even spacing between boxes
 * 
 * Integrates with the existing drag system by adjusting computed positions
 * before they are sent to the server.
 */

package afield

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Data class representing a potential snap target
 */
data class SnapTarget(
    val x: Double,
    val y: Double,
    val distance: Double,
    val type: SnapType
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
 * Main snapping system class
 */
class BoxSnappingSystem {
    // Snap threshold in pixels
    private val SNAP_THRESHOLD = 10.0
    
    // Minimum spacing to consider for even spacing detection
    private val MIN_SPACING = 5.0
    
    // Track Caps Lock state
    private var capsLockEnabled = false
    
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
                    console.log("Box snapping: ENABLED (Caps Lock ON)")
                } else {
                    console.log("Box snapping: DISABLED (Caps Lock OFF)")
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
                    console.log("Box snapping: ENABLED (Caps Lock ON)")
                } else {
                    console.log("Box snapping: DISABLED (Caps Lock OFF)")
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
            return proposedX to proposedY
        }
        
        // Get all other boxes
        val allBoxes = getAllBoxes().filter { it != draggedBox }
        
        if (allBoxes.isEmpty()) {
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
        
        // Apply horizontal snap if within threshold
        if (bestHorizontalSnap != null && bestHorizontalSnap.distance <= SNAP_THRESHOLD) {
            snappedX = bestHorizontalSnap.x
            snapped = true
            console.log("Snapping horizontally: ${bestHorizontalSnap.type} (distance: ${bestHorizontalSnap.distance.toInt()}px)")
        }
        
        // Apply vertical snap if within threshold
        if (bestVerticalSnap != null && bestVerticalSnap.distance <= SNAP_THRESHOLD) {
            snappedY = bestVerticalSnap.y
            snapped = true
            console.log("Snapping vertically: ${bestVerticalSnap.type} (distance: ${bestVerticalSnap.distance.toInt()}px)")
        }
        
        if (snapped) {
            console.log("Caps Lock snapping: ($proposedX, $proposedY) → ($snappedX, $snappedY)")
        }
        
        return snappedX to snappedY
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
        
        for (box in otherBoxes) {
            val boxData = getBoxBounds(box)
            val boxLeft = boxData.x
            val boxRight = boxData.x + boxData.w
            val boxCenterX = boxData.x + boxData.w / 2
            
            // Left edge alignment
            val leftToLeftDistance = abs(proposedLeft - boxLeft)
            if (leftToLeftDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(boxLeft, proposedY, leftToLeftDistance, SnapType.LEFT_EDGE))
            }
            
            val leftToRightDistance = abs(proposedLeft - boxRight)
            if (leftToRightDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(boxRight, proposedY, leftToRightDistance, SnapType.LEFT_EDGE))
            }
            
            // Right edge alignment
            val rightToRightDistance = abs(proposedRight - boxRight)
            if (rightToRightDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(boxRight - proposedW, proposedY, rightToRightDistance, SnapType.RIGHT_EDGE))
            }
            
            val rightToLeftDistance = abs(proposedRight - boxLeft)
            if (rightToLeftDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(boxLeft - proposedW, proposedY, rightToLeftDistance, SnapType.RIGHT_EDGE))
            }
            
            // Center alignment
            val centerDistance = abs(proposedCenterX - boxCenterX)
            if (centerDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(boxCenterX - proposedW / 2, proposedY, centerDistance, SnapType.LEFT_EDGE))
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
        
        for (box in otherBoxes) {
            val boxData = getBoxBounds(box)
            val boxTop = boxData.y
            val boxBottom = boxData.y + boxData.h
            val boxCenterY = boxData.y + boxData.h / 2
            
            // Top edge alignment
            val topToTopDistance = abs(proposedTop - boxTop)
            if (topToTopDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(proposedX, boxTop, topToTopDistance, SnapType.TOP_EDGE))
            }
            
            val topToBottomDistance = abs(proposedTop - boxBottom)
            if (topToBottomDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(proposedX, boxBottom, topToBottomDistance, SnapType.TOP_EDGE))
            }
            
            // Bottom edge alignment
            val bottomToBottomDistance = abs(proposedBottom - boxBottom)
            if (bottomToBottomDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(proposedX, boxBottom - proposedH, bottomToBottomDistance, SnapType.BOTTOM_EDGE))
            }
            
            val bottomToTopDistance = abs(proposedBottom - boxTop)
            if (bottomToTopDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(proposedX, boxTop - proposedH, bottomToTopDistance, SnapType.BOTTOM_EDGE))
            }
            
            // Center alignment
            val centerDistance = abs(proposedCenterY - boxCenterY)
            if (centerDistance < SNAP_THRESHOLD * 2) {
                targets.add(SnapTarget(proposedX, boxCenterY - proposedH / 2, centerDistance, SnapType.TOP_EDGE))
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
                        targets.add(SnapTarget(evenX, proposedY, distance, SnapType.HORIZONTAL_SPACING))
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
                        targets.add(SnapTarget(proposedX, evenY, distance, SnapType.VERTICAL_SPACING))
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
            .filter { it.unsafeCast<dynamic>().uid != undefined }
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
