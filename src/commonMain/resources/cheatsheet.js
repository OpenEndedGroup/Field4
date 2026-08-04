// Fills in the `?` cheatsheet overlay's key legends.
//
// Two kinds of legend appear in index.html:
//
//   <span class="leftKey" data-command="new-box"></span>
//       Filled from the shortcut actually bound to that command, so the card follows
//       ~/.afield/keybindings.yaml instead of describing whatever was true when the markup was
//       written. A command with no shortcut renders as "unbound".
//
//   <kbd data-key="mod">
//       A bare modifier inside a legend that is a mouse gesture or a multi-tap ("Opt+drag",
//       "Shift Shift") and so has no single command behind it. Those stay hand-written; this just
//       spells the modifier the way the current platform does.
//
// Re-rendered whenever the bindings change (Keybindings.reregisterAll calls in here) and again each
// time the card is shown, so it cannot drift.
(() => {
	const platform = String(navigator.userAgentData?.platform || navigator.platform || "").toLowerCase();
	const isMac = platform.includes("mac");
	const modifierLabels = {
		mod: isMac ? "Cmd" : "Ctrl",
		alt: isMac ? "Opt" : "Alt",
		shift: "Shift",
	};

	/**
	 * The labels bound to a command, `[]` if it deliberately has none, or null if Field cannot say —
	 * either the keybinding layer is not up yet, or the command is dormant (a selection-scoped one
	 * like `connect` with nothing selected). Null means leave the hand-written legend alone: it is
	 * still the default, and claiming "unbound" would be wrong.
	 */
	const labelsFor = (id) => {
		const kb = window.fieldKeybindings;
		if (!kb || typeof kb.labels !== "function") return null;
		try {
			const labels = kb.labels(id);
			return labels == null ? null : Array.from(labels);
		} catch (e) {
			console.warn("[cheatsheet] could not resolve keybinding for", id, e);
			return null;
		}
	};

	const kbd = (text) => {
		const el = document.createElement("kbd");
		el.textContent = text;
		return el;
	};

	/** The hand-written rows separate their <kbd>s with whitespace; keep that so styling matches. */
	const spaced = (nodes) => nodes.flatMap((node, i) => (i === 0 ? [node] : [" ", node]));

	/** `Cmd+Shift+P` → one <kbd> per part. No key's label is itself "+", so splitting is safe. */
	const renderChord = (label) => spaced(label.split("+").map(kbd));

	const renderInto = (target, labels) => {
		if (!labels.length) {
			const none = document.createElement("span");
			none.className = "cheatsheet-unbound";
			none.textContent = "unbound";
			target.replaceChildren(none);
			return;
		}
		const nodes = [];
		labels.forEach((label, index) => {
			if (index > 0) {
				const or = document.createElement("span");
				or.className = "cheatsheet-or";
				or.textContent = "or";
				nodes.push(" ", or, " ");
			}
			nodes.push(...renderChord(label));
		});
		target.replaceChildren(...nodes);
	};

	/**
	 * A row covering a run of numbered commands (bookmarks 1-9). Collapses to `Shift+F1-F9` when the
	 * whole run shares one shape, and otherwise falls back to listing the first slot — never to a
	 * range that isn't true.
	 */
	const renderGroup = (target, prefix, from, to) => {
		const perSlot = [];
		for (let slot = from; slot <= to; slot++) {
			const labels = labelsFor(`${prefix}-${slot}`);
			if (labels === null) return false;
			perSlot.push(labels);
		}
		const firstLabels = perSlot[0] || [];
		if (firstLabels.length === 1) {
			// Does every slot look like the first with only its trailing key changed?
			const head = firstLabels[0].split("+").slice(0, -1).join("+");
			const tails = perSlot.map((labels) => (labels.length === 1 ? labels[0].split("+") : null));
			const uniform = tails.every((parts, i) =>
				parts && parts.slice(0, -1).join("+") === head && parts[parts.length - 1] === `F${from + i}`);
			if (uniform) {
				const parts = head ? head.split("+") : [];
				target.replaceChildren(...spaced([...parts.map(kbd), kbd(`F${from}-F${to}`)]));
				return true;
			}
		}
		renderInto(target, firstLabels);
		return true;
	};

	const render = () => {
		const root = document.getElementById("cheatsheet");
		if (!root) return;

		root.querySelectorAll("kbd[data-key]").forEach((kbd) => {
			kbd.textContent = modifierLabels[kbd.dataset.key] || kbd.dataset.key || "";
		});

		root.querySelectorAll("[data-command]").forEach((target) => {
			const labels = labelsFor(target.dataset.command);
			// Null means the keybinding layer is not up yet — leave the markup alone and try again on
			// the next render rather than blanking the card.
			if (labels !== null) renderInto(target, labels);
		});

		// Inline in a sentence: show the chord if there is one, and nothing at all otherwise — a
		// mid-sentence "unbound" would read as noise.
		root.querySelectorAll("[data-command-inline]").forEach((target) => {
			const labels = labelsFor(target.dataset.commandInline);
			if (!labels || !labels.length) { target.replaceChildren(); return; }
			target.replaceChildren(" ", ...renderChord(labels[0]));
		});

		root.querySelectorAll("[data-command-group]").forEach((target) => {
			const from = Number(target.dataset.commandFrom || 1);
			const to = Number(target.dataset.commandTo || 9);
			renderGroup(target, target.dataset.commandGroup, from, to);
		});

		const path = window.fieldUserKeybindingsPath;
		if (path) {
			root.querySelectorAll("[data-keybindings-path]").forEach((el) => { el.textContent = path; });
		}
	};

	window.renderCheatsheetKeys = render;

	/** Lets the card offer a command as a link, so "Open Keybindings" is one click from the help. */
	const runCommand = (id) => {
		const action = (window.lastActions || []).find((a) => a.id === id);
		if (action && typeof action.run === "function") action.run();
		else console.warn("[cheatsheet] no such command", id);
	};

	const bindLinks = () => {
		const root = document.getElementById("cheatsheet");
		if (!root) return;
		root.addEventListener("click", (event) => {
			const link = event.target.closest?.("[data-run-command]");
			if (!link) return;
			event.preventDefault();
			// Let the click keep bubbling: the overlay's own handler closes the card, which is what
			// you want once the command has opened whatever it opens.
			runCommand(link.dataset.runCommand);
		});
	};

	const start = () => { render(); bindLinks(); };
	if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", start, { once: true });
	else start();
})();
