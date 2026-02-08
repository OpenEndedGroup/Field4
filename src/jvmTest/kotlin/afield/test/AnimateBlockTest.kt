/**
 * Test file demonstrating the animate { } block syntax
 * 
 * This file shows how the animate block works and can be used
 * to verify the implementation.
 */

package afield.test

import afield.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnimateBlockTest {
    
    /**
     * Demonstrates that animate returns a Frames object
     */
    @Test
    fun testAnimateReturnsFrames() {
        val result = animate {
            println("Frame: $frameNumber")
        }
        
        assertTrue(result is Frames, "animate should return a Frames object")
    }
    
    /**
     * Demonstrates the intended usage pattern:
     * - Setup code runs once
     * - Animate block code runs every frame
     */
    @Test
    fun testUsagePattern() {
        // This demonstrates the syntax, though we can't test frame execution
        // without a full Field4 environment
        
        var setupCount = 0
        var frameCount = 0
        
        // Setup (would run once)
        setupCount++
        
        // Animation (would run every frame)
        val animation = animate {
            frameCount++
            // This would be called every frame in actual execution
        }
        
        assertEquals(1, setupCount, "Setup code should have run once")
        assertTrue(animation is Frames, "animate should return Frames object")
    }
    
    /**
     * Example showing the problem statement solution
     */
    @Test
    fun testProblemStatementExample() {
        // Simulating the example from the problem statement:
        // foo()
        // bar(10.0)
        // animate {
        //    gux() 
        // }
        
        var fooCallCount = 0
        var barCallCount = 0
        var guxCallCount = 0
        
        fun foo() { fooCallCount++ }
        fun bar(x: Double) { barCallCount++ }
        fun gux() { guxCallCount++ }
        
        // Setup - runs once
        foo()
        bar(10.0)
        
        // Animation - would run every frame
        val animation = animate {
            gux()
        }
        
        // Verify setup ran
        assertEquals(1, fooCallCount, "foo should be called once")
        assertEquals(1, barCallCount, "bar should be called once")
        
        // Note: gux() inside animate hasn't been called yet because
        // the Frames object hasn't been executed. In actual Field4 usage,
        // when this code is launched, the Frames object would be detected
        // and executed every frame by launchReturnValue()
        assertEquals(0, guxCallCount, "gux should not be called yet (animate not launched)")
        assertTrue(animation is Frames, "animate should return Frames for launching")
    }
}
