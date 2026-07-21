(function () {
    if (typeof AFRAME === "undefined" || AFRAME.components["portal-button"]) {
        return;
    }

    const DEFAULT_COLOR = "#2f6fed";
    const DEFAULT_HOVER_COLOR = "#5d8cff";
    const DEFAULT_TEXT_COLOR = "#f5f7ff";
    const panelWorldPosition = new THREE.Vector3();
    const cameraWorldPosition = new THREE.Vector3();

    function buildPortalTarget(docName) {
        const trimmed = String(docName || "").trim();
        if (!trimmed) return null;

        if (/^(https?:)?\/\//i.test(trimmed)) return trimmed;
        if (trimmed.startsWith("/")) return trimmed;

        const target = new URL("/index.html", window.location.href);
        target.searchParams.set("file", trimmed);

        const current = new URL(window.location.href);
        const fieldHost = current.searchParams.get("field");
        if (fieldHost) {
            target.searchParams.set("field", fieldHost);
        }

        return target.toString();
    }

    function setMaterial(el, color) {
        el.setAttribute(
            "material",
            [
                "shader: standard",
                "color: " + color,
                "emissive: " + color,
                "emissiveIntensity: 0.18",
                "metalness: 0.08",
                "roughness: 0.34",
                "transparent: true",
                "opacity: 0.96"
            ].join("; ")
        );
    }

    function setGeometry(el, data) {
        el.setAttribute(
            "geometry",
            [
                "primitive: box",
                "width: " + data.width,
                "height: " + data.height,
                "depth: " + data.depth
            ].join("; ")
        );
    }

    function getCameraObject3D(sceneEl) {
        if (!sceneEl || !sceneEl.camera) return null;
        return sceneEl.camera.el?.object3D || sceneEl.camera.parent || sceneEl.camera;
    }

    AFRAME.registerComponent("portal-button", {
        schema: {
            doc: { type: "string" },
            label: { type: "string", default: "" },
            width: { type: "number", default: 1.35 },
            height: { type: "number", default: 0.42 },
            depth: { type: "number", default: 0.12 },
            color: { type: "color", default: DEFAULT_COLOR },
            hoverColor: { type: "color", default: DEFAULT_HOVER_COLOR },
            textColor: { type: "color", default: DEFAULT_TEXT_COLOR },
            proximity: { type: "number", default: 0.72 }
        },

        init: function () {
            this.navigating = false;
            this.lastProximityCheck = 0;

            this.labelEl = document.createElement("a-entity");
            this.labelEl.setAttribute("data-portal-button-label", "true");
            this.labelEl.setAttribute("position", "0 0 0.07");
            this.el.appendChild(this.labelEl);

            this.onMouseEnter = () => this.setHighlighted(true);
            this.onMouseLeave = () => this.setHighlighted(false);
            this.onClick = () => this.activate("click");

            this.el.addEventListener("mouseenter", this.onMouseEnter);
            this.el.addEventListener("mouseleave", this.onMouseLeave);
            this.el.addEventListener("click", this.onClick);

            const existingClass = this.el.getAttribute("class");
            this.el.setAttribute("class", ((existingClass || "") + " clickable portal-button").trim());
            this.el.setAttribute("shadow", "cast: true; receive: true");

            this.updateVisuals();
        },

        update: function () {
            this.updateVisuals();
        },

        tick: function (time) {
            if (this.navigating || !this.data.doc) return;
            if (time - this.lastProximityCheck < 120) return;
            this.lastProximityCheck = time;

            const cameraObject = getCameraObject3D(this.el.sceneEl);
            if (!cameraObject) return;

            this.el.object3D.getWorldPosition(panelWorldPosition);
            cameraObject.getWorldPosition(cameraWorldPosition);

            if (panelWorldPosition.distanceToSquared(cameraWorldPosition) <= this.data.proximity * this.data.proximity) {
                this.activate("proximity");
            }
        },

        remove: function () {
            this.el.removeEventListener("mouseenter", this.onMouseEnter);
            this.el.removeEventListener("mouseleave", this.onMouseLeave);
            this.el.removeEventListener("click", this.onClick);

            if (this.labelEl && this.labelEl.parentNode === this.el) {
                this.el.removeChild(this.labelEl);
            }
        },

        setHighlighted: function (highlighted) {
            if (this.navigating) return;
            setMaterial(this.el, highlighted ? this.data.hoverColor : this.data.color);
            this.el.setAttribute("scale", highlighted ? "1.04 1.04 1.04" : "1 1 1");
        },

        updateVisuals: function () {
            setGeometry(this.el, this.data);
            setMaterial(this.el, this.data.color);

            const label = this.data.label || this.data.doc || "Open";
            const wrapCount = Math.max(12, Math.round(this.data.width * 12));
            const textWidth = Math.max(1.8, this.data.width * 1.7);

            this.labelEl.setAttribute(
                "text",
                [
                    "value: " + label.replace(/\n/g, "\\n"),
                    "align: center",
                    "anchor: center",
                    "baseline: center",
                    "color: " + this.data.textColor,
                    "width: " + textWidth,
                    "wrapCount: " + wrapCount
                ].join("; ")
            );

            this.labelEl.setAttribute("position", "0 0 " + (this.data.depth / 2 + 0.02));
        },

        activate: function () {
            if (this.navigating) return;

            const target = buildPortalTarget(this.data.doc);
            if (!target) return;

            this.navigating = true;
            this.setHighlighted(true);

            setTimeout(() => {
                window.location.assign(target);
            }, 90);
        }
    });
})();
