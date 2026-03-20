package afield.stdlib

import afield.graphics.FLine
import org.joml.Vector4f
import java.awt.Stroke

var FLine.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }
var FLine.Node.color by Delegation<Vector4f> { vec(1.0, 1.0, 1.0, 1.0) }
var FLine.thickness by Delegation<Double?>()
var FLine.thicken by Delegation<Stroke?>()
var FLine.filled by Delegation<Boolean> { false }
var FLine.stroked by Delegation<Boolean> { true }
var FLine.pointed by Delegation<Boolean> { false }
var FLine.pointSize by Delegation<Double> { 1.0 }

var FLine.Node.pointSize by Delegation<Double?>(null)

/** Callback invoked when this FLine is clicked in an interactive SVG output. */
var FLine.onClick by Delegation<(() -> Unit)?>()

/**
 * Color to show when the pointer hovers over this FLine in an interactive SVG output.
 * When null no hover color change is applied.
 */
var FLine.hoverColor by Delegation<Vec4?>()

fun seg(a: Vec2, b: Vec2, pointed: Boolean = false, color: Vec4 = vec(1, 1, 1, 1)) = FLine().apply {
    moveTo(a.x, a.y); lineTo(b.x, b.y)
    if (pointed) this.pointed = true
}


