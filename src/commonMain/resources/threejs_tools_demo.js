/** Optional example: a reference axis marker that stays out of exported geometry. */
export default function axesMarker() {
    let context, axes, button, shown = false;
    function apply() {
        axes.visible = shown;
        button.setAttribute('aria-pressed', String(shown));
        context.requestRender();
    }
    return {
        id: 'axes-marker',
        mount(ctx) {
            context = ctx;
            axes = new ctx.THREE.AxesHelper(1); ctx.overlays.add(axes);
            button = document.createElement('button'); button.className = 'hud-button'; button.textContent = 'Reference axes';
            button.onclick = () => { shown = !shown; apply(); };
            ctx.element.append(button); apply();
        },
        snapshot: () => ({shown}),
        validate(state) { if (!state || typeof state.shown !== 'boolean') throw new Error('Invalid axes marker state'); },
        restore(state) { shown = state.shown; apply(); },
        dispose() { axes.geometry.dispose(); axes.material.dispose(); }
    };
}
