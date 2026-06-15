package afield.four.device

import afield.logger.lprintln

/**
 * Enables Extended Dynamic Range (EDR) on the macOS CAMetalLayer that backs the GLFW/MoltenVK
 * window.  Without this call the OS compositor clamps output to SDR even when an HDR Vulkan
 * swap-chain format (e.g. scRGB) is selected.
 *
 * Implementation: the GLFW window handle is an NSWindow*.  We walk the Objective-C object graph
 * via the ObjC runtime (`objc_msgSend`) using LWJGL's low-level JNI call helpers, without
 * pulling in any extra native library or annotation processor:
 *
 *   NSWindow → contentView (NSView) → layer (CAMetalLayer) → setWantsExtendedDynamicRangeContent: YES
 *
 * All class and method lookups are done via reflection so this file compiles and loads on
 * Windows/Linux without modification.  Tested against LWJGL 3.4.0-SNAPSHOT; the public APIs
 * used (`GLFWNativeCocoa.glfwGetCocoaWindow`, `LibObjC.sel_registerName`, `LibObjC.getLibrary`,
 * `JNI.callPPP`, `JNI.callPPZV`) have been stable since LWJGL 3.2.
 *
 * All ObjC messaging is wrapped in a try/catch so a failure degrades gracefully to SDR.
 */
object MacHdrActivator {

    /**
     * @param windowHandle  The GLFW window handle returned by `glfwCreateWindow`.
     */
    fun activate(windowHandle: Long) {
        try {
            // Resolve GLFWNativeCocoa reflectively so this file compiles on all platforms.
            val cocoaClass = Class.forName("org.lwjgl.glfw.GLFWNativeCocoa")
            val getNsWindow = cocoaClass.getMethod("glfwGetCocoaWindow", Long::class.javaPrimitiveType)
            val nsWindow = getNsWindow.invoke(null, windowHandle) as Long
            if (nsWindow == 0L) {
                lprintln("warning,hdr", "[HDR] glfwGetCocoaWindow returned nil – EDR not enabled")
                return
            }

            // Resolve LibObjC reflectively so this file compiles on non-Mac platforms.
            val libObjcClass = Class.forName("org.lwjgl.system.macosx.LibObjC")
            val selRegister = libObjcClass.getMethod("sel_registerName", CharSequence::class.java)
            val getLibrary  = libObjcClass.getMethod("getLibrary")

            val library = getLibrary.invoke(null) // SharedLibrary
            val getFnAddr = library.javaClass.getMethod("getFunctionAddress", String::class.java)
            val msgSend = getFnAddr.invoke(library, "objc_msgSend") as Long
            if (msgSend == 0L) {
                lprintln("warning,hdr", "[HDR] objc_msgSend not found – EDR not enabled")
                return
            }

            val selContentView = selRegister.invoke(null, "contentView") as Long
            val selLayer       = selRegister.invoke(null, "layer") as Long
            val selSetEDR      = selRegister.invoke(null, "setWantsExtendedDynamicRangeContent:") as Long

            // Invoke ObjC messages via LWJGL's JNI helper (callPPP = two ptr args → ptr result).
            val jniClass   = Class.forName("org.lwjgl.system.JNI")
            val callPPP    = jniClass.getMethod("callPPP", Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            val callPPZV   = runCatching {
                jniClass.getMethod("callPPZV", Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType)
            }.getOrNull()

            // NSWindow.contentView → NSView
            val contentView = callPPP.invoke(null, nsWindow, selContentView, msgSend) as Long
            if (contentView == 0L) {
                lprintln("warning,hdr", "[HDR] contentView is nil – EDR not enabled")
                return
            }

            // NSView.layer → CAMetalLayer
            val layer = callPPP.invoke(null, contentView, selLayer, msgSend) as Long
            if (layer == 0L) {
                lprintln("warning,hdr", "[HDR] layer is nil – EDR not enabled")
                return
            }

            // [layer setWantsExtendedDynamicRangeContent: YES]
            if (callPPZV != null) {
                callPPZV.invoke(null, layer, selSetEDR, true, msgSend)
            } else {
                // Fallback: callPPJV (long arg) – BOOL is widened to register size by ABI.
                val callPPJV = jniClass.getMethod("callPPJV", Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType)
                callPPJV.invoke(null, layer, selSetEDR, 1L, msgSend)
            }

            lprintln("info,hdr", "[HDR] CAMetalLayer.wantsExtendedDynamicRangeContent = YES (EDR enabled)")
        } catch (e: ClassNotFoundException) {
            lprintln("warning,hdr", "[HDR] Mac EDR activation skipped: required class not found (${e.message})")
        } catch (e: Exception) {
            lprintln("warning,hdr", "[HDR] Mac EDR activation failed: ${e.message}")
        }
    }
}
