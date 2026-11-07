/* Gesture-owned pointer lock. Denied/unsupported requests leave ordinary dragging intact. */
(() => {
    if (window.FieldRelativePointer) return;
    let active = null;

    function end(owner) {
        const gesture = active;
        if (!gesture || gesture.owner !== owner) return;
        gesture.ending = true;
        if (document.pointerLockElement === gesture.target) {
            gesture.locked = true;
            document.exitPointerLock();
        }
        // Keep a pending request owned until it settles, so a late grant cannot capture
        // the mouse after release or accidentally unlock a subsequent gesture.
    }

    function lost() {
        const gesture = active;
        if (!gesture || gesture.ending) return;
        end(gesture.owner);
        gesture.onLost?.();
    }

    document.addEventListener('pointerlockchange', () => {
        const gesture = active;
        if (!gesture) return;
        if (document.pointerLockElement === gesture.target) {
            gesture.locked = true;
            if (gesture.ending) document.exitPointerLock();
        } else if (gesture.locked) {
            active = null;
            if (!gesture.ending) gesture.onLost?.();
        }
    });
    document.addEventListener('pointerlockerror', () => {
        if (active && !active.locked) active = null;
    });
    window.addEventListener('blur', lost);
    window.addEventListener('keydown', event => { if (event.key === 'Escape') lost(); }, true);
    document.addEventListener('visibilitychange', () => { if (document.hidden) lost(); });

    window.FieldRelativePointer = {
        begin(owner, event, onLost) {
            if (!event.isTrusted || (event.pointerType && event.pointerType !== 'mouse')) return false;
            if (active) return active.owner === owner && !active.ending;
            if (document.pointerLockElement || !document.body?.requestPointerLock) return false;
            const gesture = { owner, target: document.body, onLost, locked: false, ending: false };
            active = gesture;
            try {
                // Keep OS mouse acceleration; raw/unadjusted motion is less portable.
                const request = gesture.target.requestPointerLock();
                // pointerlockerror settles a denial. Keep it serialized until that event,
                // so an old rejection cannot clear a newer request for the same document.
                request?.catch(() => {});
            } catch (_) {
                active = null;
                return false;
            }
            return true;
        },
        locked(owner) {
            return active?.owner === owner && !active.ending && document.pointerLockElement === active.target;
        },
        end,
    };
})();
