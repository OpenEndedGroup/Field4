// Fuzzy matching for the palettes (Open Anything, Command Palette, pickers).
//
// fzf-style: every whitespace-separated query word must match somewhere, as a subsequence, and
// the best alignment wins (not the first one a greedy scan finds). Matches at word starts,
// camelCase humps and after `_ - . / :` score more, runs of consecutive characters score more,
// and gaps cost. A word of four or more characters that does not match may drop one character
// (a typo or a swapped pair) at a penalty. Scattered alignments below a quality floor are
// rejected, so short queries do not match long descriptions by accident.
//
// Loaded as a plain script before quickpick.js; also runs under node for tools/test_palette_fuzzy.cjs.
;(function (root) {
        const MAX_DP_TEXT = 400
        const MIN_QUALITY = 0.3

        const isBoundaryAt = (text, j) => {
                if (j === 0) return true
                const prev = text[j - 1]
                const ch = text[j]
                if (/[\s_\-./:\\,()[\]<>]/.test(prev)) return true
                if (/[a-z0-9]/.test(prev) && /[A-Z]/.test(ch)) return true
                if (/[A-Za-z]/.test(prev) && /[0-9]/.test(ch)) return true
                return false
        }

        // Best alignment of one lowercase token in text; returns { score, positions } or null.
        const alignToken = (token, text, lower) => {
                const m = token.length
                const n = lower.length
                if (!m || m > n) return null
                if (n > MAX_DP_TEXT) {
                        // Long text (code, prose): contiguous matches only, preferring a word start.
                        let idx = lower.indexOf(token)
                        if (idx < 0) return null
                        let best = idx
                        while (idx >= 0) {
                                if (isBoundaryAt(text, idx)) { best = idx; break }
                                idx = lower.indexOf(token, idx + 1)
                        }
                        const positions = Array.from({ length: m }, (_, k) => best + k)
                        return { score: m * 6 + (isBoundaryAt(text, best) ? 8 : 0) - Math.min(best, 50) * 0.02, positions }
                }
                const BOUNDARY = 8, CONSEC = 5, BASE = 1, GAP = 0.6, LEAD = 0.02
                const NEG = -Infinity
                const bonus = new Float64Array(n)
                for (let j = 0; j < n; j++) bonus[j] = isBoundaryAt(text, j) ? BOUNDARY : 0
                // S[i][j]: best score with token[i] matched at j. P: predecessor column.
                let prevRow = new Float64Array(n).fill(NEG)
                const back = []
                for (let i = 0; i < m; i++) {
                        const row = new Float64Array(n).fill(NEG)
                        const ptr = new Int32Array(n).fill(-1)
                        const c = token[i]
                        let running = NEG, runningAt = -1
                        for (let j = 0; j < n; j++) {
                                if (i > 0 && j > 0) {
                                        // running = best prevRow[k] - GAP*(j-1-k) for k < j-1 (non-adjacent)
                                        if (running !== NEG) running -= GAP
                                        if (j >= 2 && prevRow[j - 2] > running) { running = prevRow[j - 2]; runningAt = j - 2 }
                                }
                                if (lower[j] !== c) continue
                                const here = BASE + bonus[j]
                                if (i === 0) {
                                        row[j] = here - j * LEAD
                                        continue
                                }
                                let best = NEG, at = -1
                                if (j > 0 && prevRow[j - 1] !== NEG) {
                                        best = prevRow[j - 1] + CONSEC + Math.max(bonus[j] * 0.5, 0)
                                        at = j - 1
                                }
                                if (running !== NEG && running - GAP > best) { best = running - GAP; at = runningAt }
                                if (at >= 0) { row[j] = best + here; ptr[j] = at }
                        }
                        back.push(ptr)
                        prevRow = row
                }
                let end = -1, score = NEG
                for (let j = 0; j < n; j++) if (prevRow[j] > score) { score = prevRow[j]; end = j }
                if (end < 0) return null
                const positions = new Array(m)
                for (let i = m - 1, j = end; i >= 0; i--) {
                        positions[i] = j
                        j = back[i][j]
                }
                return { score, positions }
        }

        // The score a perfect match of a token of this length would get, for normalizing.
        const idealScore = (m) => 1 + 8 + (m - 1) * (1 + 5 + 4)

        const matchToken = (token, text, lower) => {
                const direct = alignToken(token, text, lower)
                const quality = direct ? direct.score / idealScore(token.length) : 0
                if (direct && quality >= MIN_QUALITY) return { quality: Math.min(quality, 1), positions: direct.positions }
                if (token.length < 4) return null
                let best = null
                for (let k = 0; k < token.length; k++) {
                        const variant = token.slice(0, k) + token.slice(k + 1)
                        const hit = alignToken(variant, text, lower)
                        if (!hit) continue
                        const q = hit.score / idealScore(token.length) - 0.15
                        if (q >= MIN_QUALITY && (!best || q > best.quality)) best = { quality: q, positions: hit.positions }
                }
                return best
        }

        const tokensOf = (query) => String(query || "").trim().toLowerCase().split(/\s+/).filter(Boolean)

        // Match a query against one text. Returns { score, positions } (score 0..~120) or null.
        const paletteFuzzyMatch = (query, text) => {
                const tokens = tokensOf(query)
                const original = String(text || "")
                if (!tokens.length) return { score: 0, positions: [] }
                if (!original) return null
                const lower = original.toLowerCase()
                let total = 0
                const positions = new Set()
                for (const token of tokens) {
                        const hit = matchToken(token, original, lower)
                        if (!hit) return null
                        total += hit.quality
                        hit.positions.forEach((p) => positions.add(p))
                }
                let score = 100 * total / tokens.length
                const whole = lower.indexOf(tokens.join(" "))
                if (whole === 0) score += 20
                else if (whole > 0) score += 10
                return { score, positions: [...positions].sort((a, b) => a - b) }
        }

        // Score a query against weighted fields: a string, or [[text, weight], ...]. Each query word
        // may match in any field; it takes the field where it scores best. Returns -Infinity on no match.
        const paletteFuzzyScore = (query, fields) => {
                const tokens = tokensOf(query)
                if (!tokens.length) return 0
                const list = (Array.isArray(fields) ? fields : [[fields, 1]])
                        .map(([text, weight]) => [String(text || ""), weight])
                        .filter(([text]) => text)
                if (!list.length) return -Infinity
                const lowered = list.map(([text]) => text.toLowerCase())
                let total = 0
                for (const token of tokens) {
                        let best = -Infinity
                        list.forEach(([text, weight], i) => {
                                const hit = matchToken(token, text, lowered[i])
                                if (hit && hit.quality * weight > best) best = hit.quality * weight
                        })
                        if (best === -Infinity) return -Infinity
                        total += best
                }
                let score = 100 * total / tokens.length
                const phrase = tokens.join(" ")
                const firstWeight = list[0][1]
                const whole = lowered[0].indexOf(phrase)
                if (whole === 0) score += 20 * firstWeight
                else if (whole > 0) score += 10 * firstWeight
                if (lowered[0] === phrase) score += 10 * firstWeight
                return score
        }

        root.paletteFuzzyMatch = paletteFuzzyMatch
        root.paletteFuzzyScore = paletteFuzzyScore
        if (typeof module !== "undefined" && module.exports) module.exports = { paletteFuzzyMatch, paletteFuzzyScore }
})(typeof window !== "undefined" ? window : globalThis)
