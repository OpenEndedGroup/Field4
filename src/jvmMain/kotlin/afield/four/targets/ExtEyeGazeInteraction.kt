package afield.four.targets

import afield.logger.lprintln
import org.lwjgl.openxr.*
import org.lwjgl.openxr.EXTEyeGazeInteraction.*
import org.lwjgl.openxr.XR10.*
import org.lwjgl.system.MemoryStack

/** Optional combined gaze action, owned by the target's existing gameplay action set. */
internal class ExtEyeGazeInteraction private constructor(
    private val action: XrAction,
    private val space: XrSpace,
) {
    val state = EyeGazeState()

    /** Called only after a successful sync of the gameplay action set. */
    fun update(session: XrSession, referenceSpace: XrSpace, predictedTime: Long) {
        state.invalidate()
        MemoryStack.stackPush().use { stack ->
            val getInfo = XrActionStateGetInfo.calloc(stack).type(XR_TYPE_ACTION_STATE_GET_INFO)
                .action(action).subactionPath(XR_NULL_PATH)
            val actionState = XrActionStatePose.calloc(stack).type(XR_TYPE_ACTION_STATE_POSE)
            if (xrGetActionStatePose(session, getInfo, actionState) != XR_SUCCESS || !actionState.isActive()) return
            state.isActive = true
            val sample = XrEyeGazeSampleTimeEXT.calloc(stack).type(XR_TYPE_EYE_GAZE_SAMPLE_TIME_EXT)
            val location = XrSpaceLocation.calloc(stack).type(XR_TYPE_SPACE_LOCATION).next(sample.address())
            if (xrLocateSpace(space, referenceSpace, predictedTime, location) == XR_SUCCESS) {
                state.update(location, sample.time())
            }
        }
    }

    /** Destroy the space before the containing action set/session. The action set owns the action. */
    fun close() {
        state.invalidate()
        xrDestroySpace(space)
    }

    companion object {
        /** Must run before xrAttachSessionActionSets; gaze has no hand subaction paths. */
        fun create(session: XrSession, systemId: Long, actionSet: XrActionSet): ExtEyeGazeInteraction? {
            val instance = session.instance
            if (!instance.capabilities.XR_EXT_eye_gaze_interaction) return null
            MemoryStack.stackPush().use { stack ->
                val eyeProperties = XrSystemEyeGazeInteractionPropertiesEXT.calloc(stack)
                    .type(XR_TYPE_SYSTEM_EYE_GAZE_INTERACTION_PROPERTIES_EXT)
                val properties = XrSystemProperties.calloc(stack).type(XR_TYPE_SYSTEM_PROPERTIES)
                    .next(eyeProperties.address())
                val propsResult = xrGetSystemProperties(instance, systemId, properties)
                if (propsResult != XR_SUCCESS) {
                    lprintln("warning,openxr", "eye gaze system properties failed: $propsResult")
                    return null
                }
                if (!eyeProperties.supportsEyeGazeInteraction()) return null

                // Resolve paths before creating resources, so partial initialization cannot leak them.
                val profile = stack.callocLong(1)
                val binding = stack.callocLong(1)
                if (xrStringToPath(instance, "/interaction_profiles/ext/eye_gaze_interaction", profile) != XR_SUCCESS ||
                    xrStringToPath(instance, "/user/eyes_ext/input/gaze_ext/pose", binding) != XR_SUCCESS
                ) {
                    lprintln("warning,openxr", "eye gaze binding paths unavailable")
                    return null
                }
                val info = XrActionCreateInfo.calloc(stack).type(XR_TYPE_ACTION_CREATE_INFO)
                    .actionType(XR_ACTION_TYPE_POSE_INPUT)
                    .actionName(stack.UTF8("eye_gaze_pose")).localizedActionName(stack.UTF8("Eye Gaze Pose"))
                val outAction = stack.callocPointer(1)
                var result = xrCreateAction(actionSet, info, outAction)
                if (result != XR_SUCCESS) {
                    lprintln("warning,openxr", "xrCreateAction(eye gaze) failed: $result")
                    return null
                }
                val action = XrAction(outAction[0], actionSet)
                val bindings = XrActionSuggestedBinding.calloc(1, stack)
                bindings[0].action(action).binding(binding[0])
                val suggested = XrInteractionProfileSuggestedBinding.calloc(stack)
                    .type(XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING)
                    .interactionProfile(profile[0]).suggestedBindings(bindings)
                result = xrSuggestInteractionProfileBindings(instance, suggested)
                if (result != XR_SUCCESS) {
                    lprintln("warning,openxr", "eye gaze suggested binding failed: $result")
                    xrDestroyAction(action)
                    return null
                }
                val spaceInfo = XrActionSpaceCreateInfo.calloc(stack).type(XR_TYPE_ACTION_SPACE_CREATE_INFO)
                    .action(action).subactionPath(XR_NULL_PATH)
                spaceInfo.poseInActionSpace().orientation().w(1f)
                val outSpace = stack.callocPointer(1)
                result = xrCreateActionSpace(session, spaceInfo, outSpace)
                if (result != XR_SUCCESS) {
                    lprintln("warning,openxr", "xrCreateActionSpace(eye gaze) failed: $result")
                    xrDestroyAction(action)
                    return null
                }
                lprintln("info,openxr", "EXT eye gaze interaction enabled")
                return ExtEyeGazeInteraction(action, XrSpace(outSpace[0], session))
            }
        }
    }
}
