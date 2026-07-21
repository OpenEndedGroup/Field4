(function () {
    const DEFAULTS = {
        fixed: false,
        dotSmall: 0.02,
        dotLarge: 0.04,
        axis: 0.0,
        translationX: 0,
        translationY: 0,
        zIndex: 0
    };

    function numberOr(value, fallback) {
        const next = Number(value);
        return Number.isFinite(next) ? next : fallback;
    }

    function install(host, options) {
        const config = Object.assign({}, DEFAULTS, options || {});
        if (!host || host.__fieldWebglBackground) return host && host.__fieldWebglBackground;

        const canvas = document.createElement("canvas");
        canvas.className = "field-webgl-background";
        canvas.style.position = config.fixed ? "fixed" : "absolute";
        canvas.style.inset = "0";
        canvas.style.width = "100%";
        canvas.style.height = "100%";
        canvas.style.zIndex = String(config.zIndex);
        canvas.style.display = "block";
        canvas.style.pointerEvents = "none";
        host.insertBefore(canvas, host.firstChild);

        const attributes = {
            alpha: false,
            antialias: false,
            depth: false,
            stencil: false,
            preserveDrawingBuffer: true
        };
        const gl = canvas.getContext("webgl", attributes) || canvas.getContext("experimental-webgl", attributes);

        if (!gl) {
            canvas.remove();
            host.__fieldWebglBackground = { ok: false };
            return host.__fieldWebglBackground;
        }

        const vertexSource = `
            attribute vec2 a_position;
            void main() {
                gl_Position = vec4(a_position, 0.0, 1.0);
            }
        `;

        const fragmentSource = `
            precision highp float;

            uniform vec2 u_resolution;
            uniform vec2 u_translation;
            uniform float u_dotSmallAlpha;
            uniform float u_dotLargeAlpha;
            uniform float u_axisAlpha;
            uniform float u_dpr;

            float hash(vec2 p) {
                p = fract(p * vec2(0.1031, 0.11369));
                p += dot(p, p.yx + 19.19);
                return fract((p.x + p.y) * p.x);
            }

            float dotMask(vec2 world, float spacing, float r0, float r1) {
                vec2 q = mod(world - vec2(0.5 * u_dpr), spacing);
                q = min(q, spacing - q);
                float d = length(q);
                return 1.0 - smoothstep(r0, r1, d);
            }

            void main() {
                vec2 frag = vec2(gl_FragCoord.x, u_resolution.y - gl_FragCoord.y);
                float falloff = 450.0 * u_dpr;
                vec2 edgeDistance = min(frag, u_resolution - frag);
                vec2 edgeProximity = 1.0 - clamp(edgeDistance / falloff, 0.0, 1.0);
                float shade = min(length(edgeProximity), 1.0) * 0.28;

                vec3 centerColor = vec3(24.0 / 255.0);
                vec3 edgeColor = vec3(2.0 / 255.0);
                vec3 color = mix(centerColor, edgeColor, shade);

                float dither = (hash(frag) - 0.5) * (2.4 / 255.0);
                color += vec3(dither);

                vec2 world = frag - u_translation;
                float smallDots = dotMask(world, 25.0 * u_dpr, 0.75 * u_dpr, 1.25 * u_dpr);
                float largeDots = dotMask(world, 100.0 * u_dpr, 1.25 * u_dpr, 2.05 * u_dpr);
                float dotAlpha = clamp(smallDots * u_dotSmallAlpha + largeDots * u_dotLargeAlpha, 0.0, 0.32);
                color = mix(color, vec3(1.0), dotAlpha);

                float axisX = 1.0 - smoothstep(0.45 * u_dpr, 1.05 * u_dpr, abs(frag.x - u_translation.x));
                float axisY = 1.0 - smoothstep(0.45 * u_dpr, 1.05 * u_dpr, abs(frag.y - u_translation.y));
                color = mix(color, vec3(1.0), max(axisX, axisY) * u_axisAlpha);

                gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        `;

        function compile(type, source) {
            const shader = gl.createShader(type);
            gl.shaderSource(shader, source);
            gl.compileShader(shader);
            if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) {
                const log = gl.getShaderInfoLog(shader);
                gl.deleteShader(shader);
                throw new Error(log || "shader compile failed");
            }
            return shader;
        }

        try {
            const program = gl.createProgram();
            gl.attachShader(program, compile(gl.VERTEX_SHADER, vertexSource));
            gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragmentSource));
            gl.linkProgram(program);
            if (!gl.getProgramParameter(program, gl.LINK_STATUS)) {
                throw new Error(gl.getProgramInfoLog(program) || "shader link failed");
            }

            const buffer = gl.createBuffer();
            gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
            gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
                -1, -1,
                 1, -1,
                -1,  1,
                 1,  1
            ]), gl.STATIC_DRAW);

            const locations = {
                position: gl.getAttribLocation(program, "a_position"),
                resolution: gl.getUniformLocation(program, "u_resolution"),
                translation: gl.getUniformLocation(program, "u_translation"),
                dotSmallAlpha: gl.getUniformLocation(program, "u_dotSmallAlpha"),
                dotLargeAlpha: gl.getUniformLocation(program, "u_dotLargeAlpha"),
                axisAlpha: gl.getUniformLocation(program, "u_axisAlpha"),
                dpr: gl.getUniformLocation(program, "u_dpr")
            };

            const state = {
                ok: true,
                hasFrame: false,
                width: 0,
                height: 0,
                dpr: 1,
                tx: numberOr(config.translationX, 0),
                ty: numberOr(config.translationY, 0),
                dotSmall: numberOr(config.dotSmall, DEFAULTS.dotSmall),
                dotLarge: numberOr(config.dotLarge, DEFAULTS.dotLarge),
                axis: numberOr(config.axis, DEFAULTS.axis),
                renderCount: 0,
                skippedCount: 0,
                render(tx, ty, dotSmall, dotLarge, axis) {
                    const dpr = Math.max(1, window.devicePixelRatio || 1);
                    const cssWidth = config.fixed ? window.innerWidth : host.clientWidth;
                    const cssHeight = config.fixed ? window.innerHeight : host.clientHeight;
                    const width = Math.max(1, Math.round(cssWidth * dpr));
                    const height = Math.max(1, Math.round(cssHeight * dpr));
                    const nextTx = numberOr(tx, 0);
                    const nextTy = numberOr(ty, 0);
                    const nextDotSmall = numberOr(dotSmall, 0);
                    const nextDotLarge = numberOr(dotLarge, 0);
                    const nextAxis = numberOr(axis, 0);

                    if (
                        state.hasFrame &&
                        state.width === width &&
                        state.height === height &&
                        state.dpr === dpr &&
                        state.tx === nextTx &&
                        state.ty === nextTy &&
                        state.dotSmall === nextDotSmall &&
                        state.dotLarge === nextDotLarge &&
                        state.axis === nextAxis
                    ) {
                        state.skippedCount += 1;
                        return false;
                    }

                    state.tx = nextTx;
                    state.ty = nextTy;
                    state.dotSmall = nextDotSmall;
                    state.dotLarge = nextDotLarge;
                    state.axis = nextAxis;
                    state.width = width;
                    state.height = height;
                    state.dpr = dpr;

                    if (canvas.width !== width || canvas.height !== height) {
                        canvas.width = width;
                        canvas.height = height;
                    }

                    gl.viewport(0, 0, width, height);
                    gl.useProgram(program);
                    gl.bindBuffer(gl.ARRAY_BUFFER, buffer);
                    gl.enableVertexAttribArray(locations.position);
                    gl.vertexAttribPointer(locations.position, 2, gl.FLOAT, false, 0, 0);
                    gl.uniform2f(locations.resolution, width, height);
                    gl.uniform2f(locations.translation, state.tx * dpr, state.ty * dpr);
                    gl.uniform1f(locations.dotSmallAlpha, state.dotSmall);
                    gl.uniform1f(locations.dotLargeAlpha, state.dotLarge);
                    gl.uniform1f(locations.axisAlpha, state.axis);
                    gl.uniform1f(locations.dpr, dpr);
                    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
                    state.hasFrame = true;
                    state.renderCount += 1;
                    return true;
                }
            };

            host.__fieldWebglBackground = state;
            window.__fieldRenderWebglBackground = function (tx, ty, dotSmall, dotLarge, axis) {
                if (host.__fieldWebglBackground && host.__fieldWebglBackground.ok) {
                    return host.__fieldWebglBackground.render(tx, ty, dotSmall, dotLarge, axis);
                }
                return false;
            };

            const rerender = function () {
                state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis);
            };
            if (!config.fixed && window.ResizeObserver) {
                new ResizeObserver(rerender).observe(host);
            }
            window.addEventListener("resize", rerender);
            state.render(state.tx, state.ty, state.dotSmall, state.dotLarge, state.axis);
            return state;
        } catch (error) {
            console.warn("[field-background] WebGL background disabled", error);
            canvas.remove();
            host.__fieldWebglBackground = { ok: false, error: String(error && error.message || error) };
            return host.__fieldWebglBackground;
        }
    }

    window.FieldWebGLBackground = Object.assign(window.FieldWebGLBackground || {}, { install });
}());
