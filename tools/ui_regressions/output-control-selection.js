const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const box = name => [...document.querySelectorAll('.box')].find(b => b.textContent.includes(name));
const anchor = box('SelectionAnchor'), controls = box('OutputSelectionControls');
if (!anchor || !controls) return {ok:false, reason:'Missing fixture boxes'};
const selected = () => (window.selectedList || []).map(b => b.uid).join(',');
const before = selected();
const model = window.globalEditor.getModel();
const frames = [anchor.style.transform, controls.style.transform];
const unchanged = () => selected() === before && window.globalEditor.getModel() === model;
const pointer = (element, type, x, y, buttons) => {
    element.dispatchEvent(new PointerEvent(type, {pointerId:41, pointerType:'mouse', isPrimary:true,
        clientX:x, clientY:y, button:0, buttons, bubbles:true, cancelable:true}));
};
const tap = async element => {
    const r = element.getBoundingClientRect(), x = r.x + r.width/2, y = r.y + r.height/2;
    pointer(element,'pointerdown',x,y,1);
    element.dispatchEvent(new MouseEvent('mousedown',{clientX:x,clientY:y,button:0,buttons:1,bubbles:true,cancelable:true}));
    pointer(element,'pointerup',x,y,0);
    element.dispatchEvent(new MouseEvent('mouseup',{clientX:x,clientY:y,button:0,buttons:0,bubbles:true,cancelable:true}));
    await pause(150);
};
await tap(controls.querySelector('#output-selection-text'));
const textLeavesEditor = unchanged();
const svg = controls.querySelector('.field-slider-visual');
const r = svg.getBoundingClientRect();
const x = r.x + r.width * 0.75, y = r.y + r.height/2;
pointer(svg.querySelector('.slider-track'), 'pointerdown', x, y, 1);
pointer(svg, 'pointermove', x + 12, y, 1);
pointer(svg, 'pointerup', x + 12, y, 0);
await pause(600);
const sliderLeavesEditor = unchanged();
const value = Number(controls.querySelector('#output-selection-value')?.textContent);
const noBoxMoved = frames[0] === anchor.style.transform && frames[1] === controls.style.transform;
await tap(controls.querySelector('.box-title-stack'));
const boxStillSelects = selected() === controls.uid;
return {ok:textLeavesEditor && sliderLeavesEditor && value > 0.5 && noBoxMoved && boxStillSelects,
    textLeavesEditor,sliderLeavesEditor,value,noBoxMoved,boxStillSelects};
