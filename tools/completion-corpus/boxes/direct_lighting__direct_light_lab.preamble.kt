import afield.four.Renderer
import afield.four.util.Window
import afield.four.lighting.DirectLightingDemo
var Box.lab by Inheritance<DirectLightingDemo>() /**/
val Once.lab by Onceing<DirectLightingDemo>() /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
