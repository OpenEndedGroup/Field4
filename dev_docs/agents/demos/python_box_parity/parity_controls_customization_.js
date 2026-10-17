const title = this.querySelector('.box-title-stack') || this;
let label = this.querySelector('[data-afield-language-label="true"]');
if (!label) {
    label = document.createElement('div');
    label.setAttribute('data-afield-language-label', 'true');
    title.appendChild(label);
}
label.textContent = "Python";
label.style.fontSize = "9px";
label.style.lineHeight = "1.1";
label.style.opacity = "0.58";
label.style.fontWeight = "500";
label.style.letterSpacing = "0";
label.style.textTransform = "none";
label.style.pointerEvents = "none";
label.style.userSelect = "none";
label.style.marginTop = "1px";
this.style.margin = "";