// A string assigned to a stack.auto slot is coloured as StackSL without a #stacksl header (the server
// says which strings are slots), a plain string beside it is not, and typing inside the slot's string
// keeps it coloured before the server's next answer.
const delay=ms=>new Promise(r=>setTimeout(r,ms));
await delay(2500);
const ed=window.globalEditor, m=ed.getModel();
const tokens=(line)=>m.getAllDecorations().filter(d=>(d.options.inlineClassName||'').startsWith('afield-stacksl-token')&&d.range.startLineNumber===line).map(d=>m.getValueInRange(d.range));
const slot=tokens(2), plain=tokens(3);
if(JSON.stringify(slot)!==JSON.stringify(['uv','4','*','fbm']))throw new Error('the slot string is not coloured as StackSL: '+JSON.stringify(slot));
if(plain.length)throw new Error('a string assigned to no slot is coloured: '+JSON.stringify(plain));
ed.executeEdits('regression',[{range:{startLineNumber:2,startColumn:21,endLineNumber:2,endColumn:21},text:' 2 *'}]);
await delay(30);
const typing=tokens(2);
if(JSON.stringify(typing)!==JSON.stringify(['uv','2','*','4','*','fbm']))throw new Error('colouring did not follow the edit: '+JSON.stringify(typing));
return {ok:true,slot,typing};
