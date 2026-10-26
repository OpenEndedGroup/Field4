// Shared by the native picker and its search tests. Slide text is plain data, never HTML.
((root) => {
  const fold = text => String(text).normalize('NFKD').replace(/\p{M}/gu, '').toLowerCase();
  const terms = query => fold(query).trim().split(/\s+/u).filter(Boolean);
  const matches = (text, queryTerms) => queryTerms.every(term => text.includes(term));
  function excerpt(text, queryTerms, limit = 180) {
    const normalized = fold(text);
    const positions = queryTerms.map(term => normalized.indexOf(term)).filter(index => index >= 0);
    if (!positions.length) return '';
    const start = Math.max(0, Math.min(...positions) - 50);
    const end = Math.min(text.length, start + limit);
    return (start ? '…' : '') + text.slice(start, end) + (end < text.length ? '…' : '');
  }
  const api = {fold, terms, matches, excerpt};
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FieldPresentationSearch = api;
})(globalThis);
