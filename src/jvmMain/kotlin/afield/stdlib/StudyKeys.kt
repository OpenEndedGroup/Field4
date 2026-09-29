@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import java.io.File

/**
 * Keys for a study card: a snapshot of every number in a program, saved by name, which the card can
 * apply or blend towards — the person's way of keeping "this look" and "that look" and moving between
 * them before choosing a revision.
 *
 * A key is a [KeyframeScope] key like any other, saved with [KeyframeScope.saveKey], so each has its
 * `.versions` history and the keyframe browser can read the folder. Its values are the knobs by index
 * (`k0`, `k1`, …) and, under `shape`, the program's shape ([KnobNames.shape]): a key applies to the
 * program it was captured from, and to that program with any of its numbers changed, but not to one whose
 * text has changed around them, where the same index may be a different number.
 *
 * Keys live in `keys/` in the study's directory.
 */
internal object StudyKeys {
    /** A saved key: its [name], the [shape] of the program it came from, and its numbers by knob index. */
    data class Saved(val name: String, val shape: String, val values: Map<Int, Double>)

    private const val SHAPE = "shape"

    private fun folder(directory: File) = File(directory, "keys")

    private fun file(directory: File, name: String) = File(folder(directory), slug(name) + ".json")

    /** A file name for [name]: lower case, letters, digits and dashes. */
    fun slug(name: String) = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "key" }

    private fun scope(shape: () -> String, values: () -> Map<Int, Double>, indices: Collection<Int>) = KeyframeScope().apply {
        stringTarget(SHAPE, shape) {}
        indices.forEach { index -> doubleTarget("k$index", { values()[index] ?: 0.0 }) {} }
    }

    /** Save [values] as the key [name] of a program of [shape], replacing a key of that name; [note] goes into its history. */
    fun save(directory: File, name: String, shape: String, values: Map<Int, Double>, note: String? = null): Saved {
        val scope = scope({ shape }, { values }, values.keys)
        scope.saveKey(file(directory, name).toPath(), scope.capture(), note ?: name)
        return Saved(name, shape, values)
    }

    /** Every key saved for this study, oldest first; a file that does not read as a key is left out. */
    fun all(directory: File): List<Saved> {
        val files = folder(directory).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedWith(compareBy({ it.lastModified() }, { it.name }))
        return files.mapNotNull { f ->
            runCatching {
                val key = KeyframeScope().loadKey(f.toPath())
                val values = key.values.entries.mapNotNull { (k, v) ->
                    k.removePrefix("k").toIntOrNull()?.takeIf { k.startsWith("k") }?.let { i -> (v as? Number)?.let { i to it.toDouble() } }
                }.toMap()
                Saved(f.nameWithoutExtension, key.values[SHAPE]?.toString().orEmpty(), values)
            }.getOrNull()
        }
    }

    /** The card's config for its keys: each key's name, whether it belongs to [shape], and its numbers. */
    fun config(directory: File, shape: String): List<Map<String, Any?>> =
        all(directory).map { mapOf("name" to it.name, "applies" to (it.shape == shape), "values" to it.values.mapKeys { (k, _) -> k.toString() }) }

    /**
     * The HTML a card puts where its keys go; [SCRIPT] fills it. [blendSlider] is a Field slider from
     * 0 to 1 carrying `data-blend`.
     */
    fun section(blendSlider: String) =
        "<h4>Keys</h4><div class='muted' style='margin-bottom:6px'>A key keeps every number at once. Apply one, or blend from one to another; " +
            "<b>Keep these numbers</b> then keeps what you see.</div><div class='studykeys'></div>" +
            "<div class='keyblend' style='display:none;margin-top:6px'><select class='keyA'></select> → <select class='keyB'></select>" +
            "<span class='blendslider' style='display:inline-flex;width:420px;vertical-align:middle;margin-left:8px'>$blendSlider</span></div>" +
            "<div style='margin-top:6px'><button data-keyact='capture'>Capture these numbers as a key</button></div>"

    /**
     * The card's browser half for keys. It expects `C.keys` (from [config]), `send`, `knobValues()` — every
     * knob's current number, by index — `applyValues(map)`, which moves the sliders, and `blendValue(a, b,
     * t, index)`, which blends one knob (a colour mixes per channel). Capturing adds the key in place, so
     * the sliders keep what has not been kept yet.
     */
    const val SCRIPT = """
            const keyList=root.querySelector('.studykeys'); const blendRow=root.querySelector('.keyblend');
            const keyA=root.querySelector('.keyA'), keyB=root.querySelector('.keyB');
            const keys=(C.keys||[]).slice();
            const usable=()=>keys.filter(k=>k.applies);
            const drawKeys=()=>{
              keyList.innerHTML='';
              if(keys.length===0){ keyList.innerHTML="<span class='muted'>No keys yet.</span>"; }
              keys.forEach(k=>{ const b=document.createElement('button'); b.textContent=k.name; b.style.marginRight='6px';
                if(k.applies){ b.title='apply '+k.name; b.addEventListener('click',(ev)=>{ ev.stopPropagation(); applyValues(k.values); }); }
                else { b.disabled=true; b.title='captured from an earlier version of the program, whose numbers were not these'; }
                keyList.appendChild(b); });
              const u=usable(); blendRow.style.display=u.length>=2?'':'none';
              // From the first key to the newest, unless the person has picked; a new capture becomes the "to".
              [keyA,keyB].forEach((s,i)=>{ const was=s.dataset.picked; s.innerHTML=u.map(k=>'<option>'+k.name+'</option>').join('');
                s.value=(was&&u.some(k=>k.name===was))?was:((i===0?u[0]:u[u.length-1])||{}).name||''; });
            };
            [keyA,keyB].forEach(s=>s.addEventListener('change',()=>{ s.dataset.picked=s.value; }));
            drawKeys();
            root.addEventListener('field-slider-input',(ev)=>{ const s=ev.target.closest&&ev.target.closest('.field-slider[data-blend]'); if(!s) return;
              ev.stopPropagation();
              const a=keys.find(k=>k.name===keyA.value), b=keys.find(k=>k.name===keyB.value); if(!a||!b) return;
              const t=ev.detail.value, out={};
              Object.keys(a.values).forEach(i=>{ if(b.values[i]!==undefined) out[i]=blendValue(a.values[i],b.values[i],t,i); });
              applyValues(out); }, true);
            root.querySelectorAll('button[data-keyact=capture]').forEach(b=>b.addEventListener('click',(ev)=>{ ev.stopPropagation();
              const values=knobValues(); let n=1; while(keys.some(k=>k.name==='key-'+n)) n++; const name='key-'+n;
              send({action:'capture-key',name:name,values:values});
              const at=keys.findIndex(k=>k.name===name); const key={name:name,applies:true,values:values};
              if(at>=0) keys[at]=key; else keys.push(key); delete keyB.dataset.picked; drawKeys(); }));
    """

    /**
     * The person's keys, for a model's Inspect: one line each, naming the knobs whose numbers differ from
     * [current] (`key-2: k3 0.43→0.6, k7 620→410`), or saying the key is from an earlier program. Null
     * when there are none.
     */
    fun summary(directory: File, shape: String, current: Map<Int, Double>): List<String>? = all(directory).map { key ->
        if (key.shape != shape) "${key.name}: from an earlier version of the program; it does not apply"
        else {
            val differs = key.values.entries.sortedBy { it.key }.filter { (k, v) -> current[k]?.let { kotlin.math.abs(it - v) > 1e-9 } == true }
            "${key.name}: " + (differs.take(12).joinToString(", ") { (k, v) -> "k$k ${fmt(current[k]!!)}→${fmt(v)}" }.ifBlank { "the current numbers" }) +
                (if (differs.size > 12) ", and ${differs.size - 12} more" else "")
        }
    }.ifEmpty { null }

    /** [summary] for [knobs] of [source] in [language]: the shape and the current numbers both come from the knobs. */
    fun summary(directory: File, language: String, source: String, knobs: List<StudyKnob>): List<String>? =
        summary(directory, KnobNames.shape(language, source, knobs), knobs.associate { it.index to it.value })

    private fun fmt(v: Double) = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e9) v.toLong().toString() else "%.4g".format(v).trimEnd('0').trimEnd('.')

    /** A name for the next key: `key-1`, `key-2`, … — the first not taken. */
    fun nextName(directory: File): String {
        val taken = all(directory).map { it.name }.toSet()
        return generateSequence(1) { it + 1 }.map { "key-$it" }.first { it !in taken }
    }
}
