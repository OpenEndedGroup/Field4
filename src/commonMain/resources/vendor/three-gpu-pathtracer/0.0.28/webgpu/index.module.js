var sg=Object.defineProperty;var ag=(o,e)=>{for(var t in e)sg(o,t,{get:e[t],enumerable:!0})};import{Box3 as Lv,DataTexture as Ov,EventDispatcher as Uv,LinearFilter as ca,MathUtils as zv,Vector2 as ph,Scene as Hv,PerspectiveCamera as Wv,Color as Vv,NoToneMapping as vu,FloatType as Gv,Timer as qv,StorageTexture as Xv,MeshBasicNodeMaterial as Yv,Matrix4 as jv,WebGPUCoordinateSystem as Kv}from"/vendor/three/0.187.0/three.webgpu.js";import{uv as Qv,uniform as Zv,varying as Jv}from"/vendor/three/0.187.0/three.tsl.js";import{Box3 as gg}from"/vendor/three/0.187.0/three.webgpu.js";var qr=Math.pow(2,-24),Qt=Symbol("SKIP_GENERATION"),to={strategy:0,maxDepth:40,targetLeafSize:10,useSharedArrayBuffer:!1,setBoundingBox:!0,onProgress:null,indirect:!1,verbose:!0,range:null,[Qt]:!1};function te(o,e,t){return t.min.x=e[o],t.min.y=e[o+1],t.min.z=e[o+2],t.max.x=e[o+3],t.max.y=e[o+4],t.max.z=e[o+5],t}function nn(o){let e=-1,t=-1/0;for(let r=0;r<3;r++){let i=o[r+3]-o[r];i>t&&(t=i,e=r)}return e}function ja(o,e,t){for(let r=0;r<3;r++){let i=e[o+2*r],s=e[o+2*r+1],n=i-s,l=i+s;n<t[r]&&(t[r]=n),l>t[r+3]&&(t[r+3]=l)}}function ro(o){let e=o[3]-o[0],t=o[4]-o[1],r=o[5]-o[2];return 2*(e*t+t*r+r*e)}function k(o,e){return e[o+15]===65535}function K(o,e){return e[o+6]}function ne(o,e){return e[o+14]}function W(o){return o+8}function V(o,e){let t=e[o+6];return o+t*8}function Xr(o,e){return e[o+7]}function io(o,e,t,r,i){let s=1/0,n=1/0,l=1/0,a=-1/0,p=-1/0,u=-1/0,c=1/0,f=1/0,h=1/0,d=-1/0,m=-1/0,x=-1/0,v=o.offset||0;for(let g=(e-v)*6,y=(e+t-v)*6;g<y;g+=6){let w=o[g+0],b=o[g+1],T=w-b,_=w+b;T<s&&(s=T),_>a&&(a=_),w<c&&(c=w),w>d&&(d=w);let S=o[g+2],A=o[g+3],I=S-A,P=S+A;I<n&&(n=I),P>p&&(p=P),S<f&&(f=S),S>m&&(m=S);let C=o[g+4],B=o[g+5],R=C-B,E=C+B;R<l&&(l=R),E>u&&(u=E),C<h&&(h=C),C>x&&(x=C)}r[0]=s,r[1]=n,r[2]=l,r[3]=a,r[4]=p,r[5]=u,i[0]=c,i[1]=f,i[2]=h,i[3]=d,i[4]=m,i[5]=x}var wt=8,Qa=8,Ka=new Int32Array(wt),bt=new Float32Array(wt*6),Yr=new Float32Array(wt*6),ot=new Float32Array(6),no=new Float32Array(6),Zt=new Int32Array(Qa),Sc=new Float32Array(Qa*6);function ug(o,e,t,r){for(let i=0;i<3;i++){let s=e[o+2*i],n=e[o+2*i+1],l=s-n,a=s+n;l<t[r+i]&&(t[r+i]=l),a>t[r+i+3]&&(t[r+i+3]=a)}}function Ic(o,e){let t=o[e+3]-o[e],r=o[e+4]-o[e+1],i=o[e+5]-o[e+2];return 2*(t*r+r*i+i*t)}function $c(o,e,t,r,i,s){let n=-1,l=0;if(s===0)n=nn(e),n!==-1&&(l=(e[n]+e[n+3])/2);else if(s===1)n=nn(o),n!==-1&&(l=cg(t,r,i,n));else if(s===2){let a=ro(o),p=1.25*i,u=t.offset||0,c=(r-u)*6,f=(r+i-u)*6;for(let h=0;h<3;h++){let d=e[h],v=(e[h+3]-d)/wt;if(i<Qa){for(let g=0;g<i;g++){let y=c+g*6,w=t[y+2*h],b=g;for(;b>0&&t[Zt[b-1]+2*h]>w;)Zt[b]=Zt[b-1],b--;Zt[b]=y}for(let g=0;g<3;g++)ot[g]=1/0,ot[g+3]=-1/0,no[g]=1/0,no[g+3]=-1/0;for(let g=i-1;g>=0;g--)ja(Zt[g],t,no),Sc.set(no,g*6);for(let g=0;g<i-1;g++){ja(Zt[g],t,ot);let y=t[Zt[g+1]+2*h];if(t[Zt[g]+2*h]===y)continue;let w=g+1,b=i-w,T=ro(ot)/a,_=Ic(Sc,w*6)/a,S=1+1.25*(T*w+_*b);S<p&&(n=h,p=S,l=y)}}else{for(let w=0;w<wt;w++){Ka[w]=0;for(let b=0;b<3;b++)bt[w*6+b]=1/0,bt[w*6+b+3]=-1/0}for(let w=c;w<f;w+=6){let _=~~((t[w+2*h]-d)/v);_>=wt&&(_=wt-1),Ka[_]++,ug(w,t,bt,_*6)}let g=(wt-1)*6;for(let w=0;w<6;w++)Yr[g+w]=bt[g+w];for(let w=wt-2;w>=0;w--){let b=w*6,T=b+6;for(let _=0;_<3;_++)Yr[b+_]=Math.min(bt[b+_],Yr[T+_]),Yr[b+_+3]=Math.max(bt[b+_+3],Yr[T+_+3])}let y=0;for(let w=0;w<wt-1;w++){let b=w*6,T=Ka[w];if(T!==0)for(let P=0;P<3;P++)y===0?(ot[P]=bt[b+P],ot[P+3]=bt[b+P+3]):(ot[P]=Math.min(ot[P],bt[b+P]),ot[P+3]=Math.max(ot[P+3],bt[b+P+3]));y+=T;let _=0,S=0;y!==0&&(_=ro(ot)/a);let A=i-y;A!==0&&(S=Ic(Yr,b+6)/a);let I=1+1.25*(_*y+S*A);I<p&&(n=h,p=I,l=d+v+w*v)}}}}else console.warn(`BVH: Invalid build strategy value ${s} used.`);return{axis:n,pos:l}}function cg(o,e,t,r){let i=0,s=o.offset;for(let n=e,l=e+t;n<l;n++)i+=o[(n-s)*6+r*2];return i/t}function Cc(o,e,t,r,i,s){let n=r,l=r+i-1,a=s.pos,p=s.axis*2,u=t.offset||0;for(;;){for(;n<=l&&t[(n-u)*6+p]<a;)n++;for(;n<=l&&t[(l-u)*6+p]>=a;)l--;if(n<l){for(let c=0;c<e;c++){let f=o[n*e+c];o[n*e+c]=o[l*e+c],o[l*e+c]=f}for(let c=0;c<6;c++){let f=n-u,h=l-u,d=t[f*6+c];t[f*6+c]=t[h*6+c],t[h*6+c]=d}n++,l--}else return n}}var pg=Math.pow(2,32),jr=new Float32Array(6),oo=new Float32Array(6);function fg(o,e,t,r,i,s){let{maxDepth:n,verbose:l,targetLeafSize:a,_strictLeafSize:p=1/0,strategy:u,onProgress:c,useSharedArrayBuffer:f=!1}=i,h=o.primitiveBuffer,d=o.primitiveBufferStride,m=f?SharedArrayBuffer:ArrayBuffer,x=new m(32*Math.max(1,2*Math.ceil(r)-1)),v=new Float32Array(x),g=new Uint32Array(x),y=new Uint16Array(x),w=0,b=!1;return io(e,t,r,jr,oo),S(t,r,0),x.slice(0,w*32);function T(A){c&&c((A-s.offset)/s.count)}function _(A,I,P){let C=A*2;g[A+6]=I,y[C+14]=P,y[C+15]=65535,T(I+P)}function S(A,I,P){let C=w++,B=C*8;v.set(jr,B),!b&&P>=n&&(b=!0,l&&console.warn(`BVH: Max depth of ${n} reached when generating BVH. Consider increasing maxDepth.`));let R=I>p;if(I<=a&&!R||P>=n){_(B,A,I);return}let E=$c(jr,oo,e,A,I,u),N=E.axis===-1?-1:Cc(h,d,e,A,I,E);if(E.axis===-1||N===A||N===A+I){if(!R){_(B,A,I);return}E.axis=Math.max(0,nn(jr)),N=A+Math.max(1,Math.floor(I/2))}g[B+7]=E.axis;let M=N-A;io(e,A,M,jr,oo),S(A,M,P+1);let oe=w-C;if(oe>pg)throw new Error("MeshBVH: Cannot store relative child node offset greater than 32 bits.");g[B+6]=oe;let we=I-M;io(e,N,we,jr,oo),S(N,we,P+1)}}function Ac(o,e){let t=o.getRootRanges(e.range),r=t[0],i=t[t.length-1],s={offset:r.offset,count:i.offset+i.count-r.offset},n=new Float32Array(6*s.count);n.offset=s.offset,o.computePrimitiveBounds(s.offset,s.count,n),o._roots=t.map(l=>fg(o,n,l.offset,l.count,e,s))}import{Box3 as hg}from"/vendor/three/0.187.0/three.webgpu.js";var Jt=class{constructor(e){this._getNewPrimitive=e,this._primitives=[]}getPrimitive(){let e=this._primitives;return e.length===0?this._getNewPrimitive():e.pop()}releasePrimitive(e){this._primitives.push(e)}};var Za=class{constructor(){this.float32Array=null,this.uint16Array=null,this.uint32Array=null;let e=[],t=null;this.setBuffer=r=>{t&&e.push(t),t=r,this.float32Array=new Float32Array(r),this.uint16Array=new Uint16Array(r),this.uint32Array=new Uint32Array(r)},this.clearBuffer=()=>{t=null,this.float32Array=null,this.uint16Array=null,this.uint32Array=null,e.length!==0&&this.setBuffer(e.pop())}}},L=new Za;var er,Qr,Kr=[],so=new Jt(()=>new hg);function Pc(o,e,t,r,i,s){er=so.getPrimitive(),Qr=so.getPrimitive(),Kr.push(er,Qr),L.setBuffer(o._roots[e]);let n=Ja(0,o.geometry,t,r,i,s);L.clearBuffer(),so.releasePrimitive(er),so.releasePrimitive(Qr),Kr.pop(),Kr.pop();let l=Kr.length;return l>0&&(Qr=Kr[l-1],er=Kr[l-2]),n}function Ja(o,e,t,r,i=null,s=0,n=0){let{float32Array:l,uint16Array:a,uint32Array:p}=L,u=o*2;if(k(u,a)){let f=K(o,p),h=ne(u,a);return te(o,l,er),r(f,h,!1,n,s+o/8,er)}else{let I=function(C){let{uint16Array:B,uint32Array:R}=L,E=C*2;for(;!k(E,B);)C=W(C),E=C*2;return K(C,R)},P=function(C){let{uint16Array:B,uint32Array:R}=L,E=C*2;for(;!k(E,B);)C=V(C,R),E=C*2;return K(C,R)+ne(E,B)},f=W(o),h=V(o,p),d=f,m=h,x,v,g,y;if(i&&(g=er,y=Qr,te(d,l,g),te(m,l,y),x=i(g),v=i(y),v<x)){d=h,m=f;let C=x;x=v,v=C,g=y}g||(g=er,te(d,l,g));let w=k(d*2,a),b=t(g,w,x,n+1,s+d/8),T;if(b===2){let C=I(d),R=P(d)-C;T=r(C,R,!0,n+1,s+d/8,g)}else T=b&&Ja(d,e,t,r,i,s,n+1);if(T)return!0;y=Qr,te(m,l,y);let _=k(m*2,a),S=t(y,_,v,n+1,s+m/8),A;if(S===2){let C=I(m),R=P(m)-C;A=r(C,R,!0,n+1,s+m/8,y)}else A=S&&Ja(m,e,t,r,i,s,n+1);return!!A}}import{Box3 as sn,Matrix4 as mg}from"/vendor/three/0.187.0/three.webgpu.js";var on=new L.constructor,ao=new L.constructor,tr=new Jt(()=>new sn),Zr=new sn,Jr=new sn,tl=new sn,rl=new sn,il=!1;function Bc(o,e,t,r){if(il)throw new Error("MeshBVH: Recursive calls to bvhcast not supported.");il=!0;let i=o._roots,s=e._roots,n,l=0,a=0,p=new mg().copy(t).invert();for(let u=0,c=i.length;u<c;u++){on.setBuffer(i[u]),a=0;let f=tr.getPrimitive();te(0,on.float32Array,f),f.applyMatrix4(p);for(let h=0,d=s.length;h<d&&(ao.setBuffer(s[h]),n=st(0,0,t,p,r,l,a,0,0,f),ao.clearBuffer(),a+=s[h].byteLength/32,!n);h++);if(tr.releasePrimitive(f),on.clearBuffer(),l+=i[u].byteLength/32,n)break}return il=!1,n}function st(o,e,t,r,i,s=0,n=0,l=0,a=0,p=null,u=!1){let c,f;u?(c=ao,f=on):(c=on,f=ao);let h=c.float32Array,d=c.uint32Array,m=c.uint16Array,x=f.float32Array,v=f.uint32Array,g=f.uint16Array,y=o*2,w=e*2,b=k(y,m),T=k(w,g),_=!1;if(T&&b)u?_=i(K(e,v),ne(e*2,g),K(o,d),ne(o*2,m),a,n+e/8,l,s+o/8):_=i(K(o,d),ne(o*2,m),K(e,v),ne(e*2,g),l,s+o/8,a,n+e/8);else if(T){let S=tr.getPrimitive();te(e,x,S),S.applyMatrix4(t);let A=W(o),I=V(o,d);te(A,h,Zr),te(I,h,Jr);let P=S.intersectsBox(Zr),C=S.intersectsBox(Jr);_=P&&st(e,A,r,t,i,n,s,a,l+1,S,!u)||C&&st(e,I,r,t,i,n,s,a,l+1,S,!u),tr.releasePrimitive(S)}else{let S=W(e),A=V(e,v);te(S,x,tl),te(A,x,rl);let I=p.intersectsBox(tl),P=p.intersectsBox(rl);if(I&&P)_=st(o,S,t,r,i,s,n,l,a+1,p,u)||st(o,A,t,r,i,s,n,l,a+1,p,u);else if(I)if(b)_=st(o,S,t,r,i,s,n,l,a+1,p,u);else{let C=tr.getPrimitive();C.copy(tl).applyMatrix4(t);let B=W(o),R=V(o,d);te(B,h,Zr),te(R,h,Jr);let E=C.intersectsBox(Zr),N=C.intersectsBox(Jr);_=E&&st(S,B,r,t,i,n,s,a,l+1,C,!u)||N&&st(S,R,r,t,i,n,s,a,l+1,C,!u),tr.releasePrimitive(C)}else if(P)if(b)_=st(o,A,t,r,i,s,n,l,a+1,p,u);else{let C=tr.getPrimitive();C.copy(rl).applyMatrix4(t);let B=W(o),R=V(o,d);te(B,h,Zr),te(R,h,Jr);let E=C.intersectsBox(Zr),N=C.intersectsBox(Jr);_=E&&st(A,B,r,t,i,n,s,a,l+1,C,!u)||N&&st(A,R,r,t,i,n,s,a,l+1,C,!u),tr.releasePrimitive(C)}}return _}var _t=new class{constructor(){let o=null,e=null,t=null,r=!1;this.root=null,this.buffer=null,this.uint32Array=null,this.uint16Array=null,this.setBVH=(s,n)=>{if(r)throw new Error("BVHTraversalHelper: cannot call setBVH during an active traversal.");this.root=n,this.buffer=o=s._roots[n],this.uint16Array=t=new Uint16Array(o),this.uint32Array=e=new Uint32Array(o)},this.reset=()=>{this.root=null,this.buffer=o=null,this.uint16Array=t=null,this.uint32Array=e=null},this.getRangeStart=s=>{let n=s*2;for(;!k(n,t);)s=W(s),n=s*2;return K(s,e)},this.getRangeEnd=s=>{let n=s*2;for(;!k(n,t);)s=V(s,e),n=s*2;return K(s,e)+ne(n,t)};let i=(s,n,l)=>{let a=n*2,p=k(a,t);if(!s(l,p,n)&&!p){let c=W(n),f=V(n,e);i(s,c,l+1),i(s,f,l+1)}};this.traverseBuffer=s=>{if(r)throw new Error("BVHTraversalHelper: cannot start a traversal during an active traversal.");r=!0;try{i(s,0,0)}finally{r=!1}},this.traverse=s=>{this.traverseBuffer((n,l,a)=>{if(l){let p=a*2,u=e[a+6],c=t[p+14];return s(n,l,new Float32Array(o,a*4,6),u,c)}else{let p=Xr(a,e);return s(n,l,new Float32Array(o,a*4,6),p)}})}}};var Rc=new gg,ei=new Float32Array(6),ti=class{constructor(){this._roots=null,this.primitiveBuffer=null,this.primitiveBufferStride=null}init(e){e={...to,...e},"maxLeafSize"in e&&(console.warn('BVH: "maxLeafSize" option has been deprecated. Use "targetLeafSize", instead.'),e={...e,targetLeafSize:e.maxLeafSize}),Ac(this,e)}getRootRanges(){throw new Error("BVH: getRootRanges() not implemented")}writePrimitiveBounds(){throw new Error("BVH: writePrimitiveBounds() not implemented")}writePrimitiveRangeBounds(e,t,r,i){let s=1/0,n=1/0,l=1/0,a=-1/0,p=-1/0,u=-1/0;for(let c=e,f=e+t;c<f;c++){this.writePrimitiveBounds(c,ei,0);let[h,d,m,x,v,g]=ei;h<s&&(s=h),x>a&&(a=x),d<n&&(n=d),v>p&&(p=v),m<l&&(l=m),g>u&&(u=g)}return r[i+0]=s,r[i+1]=n,r[i+2]=l,r[i+3]=a,r[i+4]=p,r[i+5]=u,r}computePrimitiveBounds(e,t,r){let i=r.offset||0;for(let s=e,n=e+t;s<n;s++){this.writePrimitiveBounds(s,ei,0);let[l,a,p,u,c,f]=ei,h=(l+u)/2,d=(a+c)/2,m=(p+f)/2,x=(u-l)/2,v=(c-a)/2,g=(f-p)/2,y=(s-i)*6;r[y+0]=h,r[y+1]=x+(Math.abs(h)+x)*qr,r[y+2]=d,r[y+3]=v+(Math.abs(d)+v)*qr,r[y+4]=m,r[y+5]=g+(Math.abs(m)+g)*qr}return r}shiftPrimitiveOffsets(e){let t=this._indirectBuffer;if(t)for(let r=0,i=t.length;r<i;r++)t[r]+=e;else{let r=this._roots;for(let i=0;i<r.length;i++){let s=r[i],n=new Uint32Array(s),l=new Uint16Array(s),a=s.byteLength/32;for(let p=0;p<a;p++){let u=8*p,c=2*u;k(c,l)&&(n[u+6]+=e)}}}}traverse(e,t=0){_t.setBVH(this,t),_t.traverse(e),_t.reset()}refit(){let e=this._roots;for(let t=0,r=e.length;t<r;t++){let i=e[t],s=new Uint32Array(i),n=new Uint16Array(i),l=new Float32Array(i),a=i.byteLength/32;for(let p=a-1;p>=0;p--){let u=p*8,c=u*2;if(k(c,n)){let h=K(u,s),d=ne(c,n);this.writePrimitiveRangeBounds(h,d,ei,0),l.set(ei,u)}else{let h=W(u),d=V(u,s);for(let m=0;m<3;m++){let x=l[h+m],v=l[h+m+3],g=l[d+m],y=l[d+m+3];l[u+m]=x<g?x:g,l[u+m+3]=v>y?v:y}}}}}getBoundingBox(e){return e.makeEmpty(),this._roots.forEach(r=>{te(0,new Float32Array(r),Rc),e.union(Rc)}),e}shapecast(e){let{boundsTraverseOrder:t,intersectsBounds:r,intersectsRange:i,intersectsPrimitive:s,scratchPrimitive:n,iterate:l}=e;if(i&&s){let c=i;i=(f,h,d,m,x)=>c(f,h,d,m,x)?!0:l(f,h,this,s,d,m,n)}else i||(s?i=(c,f,h,d)=>l(c,f,this,s,h,d,n):i=(c,f,h)=>h);let a=!1,p=0,u=this._roots;for(let c=0,f=u.length;c<f;c++){let h=u[c];if(a=Pc(this,c,r,i,t,p),a)break;p+=h.byteLength/32}return a}bvhcast(e,t,r){let{intersectsRanges:i}=r;return Bc(this,e,t,i)}};import{Box3 as wg}from"/vendor/three/0.187.0/three.webgpu.js";function Ec(){return typeof SharedArrayBuffer<"u"}import{BufferAttribute as xg}from"/vendor/three/0.187.0/three.webgpu.js";function nl(o){return o.index?o.index.count:o.attributes.position.count}function rr(o){return nl(o)/3}function yg(o,e=ArrayBuffer){return o>65535?new Uint32Array(new e(4*o)):new Uint16Array(new e(2*o))}function Mc(o,e){if(!o.index){let t=o.attributes.position.count,r=e.useSharedArrayBuffer?SharedArrayBuffer:ArrayBuffer,i=yg(t,r);o.setIndex(new xg(i,1));for(let s=0;s<t;s++)i[s]=s}}function vg(o,e,t){let r=nl(o)/t,i=e||o.drawRange,s=i.start/t,n=(i.start+i.count)/t,l=Math.max(0,s),a=Math.min(r,n)-l;return{offset:Math.floor(l),count:Math.floor(a)}}function bg(o,e){return o.groups.map(t=>({offset:t.start/e,count:t.count/e}))}function ol(o,e,t){let r=vg(o,e,t),i=bg(o,t);if(!i.length)return[r];let s=[],n=r.offset,l=r.offset+r.count,a=nl(o)/t,p=[];for(let f of i){let{offset:h,count:d}=f,m=h,x=isFinite(d)?d:a-h,v=h+x;m<l&&v>n&&(p.push({pos:Math.max(n,m),isStart:!0}),p.push({pos:Math.min(l,v),isStart:!1}))}p.sort((f,h)=>f.pos!==h.pos?f.pos-h.pos:f.type==="end"?-1:1);let u=0,c=null;for(let f of p){let h=f.pos;u!==0&&h!==c&&s.push({offset:c,count:h-c}),u+=f.isStart?1:-1,c=h}return s}function _g(o,e){let t=o[o.length-1],r=t.offset+t.count>2**16,i=o.reduce((p,u)=>p+u.count,0),s=r?4:2,n=e?new SharedArrayBuffer(i*s):new ArrayBuffer(i*s),l=r?new Uint32Array(n):new Uint16Array(n),a=0;for(let p=0;p<o.length;p++){let{offset:u,count:c}=o[p];for(let f=0;f<c;f++)l[a+f]=u+f;a+=c}return l}var kt=class extends ti{get indirect(){return!!this._indirectBuffer}get primitiveStride(){return null}get primitiveBufferStride(){return this.indirect?1:this.primitiveStride}set primitiveBufferStride(e){}get primitiveBuffer(){return this.indirect?this._indirectBuffer:this.geometry.index.array}set primitiveBuffer(e){}constructor(e,t={}){if(e.isBufferGeometry){if(e.index&&e.index.isInterleavedBufferAttribute)throw new Error("BVH: InterleavedBufferAttribute is not supported for the index attribute.")}else throw new Error("BVH: Only BufferGeometries are supported.");if(t.useSharedArrayBuffer&&!Ec())throw new Error("BVH: SharedArrayBuffer is not available.");super(),this.geometry=e,this.resolvePrimitiveIndex=t.indirect?r=>this._indirectBuffer[r]:r=>r,this.primitiveBuffer=null,this.primitiveBufferStride=null,this._indirectBuffer=null,t={...to,...t},t[Qt]||this.init(t)}init(e){let{geometry:t,primitiveStride:r}=this;if(e.indirect){let i=ol(t,e.range,r),s=_g(i,e.useSharedArrayBuffer);this._indirectBuffer=s}else Mc(t,e);super.init(e),!t.boundingBox&&e.setBoundingBox&&(t.boundingBox=this.getBoundingBox(new wg))}getRootRanges(e){return this.indirect?[{offset:0,count:this._indirectBuffer.length}]:ol(this.geometry,e,this.primitiveStride)}raycastObject3D(){throw new Error("BVH: raycastObject3D() not implemented")}};import{BufferAttribute as Qg,FrontSide as pp,Ray as Zg,Vector3 as mp,Matrix4 as Jg}from"/vendor/three/0.187.0/three.webgpu.js";import{Vector3 as ir,Matrix4 as kc,Line3 as Lc}from"/vendor/three/0.187.0/three.webgpu.js";import{Vector3 as Tg}from"/vendor/three/0.187.0/three.webgpu.js";var Ve=class{constructor(){this.min=1/0,this.max=-1/0}setFromPointsField(e,t){let r=1/0,i=-1/0;for(let s=0,n=e.length;s<n;s++){let a=e[s][t];r=a<r?a:r,i=a>i?a:i}this.min=r,this.max=i}setFromPoints(e,t){let r=1/0,i=-1/0;for(let s=0,n=t.length;s<n;s++){let l=t[s],a=e.dot(l);r=a<r?a:r,i=a>i?a:i}this.min=r,this.max=i}isSeparated(e){return this.min>e.max||e.min>this.max}};Ve.prototype.setFromBox=(function(){let o=new Tg;return function(t,r){let i=r.min,s=r.max,n=1/0,l=-1/0;for(let a=0;a<=1;a++)for(let p=0;p<=1;p++)for(let u=0;u<=1;u++){o.x=i.x*a+s.x*(1-a),o.y=i.y*p+s.y*(1-p),o.z=i.z*u+s.z*(1-u);let c=t.dot(o);n=Math.min(c,n),l=Math.max(c,l)}this.min=n,this.max=l}})();import{Triangle as Ag,Vector3 as at,Vector2 as Nc,Line3 as ri,Plane as Pg}from"/vendor/three/0.187.0/three.webgpu.js";import{Vector3 as vr,Vector2 as Sg,Plane as Ig,Line3 as $g}from"/vendor/three/0.187.0/three.webgpu.js";var Cg=(function(){let o=new vr,e=new vr,t=new vr;return function(i,s,n){let l=i.start,a=o,p=s.start,u=e;t.subVectors(l,p),o.subVectors(i.end,i.start),e.subVectors(s.end,s.start);let c=t.dot(u),f=u.dot(a),h=u.dot(u),d=t.dot(a),x=a.dot(a)*h-f*f,v,g;x!==0?v=(c*f-d*h)/x:v=0,g=(c+v*f)/h,n.x=v,n.y=g}})(),an=(function(){let o=new Sg,e=new vr,t=new vr;return function(i,s,n,l){Cg(i,s,o);let a=o.x,p=o.y;if(a>=0&&a<=1&&p>=0&&p<=1){i.at(a,n),s.at(p,l);return}else if(a>=0&&a<=1){p<0?s.at(0,l):s.at(1,l),i.closestPointToPoint(l,!0,n);return}else if(p>=0&&p<=1){a<0?i.at(0,n):i.at(1,n),s.closestPointToPoint(n,!0,l);return}else{let u;a<0?u=i.start:u=i.end;let c;p<0?c=s.start:c=s.end;let f=e,h=t;if(i.closestPointToPoint(c,!0,e),s.closestPointToPoint(u,!0,t),f.distanceToSquared(c)<=h.distanceToSquared(u)){n.copy(f),l.copy(c);return}else{n.copy(u),l.copy(h);return}}}})(),Dc=(function(){let o=new vr,e=new vr,t=new Ig,r=new $g;return function(s,n){let{radius:l,center:a}=s,{a:p,b:u,c}=n;if(r.start=p,r.end=u,r.closestPointToPoint(a,!0,o).distanceTo(a)<=l||(r.start=p,r.end=c,r.closestPointToPoint(a,!0,o).distanceTo(a)<=l)||(r.start=u,r.end=c,r.closestPointToPoint(a,!0,o).distanceTo(a)<=l))return!0;let m=n.getPlane(t);if(Math.abs(m.distanceToPoint(a))<=l){let v=m.projectPoint(a,e);if(n.containsPoint(v))return!0}return!1}})();var Bg=["x","y","z"],Lt=1e-15,Fc=Lt*Lt;function Je(o){return Math.abs(o)<Lt}var xe=class extends Ag{constructor(...e){super(...e),this.isExtendedTriangle=!0,this.satAxes=new Array(4).fill().map(()=>new at),this.satBounds=new Array(4).fill().map(()=>new Ve),this.points=[this.a,this.b,this.c],this.plane=new Pg,this.isDegenerateIntoSegment=!1,this.isDegenerateIntoPoint=!1,this.degenerateSegment=new ri,this.needsUpdate=!0}intersectsSphere(e){return Dc(e,this)}update(){let e=this.a,t=this.b,r=this.c,i=this.points,s=this.satAxes,n=this.satBounds,l=s[0],a=n[0];this.getNormal(l),a.setFromPoints(l,i);let p=s[1],u=n[1];p.subVectors(e,t),u.setFromPoints(p,i);let c=s[2],f=n[2];c.subVectors(t,r),f.setFromPoints(c,i);let h=s[3],d=n[3];h.subVectors(r,e),d.setFromPoints(h,i);let m=p.length(),x=c.length(),v=h.length();this.isDegenerateIntoPoint=!1,this.isDegenerateIntoSegment=!1,m<Lt?x<Lt||v<Lt?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(e),this.degenerateSegment.end.copy(r)):x<Lt?v<Lt?this.isDegenerateIntoPoint=!0:(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(t),this.degenerateSegment.end.copy(e)):v<Lt&&(this.isDegenerateIntoSegment=!0,this.degenerateSegment.start.copy(r),this.degenerateSegment.end.copy(t)),this.plane.setFromNormalAndCoplanarPoint(l,e),this.needsUpdate=!1}};xe.prototype.closestPointToSegment=(function(){let o=new at,e=new at,t=new ri;return function(i,s=null,n=null){this.needsUpdate&&this.update();let{start:l,end:a}=i,p=this.points,u,c=1/0,f=this.plane.distanceToPoint(l),h=this.plane.distanceToPoint(a);if(f*h<0&&this.plane.intersectLine(i,o)!==null&&this.containsPoint(o))return s&&s.copy(o),n&&n.copy(o),0;for(let d=0;d<3;d++){let m=(d+1)%3;t.start.copy(p[d]),t.end.copy(p[m]),an(t,i,o,e),u=o.distanceToSquared(e),u<c&&(c=u,s&&s.copy(o),n&&n.copy(e))}return this.closestPointToPoint(l,o),u=l.distanceToSquared(o),u<c&&(c=u,s&&s.copy(o),n&&n.copy(l)),this.closestPointToPoint(a,o),u=a.distanceToSquared(o),u<c&&(c=u,s&&s.copy(o),n&&n.copy(a)),Math.sqrt(c)}})();xe.prototype.intersectsTriangle=(function(){let o=new xe,e=new Ve,t=new Ve,r=new at,i=new at,s=new at,n=new at,l=new ri,a=new ri,p=new at,u=new Nc,c=new Nc;function f(y,w,b,T){let _=r;!y.isDegenerateIntoPoint&&!y.isDegenerateIntoSegment?_.copy(y.plane.normal):_.copy(w.plane.normal);let S=y.satBounds,A=y.satAxes;for(let C=1;C<4;C++){let B=S[C],R=A[C];if(e.setFromPoints(R,w.points),B.isSeparated(e)||(n.copy(_).cross(R),e.setFromPoints(n,y.points),t.setFromPoints(n,w.points),e.isSeparated(t)))return!1}let I=w.satBounds,P=w.satAxes;for(let C=1;C<4;C++){let B=I[C],R=P[C];if(e.setFromPoints(R,y.points),B.isSeparated(e)||(n.crossVectors(_,R),e.setFromPoints(n,y.points),t.setFromPoints(n,w.points),e.isSeparated(t)))return!1}return b&&(T||console.warn("ExtendedTriangle.intersectsTriangle: Triangles are coplanar which does not support an output edge. Setting edge to 0, 0, 0."),b.start.set(0,0,0),b.end.set(0,0,0)),!0}function h(y,w,b,T,_,S,A,I,P,C,B){let R=A/(A-I);C.x=T+(_-T)*R,B.start.subVectors(w,y).multiplyScalar(R).add(y),R=A/(A-P),C.y=T+(S-T)*R,B.end.subVectors(b,y).multiplyScalar(R).add(y)}function d(y,w,b,T,_,S,A,I,P,C,B){if(_>0)h(y.c,y.a,y.b,T,w,b,P,A,I,C,B);else if(S>0)h(y.b,y.a,y.c,b,w,T,I,A,P,C,B);else if(I*P>0||A!=0)h(y.a,y.b,y.c,w,b,T,A,I,P,C,B);else if(I!=0)h(y.b,y.a,y.c,b,w,T,I,A,P,C,B);else if(P!=0)h(y.c,y.a,y.b,T,w,b,P,A,I,C,B);else return!0;return!1}function m(y,w,b,T){let _=w.degenerateSegment,S=y.plane.distanceToPoint(_.start),A=y.plane.distanceToPoint(_.end);return Je(S)?Je(A)?f(y,w,b,T):(b&&(b.start.copy(_.start),b.end.copy(_.start)),y.containsPoint(_.start)):Je(A)?(b&&(b.start.copy(_.end),b.end.copy(_.end)),y.containsPoint(_.end)):y.plane.intersectLine(_,r)!=null?(b&&(b.start.copy(r),b.end.copy(r)),y.containsPoint(r)):!1}function x(y,w,b){let T=w.a;return Je(y.plane.distanceToPoint(T))&&y.containsPoint(T)?(b&&(b.start.copy(T),b.end.copy(T)),!0):!1}function v(y,w,b){let T=y.degenerateSegment,_=w.a;return T.closestPointToPoint(_,!0,r),_.distanceToSquared(r)<Fc?(b&&(b.start.copy(_),b.end.copy(_)),!0):!1}function g(y,w,b,T){if(y.isDegenerateIntoSegment)if(w.isDegenerateIntoSegment){let _=y.degenerateSegment,S=w.degenerateSegment,A=i,I=s;_.delta(A),S.delta(I);let P=r.subVectors(S.start,_.start),C=A.x*I.y-A.y*I.x;if(Je(C))return!1;let B=(P.x*I.y-P.y*I.x)/C,R=-(A.x*P.y-A.y*P.x)/C;if(B<0||B>1||R<0||R>1)return!1;let E=_.start.z+A.z*B,N=S.start.z+I.z*R;return Je(E-N)?(b&&(b.start.copy(_.start).addScaledVector(A,B),b.end.copy(_.start).addScaledVector(A,B)),!0):!1}else return w.isDegenerateIntoPoint?v(y,w,b):m(w,y,b,T);else{if(y.isDegenerateIntoPoint)return w.isDegenerateIntoPoint?w.a.distanceToSquared(y.a)<Fc?(b&&(b.start.copy(y.a),b.end.copy(y.a)),!0):!1:w.isDegenerateIntoSegment?v(w,y,b):x(w,y,b);if(w.isDegenerateIntoPoint)return x(y,w,b);if(w.isDegenerateIntoSegment)return m(y,w,b,T)}}return function(w,b=null,T=!1){this.needsUpdate&&this.update(),w.isExtendedTriangle?w.needsUpdate&&w.update():(o.copy(w),o.update(),w=o);let _=g(this,w,b,T);if(_!==void 0)return _;let S=this.plane,A=w.plane,I=A.distanceToPoint(this.a),P=A.distanceToPoint(this.b),C=A.distanceToPoint(this.c);Je(I)&&(I=0),Je(P)&&(P=0),Je(C)&&(C=0);let B=I*P,R=I*C;if(B>0&&R>0)return!1;let E=S.distanceToPoint(w.a),N=S.distanceToPoint(w.b),M=S.distanceToPoint(w.c);Je(E)&&(E=0),Je(N)&&(N=0),Je(M)&&(M=0);let oe=E*N,we=E*M;if(oe>0&&we>0)return!1;i.copy(S.normal),s.copy(A.normal);let ze=i.cross(s),G=0,se=Math.abs(ze.x),He=Math.abs(ze.y);He>se&&(se=He,G=1),Math.abs(ze.z)>se&&(G=2);let he=Bg[G],ke=this.a[he],O=this.b[he],J=this.c[he],U=w.a[he],j=w.b[he],Ft=w.c[he];if(d(this,ke,O,J,B,R,I,P,C,u,l))return f(this,w,b,T);if(d(w,U,j,Ft,oe,we,E,N,M,c,a))return f(this,w,b,T);if(u.y<u.x){let Ze=u.y;u.y=u.x,u.x=Ze,p.copy(l.start),l.start.copy(l.end),l.end.copy(p)}if(c.y<c.x){let Ze=c.y;c.y=c.x,c.x=Ze,p.copy(a.start),a.start.copy(a.end),a.end.copy(p)}return u.y<c.x||c.y<u.x?!1:(b&&(c.x>u.x?b.start.copy(a.start):b.start.copy(l.start),c.y<u.y?b.end.copy(a.end):b.end.copy(l.end)),!0)}})();xe.prototype.distanceToPoint=(function(){let o=new at;return function(t){return this.closestPointToPoint(t,o),t.distanceTo(o)}})();xe.prototype.distanceToTriangle=(function(){let o=new at,e=new at,t=["a","b","c"],r=new ri,i=new ri;return function(n,l=null,a=null){let p=l||a?r:null;if(this.intersectsTriangle(n,p,!0))return(l||a)&&(l&&p.getCenter(l),a&&p.getCenter(a)),0;let u=1/0;for(let c=0;c<3;c++){let f,h=t[c],d=n[h];this.closestPointToPoint(d,o),f=d.distanceToSquared(o),f<u&&(u=f,l&&l.copy(o),a&&a.copy(d));let m=this[h];n.closestPointToPoint(m,o),f=m.distanceToSquared(o),f<u&&(u=f,l&&l.copy(m),a&&a.copy(o))}for(let c=0;c<3;c++){let f=t[c],h=t[(c+1)%3];r.set(this[f],this[h]);for(let d=0;d<3;d++){let m=t[d],x=t[(d+1)%3];i.set(n[m],n[x]),an(r,i,o,e);let v=o.distanceToSquared(e);v<u&&(u=v,l&&l.copy(o),a&&a.copy(e))}}return Math.sqrt(u)}})();var de=class{constructor(e,t,r){this.isOrientedBox=!0,this.min=new ir,this.max=new ir,this.matrix=new kc,this.invMatrix=new kc,this.points=new Array(8).fill().map(()=>new ir),this.satAxes=new Array(3).fill().map(()=>new ir),this.satBounds=new Array(3).fill().map(()=>new Ve),this.alignedSatBounds=new Array(3).fill().map(()=>new Ve),this.needsUpdate=!1,e&&this.min.copy(e),t&&this.max.copy(t),r&&this.matrix.copy(r)}set(e,t,r){this.min.copy(e),this.max.copy(t),this.matrix.copy(r),this.needsUpdate=!0}copy(e){this.min.copy(e.min),this.max.copy(e.max),this.matrix.copy(e.matrix),this.needsUpdate=!0}};de.prototype.update=(function(){return function(){let e=this.matrix,t=this.min,r=this.max,i=this.points;for(let p=0;p<=1;p++)for(let u=0;u<=1;u++)for(let c=0;c<=1;c++){let f=1*p|2*u|4*c,h=i[f];h.x=p?r.x:t.x,h.y=u?r.y:t.y,h.z=c?r.z:t.z,h.applyMatrix4(e)}let s=this.satBounds,n=this.satAxes,l=i[0];for(let p=0;p<3;p++){let u=n[p],c=s[p],f=1<<p,h=i[f];u.subVectors(l,h),c.setFromPoints(u,i)}let a=this.alignedSatBounds;a[0].setFromPointsField(i,"x"),a[1].setFromPointsField(i,"y"),a[2].setFromPointsField(i,"z"),this.invMatrix.copy(this.matrix).invert(),this.needsUpdate=!1}})();de.prototype.intersectsBox=(function(){let o=new Ve;return function(t){this.needsUpdate&&this.update();let r=t.min,i=t.max,s=this.satBounds,n=this.satAxes,l=this.alignedSatBounds;if(o.min=r.x,o.max=i.x,l[0].isSeparated(o)||(o.min=r.y,o.max=i.y,l[1].isSeparated(o))||(o.min=r.z,o.max=i.z,l[2].isSeparated(o)))return!1;for(let a=0;a<3;a++){let p=n[a],u=s[a];if(o.setFromBox(p,t),u.isSeparated(o))return!1}return!0}})();de.prototype.intersectsTriangle=(function(){let o=new xe,e=new Array(3),t=new Ve,r=new Ve,i=new ir;return function(n){this.needsUpdate&&this.update(),n.isExtendedTriangle?n.needsUpdate&&n.update():(o.copy(n),o.update(),n=o);let l=this.satBounds,a=this.satAxes;e[0]=n.a,e[1]=n.b,e[2]=n.c;for(let f=0;f<3;f++){let h=l[f],d=a[f];if(t.setFromPoints(d,e),h.isSeparated(t))return!1}let p=n.satBounds,u=n.satAxes,c=this.points;for(let f=0;f<3;f++){let h=p[f],d=u[f];if(t.setFromPoints(d,c),h.isSeparated(t))return!1}for(let f=0;f<3;f++){let h=a[f];for(let d=0;d<4;d++){let m=u[d];if(i.crossVectors(h,m),t.setFromPoints(i,e),r.setFromPoints(i,c),t.isSeparated(r))return!1}}return!0}})();de.prototype.closestPointToPoint=(function(){return function(e,t){return this.needsUpdate&&this.update(),t.copy(e).applyMatrix4(this.invMatrix).clamp(this.min,this.max).applyMatrix4(this.matrix),t}})();de.prototype.distanceToPoint=(function(){let o=new ir;return function(t){return this.closestPointToPoint(t,o),t.distanceTo(o)}})();de.prototype.distanceToBox=(function(){let o=["x","y","z"],e=new Array(12).fill().map(()=>new Lc),t=new Array(12).fill().map(()=>new Lc),r=new ir,i=new ir;return function(n,l=0,a=null,p=null){if(this.needsUpdate&&this.update(),this.intersectsBox(n))return(a||p)&&(n.getCenter(i),this.closestPointToPoint(i,r),n.closestPointToPoint(r,i),a&&a.copy(r),p&&p.copy(i)),0;let u=l*l,c=n.min,f=n.max,h=this.points,d=1/0;for(let x=0;x<8;x++){let v=h[x];i.copy(v).clamp(c,f);let g=v.distanceToSquared(i);if(g<d&&(d=g,a&&a.copy(v),p&&p.copy(i),g<u))return Math.sqrt(g)}let m=0;for(let x=0;x<3;x++)for(let v=0;v<=1;v++)for(let g=0;g<=1;g++){let y=(x+1)%3,w=(x+2)%3,b=v<<y|g<<w,T=1<<x|v<<y|g<<w,_=h[b],S=h[T];e[m].set(_,S);let I=o[x],P=o[y],C=o[w],B=t[m],R=B.start,E=B.end;R[I]=c[I],R[P]=v?c[P]:f[P],R[C]=g?c[C]:f[C],E[I]=f[I],E[P]=v?c[P]:f[P],E[C]=g?c[C]:f[C],m++}for(let x=0;x<=1;x++)for(let v=0;v<=1;v++)for(let g=0;g<=1;g++){i.x=x?f.x:c.x,i.y=v?f.y:c.y,i.z=g?f.z:c.z,this.closestPointToPoint(i,r);let y=i.distanceToSquared(r);if(y<d&&(d=y,a&&a.copy(r),p&&p.copy(i),y<u))return Math.sqrt(y)}for(let x=0;x<12;x++){let v=e[x];for(let g=0;g<12;g++){let y=t[g];an(v,y,r,i);let w=r.distanceToSquared(i);if(w<d&&(d=w,a&&a.copy(r),p&&p.copy(i),w<u))return Math.sqrt(w)}}return Math.sqrt(d)}})();var sl=class extends Jt{constructor(){super(()=>new xe)}},De=new sl;import{Vector3 as Oc}from"/vendor/three/0.187.0/three.webgpu.js";var ln=new Oc,al=new Oc;function Uc(o,e,t={},r=0,i=1/0){let s=r*r,n=i*i,l=1/0,a=null;if(o.shapecast({boundsTraverseOrder:u=>(ln.copy(e).clamp(u.min,u.max),ln.distanceToSquared(e)),intersectsBounds:(u,c,f)=>f<l&&f<n,intersectsTriangle:(u,c)=>{u.closestPointToPoint(e,ln);let f=e.distanceToSquared(ln);return f<l&&(al.copy(ln),l=f,a=c),f<s}}),l===1/0)return null;let p=Math.sqrt(l);return t.point?t.point.copy(al):t.point=al.clone(),t.distance=p,t.faceIndex=a,t}import{Vector3 as Tt,Vector2 as pn,Triangle as un,DoubleSide as Rg,BackSide as Eg,REVISION as qc}from"/vendor/three/0.187.0/three.webgpu.js";var lo=parseInt(qc)>=169,Mg=parseInt(qc)<=161,br=new Tt,wr=new Tt,_r=new Tt,uo=new pn,co=new pn,po=new pn,zc=new Tt,Hc=new Tt,Wc=new Tt,cn=new Tt;function Dg(o,e,t,r,i,s,n,l){let a;if(s===Eg?a=o.intersectTriangle(r,t,e,!0,i):a=o.intersectTriangle(e,t,r,s!==Rg,i),a===null)return null;let p=o.origin.distanceTo(i);return p<n||p>l?null:{distance:p,point:i.clone()}}function Vc(o,e,t,r,i,s,n,l,a,p,u){br.fromBufferAttribute(e,s),wr.fromBufferAttribute(e,n),_r.fromBufferAttribute(e,l);let c=Dg(o,br,wr,_r,cn,a,p,u);if(c){if(r){uo.fromBufferAttribute(r,s),co.fromBufferAttribute(r,n),po.fromBufferAttribute(r,l),c.uv=new pn;let h=un.getInterpolation(cn,br,wr,_r,uo,co,po,c.uv);lo||(c.uv=h)}if(i){uo.fromBufferAttribute(i,s),co.fromBufferAttribute(i,n),po.fromBufferAttribute(i,l),c.uv1=new pn;let h=un.getInterpolation(cn,br,wr,_r,uo,co,po,c.uv1);lo||(c.uv1=h),Mg&&(c.uv2=c.uv1)}if(t){zc.fromBufferAttribute(t,s),Hc.fromBufferAttribute(t,n),Wc.fromBufferAttribute(t,l),c.normal=new Tt;let h=un.getInterpolation(cn,br,wr,_r,zc,Hc,Wc,c.normal);c.normal.dot(o.direction)>0&&c.normal.multiplyScalar(-1),lo||(c.normal=h)}let f={a:s,b:n,c:l,normal:new Tt,materialIndex:0};if(un.getNormal(br,wr,_r,f.normal),c.face=f,c.faceIndex=s,lo){let h=new Tt;un.getBarycoord(cn,br,wr,_r,h),c.barycoord=h}}return c}function Gc(o){return o&&o.isMaterial?o.side:o}function ii(o,e,t,r,i,s,n){let l=r*3,a=l+0,p=l+1,u=l+2,{index:c,groups:f}=o;o.index&&(a=c.getX(a),p=c.getX(p),u=c.getX(u));let{position:h,normal:d,uv:m,uv1:x}=o.attributes;if(Array.isArray(e)){let v=r*3;for(let g=0,y=f.length;g<y;g++){let{start:w,count:b,materialIndex:T}=f[g];if(v>=w&&v<w+b){let _=Gc(e[T]),S=Vc(t,h,d,m,x,a,p,u,_,s,n);if(S)if(S.faceIndex=r,S.face.materialIndex=T,i)i.push(S);else return S}}}else{let v=Gc(e),g=Vc(t,h,d,m,x,a,p,u,v,s,n);if(g)if(g.faceIndex=r,g.face.materialIndex=0,i)i.push(g);else return g}return null}import{Vector2 as W_,Vector3 as V_,Triangle as G_}from"/vendor/three/0.187.0/three.webgpu.js";function ae(o,e,t,r){let i=o.a,s=o.b,n=o.c,l=e,a=e+1,p=e+2;t&&(l=t.getX(l),a=t.getX(a),p=t.getX(p)),i.x=r.getX(l),i.y=r.getY(l),i.z=r.getZ(l),s.x=r.getX(a),s.y=r.getY(a),s.z=r.getZ(a),n.x=r.getX(p),n.y=r.getY(p),n.z=r.getZ(p)}function Xc(o,e,t,r,i,s,n,l){let{geometry:a,_indirectBuffer:p}=o;for(let u=r,c=r+i;u<c;u++)ii(a,e,t,u,s,n,l)}function Yc(o,e,t,r,i,s,n){let{geometry:l,_indirectBuffer:a}=o,p=1/0,u=null;for(let c=r,f=r+i;c<f;c++){let h;h=ii(l,e,t,c,null,s,n),h&&h.distance<p&&(u=h,p=h.distance)}return u}function jc(o,e,t,r,i,s,n){let{geometry:l}=t,{index:a}=l,p=l.attributes.position;for(let u=o,c=e+o;u<c;u++){let f;if(f=u,ae(n,f*3,a,p),n.needsUpdate=!0,r(n,f,i,s))return!0}return!1}function Kc(o,e=null){e&&Array.isArray(e)&&(e=new Set(e));let t=o.geometry,r=t.index?t.index.array:null,i=t.attributes.position,s,n,l,a,p=0,u=o._roots;for(let f=0,h=u.length;f<h;f++)s=u[f],n=new Uint32Array(s),l=new Uint16Array(s),a=new Float32Array(s),c(0,p),p+=s.byteLength;function c(f,h,d=!1){let m=f*2;if(k(m,l)){let x=K(f,n),v=ne(m,l),g=1/0,y=1/0,w=1/0,b=-1/0,T=-1/0,_=-1/0;for(let S=3*x,A=3*(x+v);S<A;S++){let I=r[S],P=i.getX(I),C=i.getY(I),B=i.getZ(I);P<g&&(g=P),P>b&&(b=P),C<y&&(y=C),C>T&&(T=C),B<w&&(w=B),B>_&&(_=B)}return a[f+0]!==g||a[f+1]!==y||a[f+2]!==w||a[f+3]!==b||a[f+4]!==T||a[f+5]!==_?(a[f+0]=g,a[f+1]=y,a[f+2]=w,a[f+3]=b,a[f+4]=T,a[f+5]=_,!0):!1}else{let x=W(f),v=V(f,n),g=d,y=!1,w=!1;if(e){if(!g){let I=x/8+h/32,P=v/8+h/32;y=e.has(I),w=e.has(P),g=!y&&!w}}else y=!0,w=!0;let b=g||y,T=g||w,_=!1;b&&(_=c(x,h,g));let S=!1;T&&(S=c(v,h,g));let A=_||S;if(A)for(let I=0;I<3;I++){let P=x+I,C=v+I,B=a[P],R=a[P+3],E=a[C],N=a[C+3];a[f+I]=B<E?B:E,a[f+I+3]=R>N?R:N}return A}}}function Ae(o,e,t,r,i){let s,n,l,a,p,u,c=1/t.direction.x,f=1/t.direction.y,h=1/t.direction.z,d=t.origin.x,m=t.origin.y,x=t.origin.z,v=e[o],g=e[o+3],y=e[o+1],w=e[o+3+1],b=e[o+2],T=e[o+3+2];return c>=0?(s=(v-d)*c,n=(g-d)*c):(s=(g-d)*c,n=(v-d)*c),f>=0?(l=(y-m)*f,a=(w-m)*f):(l=(w-m)*f,a=(y-m)*f),s>a||l>n||((l>s||isNaN(s))&&(s=l),(a<n||isNaN(n))&&(n=a),h>=0?(p=(b-x)*h,u=(T-x)*h):(p=(T-x)*h,u=(b-x)*h),s>u||p>n)?!1:((p>s||s!==s)&&(s=p),(u<n||n!==n)&&(n=u),s<=i&&n>=r)}function Qc(o,e,t,r,i,s,n,l){let{geometry:a,_indirectBuffer:p}=o;for(let u=r,c=r+i;u<c;u++){let f=p?p[u]:u;ii(a,e,t,f,s,n,l)}}function Zc(o,e,t,r,i,s,n){let{geometry:l,_indirectBuffer:a}=o,p=1/0,u=null;for(let c=r,f=r+i;c<f;c++){let h;h=ii(l,e,t,a?a[c]:c,null,s,n),h&&h.distance<p&&(u=h,p=h.distance)}return u}function Jc(o,e,t,r,i,s,n){let{geometry:l}=t,{index:a}=l,p=l.attributes.position;for(let u=o,c=e+o;u<c;u++){let f;if(f=t.resolveTriangleIndex(u),ae(n,f*3,a,p),n.needsUpdate=!0,r(n,f,i,s))return!0}return!1}function ep(o,e,t,r,i,s,n){L.setBuffer(o._roots[e]),Ae(0,L.float32Array,r,s,n)&&ll(0,o,t,r,i,s,n),L.clearBuffer()}function ll(o,e,t,r,i,s,n){let{float32Array:l,uint16Array:a,uint32Array:p}=L,u=o*2;if(k(u,a)){let f=K(o,p),h=ne(u,a);Xc(e,t,r,f,h,i,s,n)}else{let f=W(o);Ae(f,l,r,s,n)&&ll(f,e,t,r,i,s,n);let h=V(o,p);Ae(h,l,r,s,n)&&ll(h,e,t,r,i,s,n)}}var Ng=["x","y","z"];function tp(o,e,t,r,i,s){L.setBuffer(o._roots[e]);let n=null;return Ae(0,L.float32Array,r,i,s)&&(n=ul(0,o,t,r,i,s)),L.clearBuffer(),n}function ul(o,e,t,r,i,s){let{float32Array:n,uint16Array:l,uint32Array:a}=L,p=o*2;if(k(p,l)){let c=K(o,a),f=ne(p,l);return Yc(e,t,r,c,f,i,s)}else{let c=Xr(o,a),f=Ng[c],d=r.direction[f]>=0,m,x;d?(m=W(o),x=V(o,a)):(m=V(o,a),x=W(o));let g=Ae(m,n,r,i,s)?ul(m,e,t,r,i,s):null;if(g){let b=g.point[f];if(d?b<=n[x+c]:b>=n[x+c+3])return g}let w=Ae(x,n,r,i,s)?ul(x,e,t,r,i,s):null;return g&&w?g.distance<=w.distance?g:w:g||w||null}}import{Box3 as Fg,Matrix4 as kg}from"/vendor/three/0.187.0/three.webgpu.js";var fo=new Fg,ni=new xe,oi=new xe,fn=new kg,rp=new de,ho=new de;function ip(o,e,t,r){L.setBuffer(o._roots[e]);let i=cl(0,o,t,r);return L.clearBuffer(),i}function cl(o,e,t,r,i=null){let{float32Array:s,uint16Array:n,uint32Array:l}=L,a=o*2;if(i===null&&(t.boundingBox||t.computeBoundingBox(),rp.set(t.boundingBox.min,t.boundingBox.max,r),i=rp),k(a,n)){let u=e.geometry,c=u.index,f=u.attributes.position,h=t.index,d=t.attributes.position,m=K(o,l),x=ne(a,n);if(fn.copy(r).invert(),t.boundsTree)return te(o,s,ho),ho.matrix.copy(fn),ho.needsUpdate=!0,t.boundsTree.shapecast({intersectsBounds:g=>ho.intersectsBox(g),intersectsTriangle:g=>{g.a.applyMatrix4(r),g.b.applyMatrix4(r),g.c.applyMatrix4(r),g.needsUpdate=!0;for(let y=m*3,w=(x+m)*3;y<w;y+=3)if(ae(oi,y,c,f),oi.needsUpdate=!0,g.intersectsTriangle(oi))return!0;return!1}});{let v=rr(t);for(let g=m*3,y=(x+m)*3;g<y;g+=3){ae(ni,g,c,f),ni.a.applyMatrix4(fn),ni.b.applyMatrix4(fn),ni.c.applyMatrix4(fn),ni.needsUpdate=!0;for(let w=0,b=v*3;w<b;w+=3)if(ae(oi,w,h,d),oi.needsUpdate=!0,ni.intersectsTriangle(oi))return!0}}}else{let u=W(o),c=V(o,l);return te(u,s,fo),!!(i.intersectsBox(fo)&&cl(u,e,t,r,i)||(te(c,s,fo),i.intersectsBox(fo)&&cl(c,e,t,r,i)))}}import{Matrix4 as Lg,Vector3 as go}from"/vendor/three/0.187.0/three.webgpu.js";var mo=new Lg,pl=new de,dn=new de,Og=new go,Ug=new go,zg=new go,Hg=new go;function np(o,e,t,r={},i={},s=0,n=1/0){e.boundingBox||e.computeBoundingBox(),pl.set(e.boundingBox.min,e.boundingBox.max,t),pl.needsUpdate=!0;let l=o.geometry,a=l.attributes.position,p=l.index,u=e.attributes.position,c=e.index,f=De.getPrimitive(),h=De.getPrimitive(),d=Og,m=Ug,x=null,v=null;i&&(x=zg,v=Hg);let g=1/0,y=null,w=null;return mo.copy(t).invert(),dn.matrix.copy(mo),o.shapecast({boundsTraverseOrder:b=>pl.distanceToBox(b),intersectsBounds:(b,T,_)=>_<g&&_<n?(T&&(dn.min.copy(b.min),dn.max.copy(b.max),dn.needsUpdate=!0),!0):!1,intersectsRange:(b,T)=>{if(e.boundsTree)return e.boundsTree.shapecast({boundsTraverseOrder:S=>dn.distanceToBox(S),intersectsBounds:(S,A,I)=>I<g&&I<n,intersectsRange:(S,A)=>{for(let I=S,P=S+A;I<P;I++){ae(h,3*I,c,u),h.a.applyMatrix4(t),h.b.applyMatrix4(t),h.c.applyMatrix4(t),h.needsUpdate=!0;for(let C=b,B=b+T;C<B;C++){ae(f,3*C,p,a),f.needsUpdate=!0;let R=f.distanceToTriangle(h,d,x);if(R<g&&(m.copy(d),v&&v.copy(x),g=R,y=C,w=I),R<s)return!0}}}});{let _=rr(e);for(let S=0,A=_;S<A;S++){ae(h,3*S,c,u),h.a.applyMatrix4(t),h.b.applyMatrix4(t),h.c.applyMatrix4(t),h.needsUpdate=!0;for(let I=b,P=b+T;I<P;I++){ae(f,3*I,p,a),f.needsUpdate=!0;let C=f.distanceToTriangle(h,d,x);if(C<g&&(m.copy(d),v&&v.copy(x),g=C,y=I,w=S),C<s)return!0}}}}}),De.releasePrimitive(f),De.releasePrimitive(h),g===1/0?null:(r.point?r.point.copy(m):r.point=m.clone(),r.distance=g,r.faceIndex=y,i&&(i.point?i.point.copy(v):i.point=v.clone(),i.point.applyMatrix4(mo),m.applyMatrix4(mo),i.distance=m.sub(i.point).length(),i.faceIndex=w),r)}function op(o,e=null){e&&Array.isArray(e)&&(e=new Set(e));let t=o.geometry,r=t.index?t.index.array:null,i=t.attributes.position,s,n,l,a,p=0,u=o._roots;for(let f=0,h=u.length;f<h;f++)s=u[f],n=new Uint32Array(s),l=new Uint16Array(s),a=new Float32Array(s),c(0,p),p+=s.byteLength;function c(f,h,d=!1){let m=f*2;if(k(m,l)){let x=K(f,n),v=ne(m,l),g=1/0,y=1/0,w=1/0,b=-1/0,T=-1/0,_=-1/0;for(let S=x,A=x+v;S<A;S++){let I=3*o.resolveTriangleIndex(S);for(let P=0;P<3;P++){let C=I+P;C=r?r[C]:C;let B=i.getX(C),R=i.getY(C),E=i.getZ(C);B<g&&(g=B),B>b&&(b=B),R<y&&(y=R),R>T&&(T=R),E<w&&(w=E),E>_&&(_=E)}}return a[f+0]!==g||a[f+1]!==y||a[f+2]!==w||a[f+3]!==b||a[f+4]!==T||a[f+5]!==_?(a[f+0]=g,a[f+1]=y,a[f+2]=w,a[f+3]=b,a[f+4]=T,a[f+5]=_,!0):!1}else{let x=W(f),v=V(f,n),g=d,y=!1,w=!1;if(e){if(!g){let I=x/8+h/32,P=v/8+h/32;y=e.has(I),w=e.has(P),g=!y&&!w}}else y=!0,w=!0;let b=g||y,T=g||w,_=!1;b&&(_=c(x,h,g));let S=!1;T&&(S=c(v,h,g));let A=_||S;if(A)for(let I=0;I<3;I++){let P=x+I,C=v+I,B=a[P],R=a[P+3],E=a[C],N=a[C+3];a[f+I]=B<E?B:E,a[f+I+3]=R>N?R:N}return A}}}function sp(o,e,t,r,i,s,n){L.setBuffer(o._roots[e]),Ae(0,L.float32Array,r,s,n)&&fl(0,o,t,r,i,s,n),L.clearBuffer()}function fl(o,e,t,r,i,s,n){let{float32Array:l,uint16Array:a,uint32Array:p}=L,u=o*2;if(k(u,a)){let f=K(o,p),h=ne(u,a);Qc(e,t,r,f,h,i,s,n)}else{let f=W(o);Ae(f,l,r,s,n)&&fl(f,e,t,r,i,s,n);let h=V(o,p);Ae(h,l,r,s,n)&&fl(h,e,t,r,i,s,n)}}var Wg=["x","y","z"];function ap(o,e,t,r,i,s){L.setBuffer(o._roots[e]);let n=null;return Ae(0,L.float32Array,r,i,s)&&(n=dl(0,o,t,r,i,s)),L.clearBuffer(),n}function dl(o,e,t,r,i,s){let{float32Array:n,uint16Array:l,uint32Array:a}=L,p=o*2;if(k(p,l)){let c=K(o,a),f=ne(p,l);return Zc(e,t,r,c,f,i,s)}else{let c=Xr(o,a),f=Wg[c],d=r.direction[f]>=0,m,x;d?(m=W(o),x=V(o,a)):(m=V(o,a),x=W(o));let g=Ae(m,n,r,i,s)?dl(m,e,t,r,i,s):null;if(g){let b=g.point[f];if(d?b<=n[x+c]:b>=n[x+c+3])return g}let w=Ae(x,n,r,i,s)?dl(x,e,t,r,i,s):null;return g&&w?g.distance<=w.distance?g:w:g||w||null}}import{Box3 as Vg,Matrix4 as Gg}from"/vendor/three/0.187.0/three.webgpu.js";var xo=new Vg,si=new xe,ai=new xe,hn=new Gg,lp=new de,yo=new de;function up(o,e,t,r){L.setBuffer(o._roots[e]);let i=hl(0,o,t,r);return L.clearBuffer(),i}function hl(o,e,t,r,i=null){let{float32Array:s,uint16Array:n,uint32Array:l}=L,a=o*2;if(i===null&&(t.boundingBox||t.computeBoundingBox(),lp.set(t.boundingBox.min,t.boundingBox.max,r),i=lp),k(a,n)){let u=e.geometry,c=u.index,f=u.attributes.position,h=t.index,d=t.attributes.position,m=K(o,l),x=ne(a,n);if(hn.copy(r).invert(),t.boundsTree)return te(o,s,yo),yo.matrix.copy(hn),yo.needsUpdate=!0,t.boundsTree.shapecast({intersectsBounds:g=>yo.intersectsBox(g),intersectsTriangle:g=>{g.a.applyMatrix4(r),g.b.applyMatrix4(r),g.c.applyMatrix4(r),g.needsUpdate=!0;for(let y=m,w=x+m;y<w;y++)if(ae(ai,3*e.resolveTriangleIndex(y),c,f),ai.needsUpdate=!0,g.intersectsTriangle(ai))return!0;return!1}});{let v=rr(t);for(let g=m,y=x+m;g<y;g++){let w=e.resolveTriangleIndex(g);ae(si,3*w,c,f),si.a.applyMatrix4(hn),si.b.applyMatrix4(hn),si.c.applyMatrix4(hn),si.needsUpdate=!0;for(let b=0,T=v*3;b<T;b+=3)if(ae(ai,b,h,d),ai.needsUpdate=!0,si.intersectsTriangle(ai))return!0}}}else{let u=W(o),c=V(o,l);return te(u,s,xo),!!(i.intersectsBox(xo)&&hl(u,e,t,r,i)||(te(c,s,xo),i.intersectsBox(xo)&&hl(c,e,t,r,i)))}}import{Matrix4 as qg,Vector3 as bo}from"/vendor/three/0.187.0/three.webgpu.js";var vo=new qg,ml=new de,mn=new de,Xg=new bo,Yg=new bo,jg=new bo,Kg=new bo;function cp(o,e,t,r={},i={},s=0,n=1/0){e.boundingBox||e.computeBoundingBox(),ml.set(e.boundingBox.min,e.boundingBox.max,t),ml.needsUpdate=!0;let l=o.geometry,a=l.attributes.position,p=l.index,u=e.attributes.position,c=e.index,f=De.getPrimitive(),h=De.getPrimitive(),d=Xg,m=Yg,x=null,v=null;i&&(x=jg,v=Kg);let g=1/0,y=null,w=null;return vo.copy(t).invert(),mn.matrix.copy(vo),o.shapecast({boundsTraverseOrder:b=>ml.distanceToBox(b),intersectsBounds:(b,T,_)=>_<g&&_<n?(T&&(mn.min.copy(b.min),mn.max.copy(b.max),mn.needsUpdate=!0),!0):!1,intersectsRange:(b,T)=>{if(e.boundsTree){let _=e.boundsTree;return _.shapecast({boundsTraverseOrder:S=>mn.distanceToBox(S),intersectsBounds:(S,A,I)=>I<g&&I<n,intersectsRange:(S,A)=>{for(let I=S,P=S+A;I<P;I++){let C=_.resolveTriangleIndex(I);ae(h,3*C,c,u),h.a.applyMatrix4(t),h.b.applyMatrix4(t),h.c.applyMatrix4(t),h.needsUpdate=!0;for(let B=b,R=b+T;B<R;B++){let E=o.resolveTriangleIndex(B);ae(f,3*E,p,a),f.needsUpdate=!0;let N=f.distanceToTriangle(h,d,x);if(N<g&&(m.copy(d),v&&v.copy(x),g=N,y=B,w=I),N<s)return!0}}}})}else{let _=rr(e);for(let S=0,A=_;S<A;S++){ae(h,3*S,c,u),h.a.applyMatrix4(t),h.b.applyMatrix4(t),h.c.applyMatrix4(t),h.needsUpdate=!0;for(let I=b,P=b+T;I<P;I++){let C=o.resolveTriangleIndex(I);ae(f,3*C,p,a),f.needsUpdate=!0;let B=f.distanceToTriangle(h,d,x);if(B<g&&(m.copy(d),v&&v.copy(x),g=B,y=I,w=S),B<s)return!0}}}}}),De.releasePrimitive(f),De.releasePrimitive(h),g===1/0?null:(r.point?r.point.copy(m):r.point=m.clone(),r.distance=g,r.faceIndex=y,i&&(i.point?i.point.copy(v):i.point=v.clone(),i.point.applyMatrix4(vo),m.applyMatrix4(vo),i.distance=m.sub(i.point).length(),i.faceIndex=w),r)}function gl(o,e,t){return o===null?null:(o.point.applyMatrix4(e.matrixWorld),o.distance=o.point.distanceTo(t.ray.origin),o.object=e,o)}var wo=new de,_o=new Zg,fp=new mp,dp=new Jg,hp=new mp,xl=["getX","getY","getZ"],Ot=class o extends kt{static serialize(e,t={}){t={cloneBuffers:!0,...t};let r=e.geometry,i=e._roots,s=e._indirectBuffer,n=r.getIndex(),l={version:1,roots:null,index:null,indirectBuffer:null};return t.cloneBuffers?(l.roots=i.map(a=>a.slice()),l.index=n?n.array.slice():null,l.indirectBuffer=s?s.slice():null):(l.roots=i,l.index=n?n.array:null,l.indirectBuffer=s),l}static deserialize(e,t,r={}){r={setIndex:!0,indirect:!!e.indirectBuffer,...r};let{index:i,roots:s,indirectBuffer:n}=e;e.version||(console.warn("MeshBVH.deserialize: Serialization format has been changed and will be fixed up. It is recommended to regenerate any stored serialized data."),a(s));let l=new o(t,{...r,[Qt]:!0});if(l._roots=s,l._indirectBuffer=n||null,r.setIndex){let p=t.getIndex();if(p===null){let u=new Qg(e.index,1,!1);t.setIndex(u)}else p.array!==i&&(p.array.set(i),p.needsUpdate=!0)}return l;function a(p){for(let u=0;u<p.length;u++){let c=p[u],f=new Uint32Array(c),h=new Uint16Array(c);for(let d=0,m=c.byteLength/32;d<m;d++){let x=8*d,v=2*x;k(v,h)||(f[x+6]=f[x+6]/8-d)}}}}get primitiveStride(){return 3}get resolveTriangleIndex(){return this.resolvePrimitiveIndex}constructor(e,t={}){t.maxLeafTris&&(console.warn('MeshBVH: "maxLeafTris" option has been deprecated. Use "targetLeafSize", instead.'),t={...t,targetLeafSize:t.maxLeafTris}),super(e,t)}shiftTriangleOffsets(e){return super.shiftPrimitiveOffsets(e)}writePrimitiveBounds(e,t,r){let i=this.geometry,s=this._indirectBuffer,n=i.attributes.position,l=i.index?i.index.array:null,p=(s?s[e]:e)*3,u=p+0,c=p+1,f=p+2;l&&(u=l[u],c=l[c],f=l[f]);for(let h=0;h<3;h++){let d=n[xl[h]](u),m=n[xl[h]](c),x=n[xl[h]](f),v=d;m<v&&(v=m),x<v&&(v=x);let g=d;m>g&&(g=m),x>g&&(g=x),t[r+h]=v,t[r+h+3]=g}return t}computePrimitiveBounds(e,t,r){let i=this.geometry,s=this._indirectBuffer,n=i.attributes.position,l=i.index?i.index.array:null,a=n.normalized;if(e<0||t+e-r.offset>r.length/6)throw new Error("MeshBVH: compute triangle bounds range is invalid.");let p=n.array,u=n.offset||0,c=3;n.isInterleavedBufferAttribute&&(c=n.data.stride);let f=["getX","getY","getZ"],h=r.offset;for(let d=e,m=e+t;d<m;d++){let v=(s?s[d]:d)*3,g=(d-h)*6,y=v+0,w=v+1,b=v+2;l&&(y=l[y],w=l[w],b=l[b]),a||(y=y*c+u,w=w*c+u,b=b*c+u);for(let T=0;T<3;T++){let _,S,A;a?(_=n[f[T]](y),S=n[f[T]](w),A=n[f[T]](b)):(_=p[y+T],S=p[w+T],A=p[b+T]);let I=_;S<I&&(I=S),A<I&&(I=A);let P=_;S>P&&(P=S),A>P&&(P=A);let C=(P-I)/2,B=T*2;r[g+B+0]=I+C,r[g+B+1]=C+(Math.abs(I)+C)*qr}}return r}raycastObject3D(e,t,r=[]){let{material:i}=e;if(i===void 0)return;dp.copy(e.matrixWorld).invert(),_o.copy(t.ray).applyMatrix4(dp),hp.setFromMatrixScale(e.matrixWorld),fp.copy(_o.direction).multiply(hp);let s=fp.length(),n=t.near/s,l=t.far/s;if(t.firstHitOnly===!0){let a=this.raycastFirst(_o,i,n,l);a=gl(a,e,t),a&&r.push(a)}else{let a=this.raycast(_o,i,n,l);for(let p=0,u=a.length;p<u;p++){let c=gl(a[p],e,t);c&&r.push(c)}}return r}refit(e=null){return(this.indirect?op:Kc)(this,e)}raycast(e,t=pp,r=0,i=1/0){let s=this._roots,n=[],l=this.indirect?sp:ep;for(let a=0,p=s.length;a<p;a++)l(this,a,t,e,n,r,i);return n}raycastFirst(e,t=pp,r=0,i=1/0){let s=this._roots,n=null,l=this.indirect?ap:tp;for(let a=0,p=s.length;a<p;a++){let u=l(this,a,t,e,r,i);u!=null&&(n==null||u.distance<n.distance)&&(n=u)}return n}intersectsGeometry(e,t){let r=!1,i=this._roots,s=this.indirect?up:ip;for(let n=0,l=i.length;n<l&&(r=s(this,n,e,t),!r);n++);return r}shapecast(e){let t=De.getPrimitive(),r=super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:this.indirect?Jc:jc});return De.releasePrimitive(t),r}bvhcast(e,t,r){let{intersectsRanges:i,intersectsTriangles:s}=r,n=De.getPrimitive(),l=this.geometry.index,a=this.geometry.attributes.position,p=this.indirect?d=>{let m=this.resolveTriangleIndex(d);ae(n,m*3,l,a)}:d=>{ae(n,d*3,l,a)},u=De.getPrimitive(),c=e.geometry.index,f=e.geometry.attributes.position,h=e.indirect?d=>{let m=e.resolveTriangleIndex(d);ae(u,m*3,c,f)}:d=>{ae(u,d*3,c,f)};if(s){if(!(e instanceof o))throw new Error('MeshBVH: "intersectsTriangles" callback can only be used with another MeshBVH.');let d=(m,x,v,g,y,w,b,T)=>{for(let _=v,S=v+g;_<S;_++){h(_),u.a.applyMatrix4(t),u.b.applyMatrix4(t),u.c.applyMatrix4(t),u.needsUpdate=!0;for(let A=m,I=m+x;A<I;A++)if(p(A),n.needsUpdate=!0,s(n,u,A,_,y,w,b,T))return!0}return!1};if(i){let m=i;i=function(x,v,g,y,w,b,T,_){return m(x,v,g,y,w,b,T,_)?!0:d(x,v,g,y,w,b,T,_)}}else i=d}return super.bvhcast(e,t,{intersectsRanges:i})}intersectsBox(e,t){return wo.set(e.min,e.max,t),wo.needsUpdate=!0,this.shapecast({intersectsBounds:r=>wo.intersectsBox(r),intersectsTriangle:r=>wo.intersectsTriangle(r)})}intersectsSphere(e){return this.shapecast({intersectsBounds:t=>e.intersectsBox(t),intersectsTriangle:t=>t.intersectsSphere(e)})}closestPointToGeometry(e,t,r={},i={},s=0,n=1/0){return(this.indirect?cp:np)(this,e,t,r,i,s,n)}closestPointToPoint(e,t={},r=0,i=1/0){return Uc(this,e,t,r,i)}};import{Vector3 as lt,Vector2 as yn,Ray as ex,Matrix4 as tx,FrontSide as rx,BackSide as ix,Triangle as gn,REVISION as Sp}from"/vendor/three/0.187.0/three.webgpu.js";var xp=new lt,yp=new lt,vp=new lt,Ut=new ex,bp=new tx,Tr=new lt,yl=new lt,nx=["x","y","z"],To=parseInt(Sp)>=169,ox=parseInt(Sp)<=161,So=new yn,Io=new yn,$o=new yn,wp=new lt,_p=new lt,Tp=new lt,St=class extends kt{get primitiveStride(){return 3}constructor(e,t={}){if(!e.isMesh)throw new Error("SkinnedMeshBVH: First argument must be a Mesh.");super(e.geometry,{...t,[Qt]:!0}),this.mesh=e,t[Qt]||this.init(t)}writePrimitiveBounds(e,t,r){let{mesh:i,geometry:s}=this,n=this._indirectBuffer,l=s.index?s.index.array:null,p=(n?n[e]:e)*3,u=p+0,c=p+1,f=p+2;l&&(u=l[u],c=l[c],f=l[f]),i.getVertexPosition(u,xp),i.getVertexPosition(c,yp),i.getVertexPosition(f,vp);for(let h=0;h<3;h++){let d=nx[h],m=xp[d],x=yp[d],v=vp[d],g=m;x<g&&(g=x),v<g&&(g=v);let y=m;x>y&&(y=x),v>y&&(y=v),t[r+h]=g,t[r+h+3]=y}return t}shapecast(e){let t=new xe;return super.shapecast({...e,intersectsPrimitive:e.intersectsTriangle,scratchPrimitive:t,iterate:sx})}raycastObject3D(e,t,r=[]){let{material:i}=e;if(i===void 0)return;let{matrixWorld:s}=e,{firstHitOnly:n}=t;bp.copy(s).invert(),Ut.copy(t.ray).applyMatrix4(bp);let l=null,a=1/0;return this.shapecast({boundsTraverseOrder:p=>p.distanceToPoint(Ut.origin),intersectsBounds:p=>{if(n){if(!Ut.intersectBox(p,yl))return 0;let u;return p.containsPoint(Ut.origin)?u=0:(yl.applyMatrix4(s),u=t.ray.origin.distanceTo(yl)),u<a?1:0}else return Ut.intersectsBox(p)?1:0},intersectsTriangle:(p,u)=>{let c=null;if(i.side===rx?c=Ut.intersectTriangle(p.a,p.b,p.c,!0,Tr):i.side===ix?c=Ut.intersectTriangle(p.c,p.b,p.a,!0,Tr):c=Ut.intersectTriangle(p.a,p.b,p.c,!1,Tr),!c)return;c=c.clone().applyMatrix4(s);let f=t.ray.origin.distanceTo(c);if(f>=t.near&&f<=t.far){if(n&&f>=a)return;let{geometry:h}=this,{index:d}=h,m=this.resolvePrimitiveIndex(u),x=m*3,v=x+0,g=x+1,y=x+2;d&&(v=d.array[v],g=d.array[g],y=d.array[y]);let w={distance:f,point:c.clone(),object:e,uv:null,uv1:null,normal:null,face:{a:v,b:g,c:y,normal:gn.getNormal(p.a,p.b,p.c,new lt),materialIndex:0},faceIndex:m};if(To){let S=new lt;gn.getBarycoord(Tr,p.a,p.b,p.c,S),w.barycoord=S}let b=h.attributes.uv,T=h.attributes.uv1,_=h.attributes.normal;if(b){So.fromBufferAttribute(b,v),Io.fromBufferAttribute(b,g),$o.fromBufferAttribute(b,y),w.uv=new yn;let S=gn.getInterpolation(Tr,p.a,p.b,p.c,So,Io,$o,w.uv);To||(w.uv=S)}if(T){So.fromBufferAttribute(T,v),Io.fromBufferAttribute(T,g),$o.fromBufferAttribute(T,y),w.uv1=new yn;let S=gn.getInterpolation(Tr,p.a,p.b,p.c,So,Io,$o,w.uv1);To||(w.uv1=S),ox&&(w.uv2=w.uv1)}if(_){wp.fromBufferAttribute(_,v),_p.fromBufferAttribute(_,g),Tp.fromBufferAttribute(_,y),w.normal=new lt;let S=gn.getInterpolation(Tr,p.a,p.b,p.c,wp,_p,Tp,w.normal);w.normal.dot(Ut.direction)>0&&w.normal.multiplyScalar(-1),To||(w.normal=S)}a=w.distance,l=w,n||r.push(w)}}}),n&&l&&r.push(l),r}};function sx(o,e,t,r,i,s,n){let{mesh:l,geometry:a}=t,p=a.index?a.index.array:null;for(let u=o,c=e+o;u<c;u++){let f=t.resolvePrimitiveIndex(u),h=3*f+0,d=3*f+1,m=3*f+2;if(p&&(h=p[h],d=p[d],m=p[m]),l.getVertexPosition(h,n.a),l.getVertexPosition(d,n.b),l.getVertexPosition(m,n.c),n.needsUpdate=!0,r(n,u,i,s))return!0}return!1}import{wgslFn as vl}from"/vendor/three/0.187.0/three.tsl.js";import{wgslFn as $p,wgsl as Cp}from"/vendor/three/0.187.0/three.tsl.js";import{StructTypeNode as li}from"/vendor/three/0.187.0/three.webgpu.js";var Pe=new li({min:"array<f32, 3>",max:"array<f32, 3>"},"BVHBoundingBox");Pe.getLength=()=>6;var Ge=new li({leftBounds:"BVHBoundingBox",rightBounds:"BVHBoundingBox",rightChildOrTriangleOffset:"uint",splitAxisOrTriangleCount:"uint",_alignment0:"uint",_alignment1:"uint"},"BVHNode");Ge.getLength=()=>2*Pe.getLength()+4;var Ip=new li({matrixWorld:"mat4x4f",inverseMatrixWorld:"mat4x4f",visible:"uint",_alignment0:"uint",_alignment1:"uint",_alignment2:"uint"},"TransformStruct"),D=new li({origin:"vec3f",direction:"vec3f",maxDist:"float"},"Ray"),nr=new li({indices:"vec4u",normal:"vec3f",didHit:"bool",barycoord:"vec3f",objectIndex:"uint",side:"float",dist:"float"},"IntersectionResult"),vn=new li({faceIndices:"vec4u",closestPoint:"vec3f",found:"bool",barycoord:"vec3f",objectIndex:"uint",faceNormal:"vec3f",side:"float",distanceSq:"float"},"PointQueryResult");var Co=Cp(`

	const BVH_STACK_DEPTH = 60u;
	const INFINITY = 1e20;
	const TRI_INTERSECT_EPSILON = 1e-5;

`),Ao=Cp(`
	struct IntersectionResult {
		indices: vec4u,
		normal: vec3f,
		didHit: bool,
		barycoord: vec3f,
		side: f32,
		dist: f32,
	};
`),E1=$p(`

	fn getVertexAttribute(
		barycoord: vec3f,
		indices: vec3u,
		attributeBuffer: ptr<storage, array<vec3f>, read>
	) -> vec3f {

		let n0 = attributeBuffer[ indices.x ];
		let n1 = attributeBuffer[ indices.y ];
		let n2 = attributeBuffer[ indices.z ];
		return barycoord.x * n0 + barycoord.y * n1 + barycoord.z * n2;

	}

`),Ap=$p(`

	fn intersectsBounds(
		ray: Ray,
		bounds: BVHBoundingBox,
		dist: ptr<function, f32>
	) -> bool {

		let boundsMin = vec3( bounds.min[0], bounds.min[1], bounds.min[2] );
		let boundsMax = vec3( bounds.max[0], bounds.max[1], bounds.max[2] );

		let invDir = 1.0 / ray.direction;
		let tMinPlane = ( boundsMin - ray.origin ) * invDir;
		let tMaxPlane = ( boundsMax - ray.origin ) * invDir;

		let tMinHit = vec3f(
			min( tMinPlane.x, tMaxPlane.x ),
			min( tMinPlane.y, tMaxPlane.y ),
			min( tMinPlane.z, tMaxPlane.z )
		);

		let tMaxHit = vec3f(
			max( tMinPlane.x, tMaxPlane.x ),
			max( tMinPlane.y, tMaxPlane.y ),
			max( tMinPlane.z, tMaxPlane.z )
		);

		let t0 = max( max( tMinHit.x, tMinHit.y ), tMinHit.z );
		let t1 = min( min( tMaxHit.x, tMaxHit.y ), tMaxHit.z );

		( *dist ) = max( t0, 0.0 );

		return t1 >= ( *dist );

	}

`,[D,Pe]);var bl=vl(`

	fn intersectsTriangle( ray: Ray, a: vec3f, b: vec3f, c: vec3f ) -> IntersectionResult {

		var result: IntersectionResult;
		result.didHit = false;

		let edge1 = b - a;
		let edge2 = c - a;
		let n = cross( edge1, edge2 );

		let det = - dot( ray.direction, n );

		if ( abs( det ) < TRI_INTERSECT_EPSILON ) {

			return result;

		}

		let invdet = 1.0 / det;

		let AO = ray.origin - a;
		let DAO = cross( AO, ray.direction );

		let u = dot( edge2, DAO ) * invdet;
		let v = -dot( edge1, DAO ) * invdet;
		let t = dot( AO, n ) * invdet;

		let w = 1.0 - u - v;

		if ( u < - TRI_INTERSECT_EPSILON || v < - TRI_INTERSECT_EPSILON || w < - TRI_INTERSECT_EPSILON || t < TRI_INTERSECT_EPSILON ) {

			return result;

		}

		result.didHit = true;
		result.barycoord = vec3f( w, u, v );
		result.dist = t;
		result.side = sign( det );
		result.normal = result.side * normalize( n );

		return result;

	}

`,[D,Ao,Co]),ax=vl(`

	fn intersectTriangles(
		bvh_position: ptr<storage, array<vec3f>, read>,
		bvh_index: ptr<storage, array<vec3u>, read>,
		offset: u32,
		count: u32,
		ray: Ray
	) -> IntersectionResult {

		var closestResult: IntersectionResult;

		closestResult.didHit = false;
		closestResult.dist = INFINITY;

		for ( var i = offset; i < offset + count; i = i + 1u ) {

			let indices = bvh_index[ i ];
			let a = bvh_position[ indices.x ];
			let b = bvh_position[ indices.y ];
			let c = bvh_position[ indices.z ];

			var triResult = intersectsTriangle( ray, a, b, c );

			if ( triResult.didHit && triResult.dist < closestResult.dist ) {

				closestResult = triResult;
				closestResult.indices = vec4u( indices.xyz, i );

			}

		}

		return closestResult;

	}

`,[bl,D,Ao,Co]),k1=vl(`

	fn bvhIntersectFirstHit(
		bvh_index: ptr<storage, array<vec3u>, read>,
		bvh_position: ptr<storage, array<vec3f>, read>,
		bvh: ptr<storage, array<BVHNode>,read>,
		ray: Ray,
	) -> IntersectionResult {

		var pointer = 0;
		var stack: array<u32, BVH_STACK_DEPTH>;
		stack[ 0 ] = 0u;

		var bestHit: IntersectionResult;

		bestHit.didHit = false;
		bestHit.dist = INFINITY;

		loop {

			if ( pointer < 0 || pointer >= i32( BVH_STACK_DEPTH ) ) {

				break;

			}

			let currNodeIndex = stack[ pointer ];
			let node = bvh[ currNodeIndex ];

			pointer = pointer - 1;

			var boundsHitDistance: f32 = 0.0;

			if ( ! intersectsBounds( ray, node.bounds, &boundsHitDistance ) || boundsHitDistance > bestHit.dist ) {

				continue;

			}

			let boundsInfox = node.splitAxisOrTriangleCount;
			let boundsInfoy = node.rightChildOrTriangleOffset;

			let isLeaf = ( boundsInfox & 0xffff0000u ) != 0u;

			if ( isLeaf ) {

				let count = boundsInfox & 0x0000ffffu;
				let offset = boundsInfoy;

				let localHit = intersectTriangles(
					bvh_position, bvh_index, offset,
					count, ray
				);

				if ( localHit.didHit && localHit.dist < bestHit.dist ) {

					bestHit = localHit;

				}

			} else {

				let leftIndex = currNodeIndex + 1u;
				let splitAxis = boundsInfox & 0x0000ffffu;
				let rightIndex = currNodeIndex + boundsInfoy;

				let leftToRight = ray.direction[splitAxis] >= 0.0;
				let c1 = select( rightIndex, leftIndex, leftToRight );
				let c2 = select( leftIndex, rightIndex, leftToRight );

				pointer = pointer + 1;
				stack[ pointer ] = c2;

				pointer = pointer + 1;
				stack[ pointer ] = c1;

			}

		}

		return bestHit;

	}

`,[ax,Ap,D,Ge,Ao,Co]);import{Matrix4 as ef,Mesh as Do,Group as _x}from"/vendor/three/0.187.0/three.webgpu.js";import{StorageBufferAttribute as No,StructTypeNode as Tx}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as Fo}from"/vendor/three/0.187.0/three.tsl.js";import{Node as lx,TSL as Pp}from"/vendor/three/0.187.0/three.webgpu.js";var Po=class extends lx{static get type(){return"ProxyCallNode"}constructor(e,t){super(),this.proxyNode=e,this.params=t}setup(){return this.proxyNode.proxyNode.call(...this.params)}},bn=class{get isNode(){return!0}get proxyNode(){let{proxyObject:e,proxyProperty:t}=this,r=t.split("."),i=e;for(let s=0,n=r.length;s<n;s++)i=i?.[r[s]];return i&&"functionNode"in i?i.functionNode:i??null}constructor(e,t=null){return this.proxyObject=t,this.proxyProperty=e,new Proxy(this,{get(r,i){if(i in r)return Reflect.get(r,i);{let s=r.proxyNode;if(!s)return;let n=Reflect.get(s,i);return typeof n=="function"?n.bind(s):n}},set(r,i,s){if(i in r)return Reflect.set(r,i,s);throw new Error("NodeProxy: Cannot set members of proxied nodes.")}})}},ge=(...o)=>new bn(...o),pe=(...o)=>{let e=new bn(...o),t=(...r)=>new Po(e,r);return Pp.nodeProxyConstructor(t,e)},Bo=class extends bn{get proxyNode(){return super.proxyNode||this.fallbackNode}constructor(e,t){super(e,null),Object.defineProperty(this,"fallbackNode",{value:t})}setup(e){return this.proxyObject=e.getContext(),this.proxyNode.setup(e)}},Be=(...o)=>new Bo(...o),$e=(...o)=>{let e=new Bo(...o),t=(...r)=>new Po(e,r);return Pp.nodeProxyConstructor(t,e)};import{Vector4 as Rp}from"/vendor/three/0.187.0/three.webgpu.js";var Ro=64,Ep=Ro/4,or=new Rp,It=new Rp;function Bp(o,e,t,r){let i=e*8;for(let s=0;s<3;s++){let n=o[i+s],l=o[i+s+3];n>l?(t[r+s]=1,t[r+s+3]=-1):(t[r+s]=n,t[r+s+3]=l)}}function Mp(o,e,t,r,i){let s=t*8;Bp(o,t+1,r,i),Bp(o,t+e[s+6],r,i+6)}function ux(o,e){let t=o?o.array:null,r=new Uint32Array(e.length*3);for(let i=0,s=e.length;i<s;i++){let n=3*i,l=3*e[i];for(let a=0;a<3;a++)r[n+a]=t?t[l+a]:l+a}return r}function wl(o,e,t,r){let i=new Uint32Array(r),s=new Float32Array(r);o._roots.forEach(n=>{let l=new Uint16Array(n),a=new Uint32Array(n),p=new Float32Array(n);for(let u=0,c=n.byteLength/32;u<c;u++){let f=u*8,h=f*2,d=t*Ep,m=k(h,l);if(m||Mp(p,a,u,s,d),e===null){t++;continue}if(m){let x=a[f+6],v=l[h+14],{transformSlot:g,nodeOffset:y}=v===0?{transformSlot:0,nodeOffset:0}:e[x];if(g>16777215)throw new Error(`packBVHBufferUtils: transform slot ${g} exceeds the 24-bit TLAS leaf limit.`);i[d+12]=y,i[d+13]=4278190080|g&16777215}else i[d+12]=a[f+6],i[d+13]=a[f+7];t++}})}function Dp(o,e){return Np(new Uint16Array(o),new Uint32Array(o),e)}function Np(o,e,t){if(k(t*8*2,o))return 1;let i=e[t*8+6];return i+Np(o,e,t+i)}function _l(o,e=0){let t=new Uint16Array(o),r=new Uint32Array(o),i=0;return s(e*8,1),i;function s(n,l){let a=n,p=n*2;if(k(p,t))i=Math.max(l,i);else{let c=V(a,r),f=W(a);s(f,l+1),s(c,l+1)}}}function Fp(o,e,t,r,i,s){let n=new Uint16Array(s),l=new Uint32Array(s),a=new Float32Array(s),p=new Uint16Array(o),u=new Uint32Array(o),c=new Float32Array(o);for(let f=0;f<t;f++){let h=e+f,d=h*8,m=d*2,x=i*Ep,v=x*2;k(m,p)?(l[x+12]=u[d+6]+r,n[v+26]=p[m+14],n[v+27]=65535):(Mp(c,u,h,a,x),l[x+12]=u[d+6],l[x+13]=u[d+7]),i++}}function kp(o,e,t,r,i){let{geometry:s}=o,{start:n,count:l,vertexStart:a}=e;if(o.indirect){let p=ux(s.index,o._indirectBuffer);for(let u=0;u<p.length;u++)i[u+r]=p[u]-a+t}else if(s.index)for(let p=0;p<l;p++)i[p+r]=s.index.getX(p+n)-a+t;else for(let p=0;p<l;p++)i[p+r]=p+n+t}function Lp(o,e,t,r,i,s){let{geometry:n,mesh:l=null}=o,{vertexStart:a,vertexCount:p}=e,u=new Float32Array(r),c=i.getLength();i.membersLayout.forEach(({name:f},h)=>{let d=n.attributes[f];s.getDefaultAttributeValue(f,or);for(let m=0;m<p;m++){if(d){switch(It.fromBufferAttribute(d,m+a),d.itemSize){case 1:It.y=or.y,It.z=or.z,It.w=or.w;break;case 2:It.z=or.z,It.w=or.w;break;case 3:It.w=or.w;break}l&&(f==="position"||f==="normal"||f==="tangent")&&l.applyBoneTransform(m+a,It)}else It.copy(or);It.toArray(u,(t+m)*c+h*4)}})}import{CodeNode as cx,FunctionNode as px,Node as Op,TSL as fx}from"/vendor/three/0.187.0/three.webgpu.js";var Tl=class extends Op{constructor(e){super(),this.literal=e}build(){return this.literal}},wn=class extends Op{constructor(e,t="property"){super(),this.node=e,this.output=t}build(e){return this.node.build(e,this.output)}};function dx(o){return o.isNode?new wn(o):null}function Up(o){let e=[];for(let t of o)if(Array.isArray(t))for(let r of t){let i=dx(r);i&&e.push(i)}return e}function zp(o,e){return o.map(t=>(t&&!t.isNode&&t instanceof Function&&(t=t(e)),t&&t.isNode?(t.setup(e),t.isWGSLTagCodeNode?new wn(t,"inline"):new wn(t)):t))}function Sl(o,e,t){let r="";for(let i=0,s=o.length;i<s;i++)if(r+=o[i],i<e.length){let n=e[i];Array.isArray(n)||(typeof n=="string"||typeof n=="number"?r+=String(n):r+=n.build(t))}return r}var Il=class extends px{static get type(){return"WGSLTagFnNode"}constructor(e,t,r="wgsl"){super("",Up(t),r),this.isWGSLTagFnNode=!0,this.tokens=e,this.args=t}setup(e){super.setup(e),this._normalizedArgs=zp(this.args,e)}getNodeFunction(e){let{tokens:t,_normalizedArgs:r}=this,i=e.getDataFromNode(this),s=i.nodeFunction;if(s===void 0){let n="";for(let l=0,a=t.length;l<a;l++)if(n+=t[l],l<r.length){let p=r[l];Array.isArray(p)||(typeof p=="string"||typeof p=="number"?n+=String(p):p.isStructLayoutNode?n+=p.getNodeType(e):p.isStruct?n+=p.layout.getNodeType(e):n+="_arg"+l)}n=n.replace(/\/\/.*[\n\r]/g,""),s=e.parser.parseFunction(n),i.nodeFunction=s}return s}generate(e,t){let r=super.generate(e,t),{_normalizedArgs:i}=this,s=Sl(this.tokens,i,e),{type:n}=this.getNodeFunction(e),l=e.getCodeFromNode(this,n);return l.code=s.replace(/\/\/.+[\n\r]/g,"").replace(/->\s*void/,"").trim(),r}},$l=class extends cx{static get type(){return"WGSLTagCodeNode"}constructor(e,t,r="wgsl"){super("",Up(t),r),this.isWGSLTagCodeNode=!0,this.tokens=e,this.args=t}setup(e){super.setup(e),this._normalizedArgs=zp(this.args,e)}build(e,t){return t==="inline"?Sl(this.tokens,this._normalizedArgs,e):super.build(e,t)}generate(e){super.generate(e);let t=e.getCodeFromNode(this,this.getNodeType(e));return t.code=Sl(this.tokens,this._normalizedArgs,e),t.code}},hx=o=>{let e=(...t)=>{if(t.length===1&&t[0]&&typeof t[0]=="object"&&!t[0].isNode){let r=t[0];for(let i in r)typeof r[i]=="string"&&(r[i]=new Tl(r[i]))}return o.call(...t)};return fx.nodeProxyConstructor(e,o)},$=(o,...e)=>hx(new Il(o,e)),Sr=(o,...e)=>new $l(o,e);import{uint as mx}from"/vendor/three/0.187.0/three.tsl.js";var _n=mx(60);function Hp(o,e){let{name:t=`bvh_shapecast_fn_${Math.random().toString(36).substring(2,7)}`,shapeStruct:r,resultStruct:i=null,prefixFn:s=null,boundsOrderFn:n=null,intersectsBoundsFn:l,intersectRangeFn:a,transformShapeFn:p=null,transformResultFn:u=null,resetShapeFn:c=null}=e,{nodes:f,transforms:h}=o.storage,d="";s&&(d=Sr`${s}();`);let m="";u&&(m=Sr`${u}( result, objectIndex );`);let x="";p&&(x=Sr`${p}( &localShape, objectIndex );`);let v="";c&&(v=Sr`${c}( objectIndex );`);let g="";n&&(g=Sr`
			let leftToRight = ${n}( localShape, splitAxis, node );
			c1 = select( rightIndex, leftIndex, leftToRight );
			c2 = select( leftIndex, rightIndex, leftToRight );
			hit1 = select( hitRight, hitLeft, leftToRight );
			hit2 = select( hitLeft, hitRight, leftToRight );
		`);let y=i?Sr`result: ptr<function, ${i}>`:"",w=i?"result":"",b=$`
		// fn
		fn ${t}( shape: ${r}, ${y} ) -> bool {

			${d}

			var didHit = false;

			var isTLAS = true;
			var pointer: i32 = 0;
			var stack: array<u32, ${_n}>;
			stack[ 0 ] = 0u;

			var blasDidHit: bool = false;
			var objectIndex: u32 = 0;
			var localShape: ${r} = shape;

			// the stack depth the current cluster's BLAS drains back down to once it is complete
			var tlasReset: i32 = 0;

			loop {

				// The cluster's BLAS has drained back to its TLAS leaf. Finalize the cluster that
				// was just traversed and resume the TLAS.
				if ( ! isTLAS && tlasReset == pointer ) {

					if ( blasDidHit ) {

						blasDidHit = false;
						didHit = true;
						${m}

					}

					${v}

					objectIndex = 0;
					isTLAS = true;
					localShape = shape;

				}

				// check if we've finished all nodes on the stack (or overrun the stack)
				if ( pointer < 0 || pointer >= i32( ${_n} ) ) {

					break;

				}

				// a node's bounds were tested by its parent, so the popped node is always entered
				let nodeIndex = stack[ pointer ];
				let node = ${f}[ nodeIndex ];
				pointer = pointer - 1;

				let infoX = node.splitAxisOrTriangleCount;
				let infoY = node.rightChildOrTriangleOffset;
				let isLeaf = ( infoX & 0xffff0000u ) != 0u;

				if ( isLeaf ) {

					if ( isTLAS ) {

						// the leaf encodes the placement / transform slot in the low 24 bits of infoX
						// and the cluster subtree's absolute node offset in infoY, which is pushed
						// directly as the BLAS entry node. Each TLAS leaf references one cluster.
						objectIndex = infoX & 0x00ffffffu;

						let transform = ${h}[ objectIndex ];
						if ( transform.visible != 0u ) {

							tlasReset = pointer;
							isTLAS = false;
							blasDidHit = false;

							// Transform shape into object local space
							localShape = shape;
							${x}

							pointer = pointer + 1;
							stack[ pointer ] = infoY;

						}

					} else {

						let count = infoX & 0x0000ffffu;
						let offset = infoY;
						// Cast bool callbacks to u32 to preserve their existing hit / miss behavior.
						let rangeResult = u32( ${a}( localShape, offset, count, ${w} ) );
						blasDidHit = rangeResult != ${0}u || blasDidHit;
						if ( rangeResult == ${3}u ) {

							${m}
							${v}
							return true;

						}

					}

				} else {

					// test both children here, pushing the farther one first so the nearer is popped next
					let leftIndex = nodeIndex + 1u;
					let splitAxis = infoX & 0x0000ffffu;
					let rightIndex = nodeIndex + infoY;
					let hitLeft = ${l}( localShape, node.leftBounds, ${w} ) != 0u;
					let hitRight = ${l}( localShape, node.rightBounds, ${w} ) != 0u;

					var c1 = rightIndex;
					var c2 = leftIndex;
					var hit1 = hitRight;
					var hit2 = hitLeft;
					${g}

					if ( hit2 ) {

						pointer = pointer + 1;
						stack[ pointer ] = c2;

					}

					if ( hit1 ) {

						pointer = pointer + 1;
						stack[ pointer ] = c1;

					}

				}

			}

			return didHit;

		}
	`;return b.outputType=i,b.functionName=t,b}import{float as xx}from"/vendor/three/0.187.0/three.tsl.js";var Wp=$`
	// fn
	fn closestPointToTriangle(
		p: vec3f,
		v0: vec3f,
		v1: vec3f,
		v2: vec3f
	) -> vec3f {

		let v10 = v1 - v0;
		let v21 = v2 - v1;
		let v02 = v0 - v2;
		let p0 = p - v0;
		let p1 = p - v1;
		let p2 = p - v2;

		let nor = cross( v10, v02 );
		let q = cross( nor, p0 );
		let d = 1.0 / dot( nor, nor );
		var u = d * dot( q, v02 );
		var v = d * dot( q, v10 );
		var w = 1.0 - u - v;

		if ( u < 0.0 ) {

			w = clamp( dot( p2, v02 ) / dot( v02, v02 ), 0.0, 1.0 );
			u = 0.0;
			v = 1.0 - w;

		} else if ( v < 0.0 ) {

			u = clamp( dot( p0, v10 ) / dot( v10, v10 ), 0.0, 1.0 );
			v = 0.0;
			w = 1.0 - u;

		} else if ( w < 0.0 ) {

			v = clamp( dot( p1, v21 ) / dot( v21, v21 ), 0.0, 1.0 );
			w = 0.0;
			u = 1.0 - v;

		}

		return vec3f( w, u, v );

	}
`,ui=$`
	// fn
	fn intersectRayTriangle( ray: ${D}, a: vec3f, b: vec3f, c: vec3f, threshold: f32 ) -> ${nr} {

		const DET_EPSILON = 1e-15;

		var result: ${nr};
		result.didHit = false;

		let edge1 = b - a;
		let edge2 = c - a;
		let n = cross( edge1, edge2 );

		let det = - dot( ray.direction, n );
		if ( abs( det ) < DET_EPSILON ) {

			return result;

		}

		let invdet = 1.0 / det;

		let AO = ray.origin - a;
		let DAO = cross( AO, ray.direction );

		let u = dot( edge2, DAO ) * invdet;
		if ( u < 0.0 || u > 1.0 ) {

			return result;

		}

		let v = - dot( edge1, DAO ) * invdet;
		if ( v < 0.0 || u + v > 1.0 ) {

			return result;

		}

		let t = dot( AO, n ) * invdet;
		let w = 1.0 - u - v;
		if ( t < threshold ) {

			return result;

		}

		result.didHit = true;
		result.barycoord = vec3f( w, u, v );
		result.dist = t;
		result.side = sign( det );
		result.normal = result.side * normalize( n );

		return result;

	}
`,sr=$`
	// fn
	fn ndcToCameraRay( ndc: vec2f, inverseModelViewProjection: mat4x4f ) -> ${D} {

		var homogeneous = vec4f();
		var ray: ${D};

		homogeneous = inverseModelViewProjection * vec4f( ndc, 0.0, 1.0 );
		ray.origin = homogeneous.xyz / homogeneous.w;

		homogeneous = inverseModelViewProjection * vec4f( ndc, 1.0, 1.0 );
		let toFarPlane = ( homogeneous.xyz / homogeneous.w ) - ray.origin;
		ray.maxDist = length( toFarPlane );
		ray.direction = toFarPlane / ray.maxDist;

		return ray;

	}
`;var Eo=$`
	fn bvh_rayBoundsDistance( ray: ${D}, bounds: ${Pe} ) -> f32 {

		let boundsMin = vec3( bounds.min[0], bounds.min[1], bounds.min[2] );
		let boundsMax = vec3( bounds.max[0], bounds.max[1], bounds.max[2] );

		// Avoid 0 * infinity for rays parallel to a slab and on its boundary.
		let parallel = ray.direction == vec3f( 0.0 );
		if ( any( parallel & ( ( ray.origin < boundsMin ) | ( ray.origin > boundsMax ) ) ) ) {

			return - 1.0;

		}

		let invDir = 1.0 / select( ray.direction, vec3f( 1.0 ), parallel );
		let tMinPlane = ( boundsMin - ray.origin ) * invDir;
		let tMaxPlane = ( boundsMax - ray.origin ) * invDir;

		// parallel axes never limit the slab interval
		const FLT_MAX = 3.402823e38;
		let tMinHit = select( min( tMinPlane, tMaxPlane ), vec3f( - FLT_MAX ), parallel );
		let tMaxHit = select( max( tMinPlane, tMaxPlane ), vec3f( FLT_MAX ), parallel );

		let t0 = max( max( tMinHit.x, tMinHit.y ), tMinHit.z );
		let t1 = min( min( tMaxHit.x, tMaxHit.y ), tMaxHit.z );

		let dist = max( t0, 0.0 );
		return select( dist, - 1.0, t1 < dist );

	}
`;function Vp(o){let{index:e,attributes:t,transforms:r}=o.storage,i=xx(1).toVar("bvh_rayScalarAnyHit");return o.getShapecastFn({name:"bvh_RaycastAnyHit",shapeStruct:D,intersectsBoundsFn:$`
			fn rayIntersectsBoundsAnyHit( ray: ${D}, bounds: ${Pe} ) -> u32 {

				let dist = ${Eo}( ray, bounds ) * ${i};
				return u32( dist >= 0.0 && ( ray.maxDist <= 0.0 || dist < ray.maxDist ) );

			}
		`,intersectRangeFn:$`
			fn intersectRangeAnyHit( ray: ${D}, offset: u32, count: u32 ) -> u32 {

				for ( var ti = offset; ti < offset + count; ti = ti + 1u ) {

					let a = ${t}[ ${e}[ ti * 3u ] ].position.xyz;
					let b = ${t}[ ${e}[ ti * 3u + 1u ] ].position.xyz;
					let c = ${t}[ ${e}[ ti * 3u + 2u ] ].position.xyz;

					let triResult = ${ui}( ray, a, b, c, 0.0 );
					if ( triResult.didHit && ( ray.maxDist <= 0.0 || triResult.dist * ${i} < ray.maxDist ) ) {

						return ${3}u;

					}

				}

				return ${0}u;

			}
		`,transformShapeFn:$`
			fn transformRayAnyHit( ray: ptr<function, ${D}>, objectIndex: u32 ) -> void {

				let toLocal = ${r}[ objectIndex ].inverseMatrixWorld;
				ray.origin = ( toLocal * vec4f( ray.origin, 1.0 ) ).xyz;
				ray.direction = ( toLocal * vec4f( ray.direction, 0.0 ) ).xyz;

				let len = length( ray.direction );
				ray.direction /= len;
				${i} = 1.0 / len;

			}
		`,resetShapeFn:$`
			fn resetRayScalarAnyHit( objectIndex: u32 ) -> void {

				${i} = 1.0;

			}
		`})}import{float as yx}from"/vendor/three/0.187.0/three.tsl.js";function Gp(o){let{index:e,attributes:t,transforms:r}=o.storage,i=yx(1).toVar("bvh_rayScalar");return o.getShapecastFn({name:"bvh_RaycastFirstHit",shapeStruct:D,resultStruct:nr,boundsOrderFn:$`
			fn getBoundsOrder( ray: ${D}, splitAxis: u32, node: ${Ge} ) -> bool {

				return ray.direction[ splitAxis ] >= 0.0;

			}
		`,intersectsBoundsFn:$`
			fn rayIntersectsBounds( ray: ${D}, bounds: ${Pe}, result: ptr<function, ${nr}> ) -> u32 {

				let dist = ${Eo}( ray, bounds ) * ${i};
				if ( dist < 0.0 ) {

					return 0u;

				} else if ( ray.maxDist > 0.0 && dist >= ray.maxDist ) {

					return 0u;

				} else if ( result.didHit && dist >= result.dist ) {

					return 0u;

				} else {

					return 1u;

				}

			}

		`,intersectRangeFn:$`
			fn intersectRange( ray: ${D}, offset: u32, count: u32, result: ptr<function, ${nr}> ) -> bool {

				var didHit = false;
				for ( var ti = offset; ti < offset + count; ti = ti + 1u ) {

					let i0 = ${e}[ ti * 3u ];
					let i1 = ${e}[ ti * 3u + 1u ];
					let i2 = ${e}[ ti * 3u + 2u ];

					let a = ${t}[ i0 ].position.xyz;
					let b = ${t}[ i1 ].position.xyz;
					let c = ${t}[ i2 ].position.xyz;

					var triResult = ${ui}( ray, a, b, c, 0.0 );
					triResult.dist *= ${i};
					if ( triResult.didHit && ( ray.maxDist <= 0.0 || triResult.dist < ray.maxDist ) && ( ! result.didHit || triResult.dist < result.dist ) ) {

						result.didHit = true;
						result.dist = triResult.dist;
						result.normal = triResult.normal;
						result.side = triResult.side;
						result.barycoord = triResult.barycoord;
						result.indices = vec4u( i0, i1, i2, ti );

						didHit = true;

					}

				}

				return didHit;

			}
		`,transformShapeFn:$`
			fn transformRay( ray: ptr<function, ${D}>, objectIndex: u32 ) -> void {

				let toLocal = ${r}[ objectIndex ].inverseMatrixWorld;
				ray.origin = ( toLocal * vec4f( ray.origin, 1.0 ) ).xyz;
				ray.direction = ( toLocal * vec4f( ray.direction, 0.0 ) ).xyz;

				let len = length( ray.direction );
				ray.direction /= len;
				${i} = 1.0 / len;

			}
		`,transformResultFn:$`
			fn transformResult( hit: ptr<function, ${nr}>, objectIndex: u32 ) -> void {

				let toLocal = ${r}[ objectIndex ].inverseMatrixWorld;
				hit.normal = normalize( ( transpose( toLocal ) * vec4f( hit.normal, 0.0 ) ).xyz );
				hit.objectIndex = objectIndex;

			}
		`,resetShapeFn:$`
			fn resetRayScalar( objectIndex: u32 ) -> void {

				${i} = 1.0;

			}
		`})}function qp(o){let{storage:e,structs:t}=o,r=t.attributes.membersLayout.map(({name:i})=>`result.${i} = a0.${i} * barycoord.x + a1.${i} * barycoord.y + a2.${i} * barycoord.z;`).join(`
`);return $`
		// fn
		fn bvh_sampleTrianglePoint( barycoord: vec3f, indices: vec3u ) -> ${t.attributes} {

			var result: ${t.attributes};
			var a0 = ${e.attributes}[ indices.x ];
			var a1 = ${e.attributes}[ indices.y ];
			var a2 = ${e.attributes}[ indices.z ];
			${r}
			return result;

		}
	`}import{mat4 as vx}from"/vendor/three/0.187.0/three.tsl.js";function Xp(o){let{index:e,attributes:t,transforms:r}=o.storage,i=vx().toVar("bvh_toWorldMat");return o.getShapecastFn({name:"bvh_ClosestPointToPoint",shapeStruct:"vec3f",resultStruct:vn,boundsOrderFn:$`
			fn cppBoundsOrder( shape: vec3f, splitAxis: u32, node: ${Ge} ) -> bool {

				let toWorld = ${i};
				let absX = abs( toWorld[ 0 ].xyz );
				let absY = abs( toWorld[ 1 ].xyz );
				let absZ = abs( toWorld[ 2 ].xyz );

				// squared distance from the point to each child's bounds in world space
				let lMin = vec3f( node.leftBounds.min[ 0 ], node.leftBounds.min[ 1 ], node.leftBounds.min[ 2 ] );
				let lMax = vec3f( node.leftBounds.max[ 0 ], node.leftBounds.max[ 1 ], node.leftBounds.max[ 2 ] );
				let lHalf = ( lMax - lMin ) * 0.5;
				let lCenter = ( toWorld * vec4f( ( lMin + lMax ) * 0.5, 1.0 ) ).xyz;
				let lExtent = absX * lHalf.x + absY * lHalf.y + absZ * lHalf.z;
				let lDelta = shape - clamp( shape, lCenter - lExtent, lCenter + lExtent );

				let rMin = vec3f( node.rightBounds.min[ 0 ], node.rightBounds.min[ 1 ], node.rightBounds.min[ 2 ] );
				let rMax = vec3f( node.rightBounds.max[ 0 ], node.rightBounds.max[ 1 ], node.rightBounds.max[ 2 ] );
				let rHalf = ( rMax - rMin ) * 0.5;
				let rCenter = ( toWorld * vec4f( ( rMin + rMax ) * 0.5, 1.0 ) ).xyz;
				let rExtent = absX * rHalf.x + absY * rHalf.y + absZ * rHalf.z;
				let rDelta = shape - clamp( shape, rCenter - rExtent, rCenter + rExtent );

				// the nearer child is traversed first
				return dot( lDelta, lDelta ) <= dot( rDelta, rDelta );

			}
		`,intersectsBoundsFn:$`
			fn cppIntersectsBounds( shape: vec3f, bounds: ${Pe}, result: ptr<function, ${vn}> ) -> u32 {

				// return 1u;
				// we need to check this no matter what if the result has not been found yet
				if ( ! result.found ) {

					return 1u;

				}

				let toWorld = ${i};

				// transform to world space
				let bMin = vec3f( bounds.min[ 0 ], bounds.min[ 1 ], bounds.min[ 2 ] );
				let bMax = vec3f( bounds.max[ 0 ], bounds.max[ 1 ], bounds.max[ 2 ] );
				let center = ( bMin + bMax ) * 0.5;
				let halfExtent = ( bMax - bMin ) * 0.5;
				let worldCenter = ( toWorld * vec4f( center, 1.0 ) ).xyz;
				let worldHalfExtent =
					abs( toWorld[ 0 ].xyz ) * halfExtent.x +
				    abs( toWorld[ 1 ].xyz ) * halfExtent.y +
				    abs( toWorld[ 2 ].xyz ) * halfExtent.z;
				let worldMin = worldCenter - worldHalfExtent;
				let worldMax = worldCenter + worldHalfExtent;

				// intersect if the distance to the bounds is not bigger than the already found
				let d = shape - clamp( shape, worldMin, worldMax );
				return select( 0u, 1u, dot( d, d ) < result.distanceSq );

			}
		`,intersectRangeFn:$`
			fn cppIntersectsRange( shape: vec3f, offset: u32, count: u32, result: ptr<function, ${vn}> ) -> bool {

				var didHit = false;
				let toWorld = ${i};

				for ( var i = offset; i < offset + count; i ++ ) {

					// transform the triangle to world space
					let i0 = ${e}[ i * 3u + 0u ];
					let i1 = ${e}[ i * 3u + 1u ];
					let i2 = ${e}[ i * 3u + 2u ];
					let a = ( toWorld * vec4f( ${t}[ i0 ].position.xyz, 1.0 ) ).xyz;
					let b = ( toWorld * vec4f( ${t}[ i1 ].position.xyz, 1.0 ) ).xyz;
					let c = ( toWorld * vec4f( ${t}[ i2 ].position.xyz, 1.0 ) ).xyz;

					let barycoord = ${Wp}( shape, a, b, c );
					let closestPoint = barycoord.x * a + barycoord.y * b + barycoord.z * c;
					let delta = shape - closestPoint;
					let distSq = dot( delta, delta );

					// copy the content over
					if ( ! result.found || distSq < result.distanceSq ) {

						let normal = normalize( cross( a - b, b - c ) );

						result.closestPoint = closestPoint;
						result.barycoord = barycoord;
						result.distanceSq = distSq;
						result.faceNormal = normal;
						result.side = sign( dot( normal, delta ) );
						result.faceIndices = vec4u( i0, i1, i2, i );
						result.found = true;
						didHit = true;

					}

				}

				return didHit;

			}
		`,resetShapeFn:$`
				fn cppResetShape( objectIndex: u32 ) -> void {

					// node bounds are transformed by "toWorld" during the bounds tests. Only the
					// object-local BLAS bounds need the object's world matrix - the top-level bounds
					// are already in world space - so restore identity before top-level traversal resumes.
					${i} = mat4x4f(
						1.0, 0.0, 0.0, 0.0,
						0.0, 1.0, 0.0, 0.0,
						0.0, 0.0, 1.0, 0.0,
						0.0, 0.0, 0.0, 1.0
					);

				}
			`,transformShapeFn:$`
			fn cppTransformShape( shape: ptr<function, vec3f>, objectIndex: u32 ) -> void {

				${i} = ${r}[ objectIndex ].matrixWorld;

			}
		`,transformResultFn:$`
			fn cppTransformResult( result: ptr<function, ${vn}>, objectIndex: u32 ) -> void {

				result.objectIndex = objectIndex;

			}
		`})}import{Matrix4 as Bl,Box3 as bx}from"/vendor/three/0.187.0/three.webgpu.js";var Sn=24,Cl=(1<<Sn)-1,jp=31-Sn,wx=(1<<jp)-1,Al=new Bl,Pl=new bx,Tn=new Bl,Mo=class extends ti{constructor(e,t){super(),t={getBVH:(n,l)=>{throw new Error("ClusteredBVH: getBVH callback must be provided ")},shouldCluster:n=>n.isSkinnedMesh||n.isInstancedMesh||n.isBatchedMesh,primitiveLimit:64,matrixWorld:Array.isArray(e)?new Bl:e.matrixWorld,includeInstances:!0,_strictLeafSize:1,...t};let r=Array.from(Kp(e)),i=Math.ceil(Math.log2(r.length)),s=(1<<i)-1;this.objects=r,this.getBVH=t.getBVH,this.shouldCluster=t.shouldCluster,this.includeInstances=t.includeInstances,this.primitiveLimit=t.primitiveLimit,this.matrixWorld=t.matrixWorld,this.bvhMap=new WeakMap,this.idBits=i,this.idMask=s,this.primitiveBufferStride=2,this.init(t)}init(e){let t=0,{objects:r,bvhMap:i,matrixWorld:s}=this;Al.copy(s).invert(),r.forEach(n=>{let l=[];for(let a=0,p=this._getInstanceCount(n);a<p;a++){let u=this.getBVH(n,a);l.push(u),u&&(t+=this.shouldCluster(n)?u._roots.length:this._countRelevantLeafNodes(u))}i.set(n,l)}),this.primitiveBuffer=new Uint32Array(t*2),this._fillPrimitiveBuffer(this.primitiveBuffer),super.init(e)}getRootRanges(){return[{offset:0,count:this.primitiveBuffer.length/this.primitiveBufferStride}]}refit(...e){Al.copy(this.matrixWorld).invert(),super.refit(...e)}writePrimitiveBounds(e,t,r){let{primitiveBuffer:i,bvhMap:s,objects:n}=this,l=i[2*e+0],a=i[2*e+1],p=n[this.getObjectId(l)],u=this.getInstanceId(l),c=s.get(p)[u],f=this.getBVHRootIndex(a),h=this.getBVHNodeIndex(a);p.isInstancedMesh||p.isBatchedMesh?(p.getMatrixAt(u,Tn),Tn.premultiply(p.matrixWorld)):Tn.copy(p.matrixWorld),Tn.premultiply(Al),te(h,new Float32Array(c._roots[f]),Pl),Pl.applyMatrix4(Tn);let{min:d,max:m}=Pl;t[r+0]=d.x,t[r+1]=d.y,t[r+2]=d.z,t[r+3]=m.x,t[r+4]=m.y,t[r+5]=m.z}getInstanceId(e){let{idMask:t,idBits:r}=this;return(e&~t)>>>r}getObjectId(e){let{idMask:t}=this;return e&t}getBVHRootIndex(e){return e>>>Sn}getBVHNodeIndex(e){return(e&Cl)*8}_getInstanceCount(e){let{includeInstances:t}=this;return e.isInstancedMesh&&t?e.count:e.isBatchedMesh&&t?e.instanceCount:1}_fillPrimitiveBuffer(e){let{objects:t,bvhMap:r,idBits:i,primitiveLimit:s}=this,n=0,l=(a,p,u,c)=>{if(c>Cl)throw new Error(`ClusteredBVH: cluster node index ${c} exceeds the ${Sn}-bit packing limit and cannot be represented.`);if(u>wx)throw new Error(`ClusteredBVH: bvh root index ${u} exceeds the ${jp}-bit packing limit and cannot be represented.`);e[2*n+0]=a<<i|p,e[2*n+1]=u<<Sn|c&Cl,n++};t.forEach((a,p)=>{r.get(a).forEach((u,c)=>{if(u)if(this.shouldCluster(a))for(let f=0,h=u._roots.length;f<h;f++)l(c,p,f,0);else Yp(u,s,(f,h)=>{l(c,p,f,h/8)})})})}_countRelevantLeafNodes(e){let{primitiveLimit:t}=this,r=0;return Yp(e,t,(i,s,n,l)=>{r++,l&&n>=t&&console.warn(`ClusteredBVH: a leaf node with ${n} primitives exceeds the cluster primitive limit of ${t} and cannot be subdivided further.`)}),r}};function Yp(o,e,t){let r=o._roots.length;for(let i=0;i<r;i++)_t.setBVH(o,i),_t.traverseBuffer((s,n,l)=>{let a=_t.getRangeStart(l),u=_t.getRangeEnd(l)-a;return u<e||n?(t(i,l,u,n),!0):!1});_t.reset()}function Kp(o,e=new Set){return Array.isArray(o)?o.forEach(t=>Kp(t,e)):o.traverse(t=>{t.isMesh&&e.add(t)}),e}var Ir=new ef,ko=new ef,Qp={start:0,count:0,vertexStart:0,vertexCount:0};function Sx(o){let e=o;for(;e;){if(e.visible===!1)return!1;e=e.parent}return!0}function Zp(o){return o._roots.reduce((e,t)=>e+t.byteLength/32,0)}function Jp(o,e){return`${o}_${e}`}var Lo=class{constructor(e,t={}){let{attributes:r={position:"vec4f"},autogenerateBvh:i=!0}=t;Array.isArray(e)||(e=[e]),e=e.map(s=>{if(s.isObject3D)return s;if(s.isBufferGeometry)return new Do(s);if(s instanceof kt){let n=new Do;return n.geometry.boundsTree=s,n}}),this._bvhCache=new Map,this.autogenerateBvh=i,this.attributes=r,this.objects=e,this.bvh=null,this.storage=new In,this.structs=new In({transform:Ip}),this.fns=new In({raycastFirstHit:Gp(this),raycastAnyHit:Vp(this),closestPointToPoint:Xp(this),sampleTrianglePoint:null},pe)}getRootObject(){let{objects:e}=this;if(e.isObject3D)return e;Array.isArray(e)||(e=[e]),e=e.map(r=>{if(r.isObject3D)return r;if(r.isBufferGeometry)return new Do(r);if(r instanceof kt){let i=new Do;return i.geometry.boundsTree=r,i}});let t=new _x;return t.children=e,t}getShapecastFn(e){return Hp(this,e)}update(){let e=this.getRootObject(),t=0;e.traverse(M=>{M.isMesh&&t++}),this.bvh=new Mo(e,{strategy:2,getBVH:(M,oe)=>this._getBVHSync(M,oe,Qp),primitiveLimit:t<3?1/0:64}),this.dispose();let{attributes:r,structs:i,bvh:s}=this,n=[],l=[],a=new Map,p=Zp(s)*Ro,u=0,c=0,f=[],h=0,d=this._getTransformMap(s),{primitiveBuffer:m,primitiveBufferStride:x}=s;for(let M=0,oe=m.length;M<oe;M+=x){let we=m[M],ze=m[M+1],G=s.objects[s.getObjectId(we)],se=s.getInstanceId(we),He={start:0,count:0,vertexStart:0,vertexCount:0},We=this._getBVHSync(G,se,He);if(!We)throw new Error("BVHComputeData: BVH not found.");let he=n.find(j=>j.bvh===We);he||(he={index:n.length,bvh:We,range:He,geometryOffset:0},u+=he.range.count,c+=he.range.vertexCount,n.push(he));let ke=s.getBVHRootIndex(ze),O=s.getBVHNodeIndex(ze)/8,J=`${he.index}_${ke}_${O}`,U=a.get(J);if(U===void 0){let j=Dp(We._roots[ke],O);U={data:he,root:ke,node:O,size:j,base:0},a.set(J,U),l.push(U),p+=j*Ro,h=Math.max(h,_l(We._roots[ke],O))}f.push({transformSlot:d.get(Jp(we,ke)).slot,subtree:U})}if(_l(s._roots[0])+h-1>_n.value)throw new Error("BVHComputeData: BVH depth overruns the compute stack depth.");let y=Math.max(d.size,2);u=Math.max(u,2),c=Math.max(c,2);let w=new Tx(r,"bvh_GeometryStruct"),b=0,T=0,_=new Uint32Array(u),S=new ArrayBuffer(c*w.getLength()*4),A=new ArrayBuffer(p);n.forEach(M=>{M.geometryOffset=T/3,M.bvh.indirect||(M.geometryOffset-=M.range.start/3),kp(M.bvh,M.range,b,T,_),Lp(M.bvh,M.range,b,S,w,this),T+=M.range.count,b+=M.range.vertexCount});let I=Zp(s);l.forEach(M=>{M.base=I,Fp(M.data.bvh._roots[M.root],M.node,M.size,M.data.geometryOffset,I,A),I+=M.size}),f.forEach(M=>M.nodeOffset=M.subtree.base),wl(s,f,0,A);let P=new ArrayBuffer(i.transform.getLength()*y*4),C=Fo(new No(new Uint32Array(A),1),Ge).toReadOnly().setName("bvh_nodes"),B=new No(new Uint32Array(P),1),R=Fo(B,i.transform).toReadOnly().setName("bvh_transforms"),E=Fo(new No(_,1),"uint").toReadOnly().setName("bvh_index"),N=Fo(new No(new Uint32Array(S),w.getLength()),w).toReadOnly().setName("bvh_attributes");this.storage.transforms=R,this.storage.nodes=C,this.storage.index=E,this.storage.attributes=N,this.structs.attributes=w,ko.copy(s.matrixWorld).invert(),d.forEach(M=>{this.writeTransformData(M,ko,M.slot,P)}),this.fns.sampleTrianglePoint=qp(this),this._bvhCache.clear()}async updateAsync(){let e=[];this.getRootObject().traverse(t=>{if(!t.isMesh)return;let r=1;t.isInstancedMesh?r=t.count:t.isBatchedMesh&&(r=t.instanceCount);for(let i=0;i<r;i++)e.push(this.getBVH(t,i,Qp))}),await Promise.all(e),this.update()}updateTransforms(){let{bvh:e,storage:t}=this;e.refit();let r=t.nodes.proxyNode.value;wl(e,null,0,r.array.buffer),r.needsUpdate=!0;let i=t.transforms.proxyNode.value,s=i.array.buffer;ko.copy(e.matrixWorld).invert(),this._getTransformMap(e).forEach(n=>{this.writeTransformData(n,ko,n.slot,s)}),i.needsUpdate=!0}writeTransformData(e,t,r,i){let{structs:s}=this,n=new Float32Array(i),l=new Uint32Array(i),{object:a,instanceId:p}=e;a.isInstancedMesh||a.isBatchedMesh?(a.getMatrixAt(p,Ir),Ir.premultiply(a.matrixWorld)):Ir.copy(a.matrixWorld),Ir.premultiply(t),Ir.toArray(n,r*s.transform.getLength()),Ir.invert(),Ir.toArray(n,r*s.transform.getLength()+16);let u=Sx(a);a.isBatchedMesh&&(u=u&&a.getVisibleAt(p)),l[r*s.transform.getLength()+32]=u?1:0}getBVH(e,t,r){let{autogenerateBvh:i,_bvhCache:s}=this,n=null;if(e.boundsTree||e.isSkinnedMesh){let l=e.geometry;if(r.count=l.index?l.index.count:l.attributes.position.count,r.vertexCount=l.attributes.position.count,n=e.boundsTree||null,n===null&&i){let a=e.uuid;n=s.get(a)||new St(e),s.set(a,n)}}else if(e.isBatchedMesh){let l=e.getGeometryIdAt(t),a=e.getGeometryRangeAt(l);if(Object.assign(r,a),n=e.boundsTrees[l]||null,n===null&&i){let p=`batched_${e.geometry.uuid}_${a.start}_${a.count}`;n=s.get(p)||new Ot(e.geometry,{range:{...r}}),s.set(p,n)}}else{let l=e.geometry;if(r.count=l.index?l.index.count:l.attributes.position.count,r.vertexCount=l.attributes.position.count,n=e.geometry.boundsTree||null,n===null&&i){let a=l.uuid;n=s.get(a)||new Ot(l),s.set(a,n)}}return n}getDefaultAttributeValue(e,t){switch(e){case"position":case"color":t.set(1,1,1,1);break;default:t.set(0,0,0,0)}return t}dispose(){let{storage:e}=this;for(let t in e)e[t].value?.dispose()}_getBVHSync(e,t,r){let i=this.getBVH(e,t,r);if(i instanceof Promise)throw new Error('BVHComputeData: "getBVH" returned a promise. Use "updateAsync" instead.');return i}_getTransformMap(e){let{primitiveBuffer:t,primitiveBufferStride:r}=e,i=new Map;for(let s=0,n=t.length;s<n;s+=r){let l=t[s],a=e.getBVHRootIndex(t[s+1]),p=Jp(l,a);if(i.has(p))continue;let u=i.size,c=e.objects[e.getObjectId(l)],f=e.getInstanceId(l);i.set(p,{object:c,instanceId:f,compositeId:l,root:a,slot:u})}return i}},In=class{constructor(e={},t=ge){let r={};return new Proxy({...e},{get(i,s){return r[s]||(r[s]=t(s,i)),r[s]},set(i,s,n){return i[s]=n,!0}})}};import{BufferGeometry as Ix,Float32BufferAttribute as tf,OrthographicCamera as $x,Mesh as Cx}from"/vendor/three/0.187.0/three.webgpu.js";var Ax=new $x(-1,1,1,-1,0,1),Rl=class extends Ix{constructor(){super(),this.setAttribute("position",new tf([-1,3,0,-1,-1,0,3,-1,0],3)),this.setAttribute("uv",new tf([0,2,0,0,2,0],2))}},Px=new Rl,$t=class{constructor(e){this._mesh=new Cx(Px,e)}dispose(){this._mesh.geometry.dispose()}render(e){e.render(this._mesh,Ax)}get material(){return this._mesh.material}set material(e){this._mesh.material=e}};import{MeshBasicNodeMaterial as Bx,NoToneMapping as Rx,StorageTexture as rf}from"/vendor/three/0.187.0/three.webgpu.js";import{uv as Ex,varying as Mx,texture as nf,vec4 as Dx,toneMapping as Nx,uniform as Fx,wgslFn as kx}from"/vendor/three/0.187.0/three.tsl.js";var Oo=kx(`
	fn sampleTexel( tex: texture_2d<f32>, coord: vec2f ) -> vec4f {

		// Manual bilinear filtering using textureLoad to support filterable float32 textures
		// on all devices
		let size = vec2f( textureDimensions( tex, 0 ) );

		// the path tracer stores its color rows top down
		let pxCoord = vec2f( coord.x, 1.0 - coord.y ) * size - 0.5;
		let px = vec2i( floor( pxCoord ) );
		let fr = fract( pxCoord );

		// get the four sibling samples
		let s00 = textureLoad( tex, clamp( px,                 vec2i( 0 ), vec2i( size ) - 1 ), 0 );
		let s10 = textureLoad( tex, clamp( px + vec2i( 1, 0 ), vec2i( 0 ), vec2i( size ) - 1 ), 0 );
		let s01 = textureLoad( tex, clamp( px + vec2i( 0, 1 ), vec2i( 0 ), vec2i( size ) - 1 ), 0 );
		let s11 = textureLoad( tex, clamp( px + vec2i( 1, 1 ), vec2i( 0 ), vec2i( size ) - 1 ), 0 );

		// interpolate on the x axis
		let y0 = mix( s00, s10, fr.x );
		let y1 = mix( s01, s11, fr.x );

		// then y
		return mix( y0, y1, fr.y );

	}
`),Uo=class extends Bx{get texture(){return this._texNode.value}set texture(e){this._texNode.value=e}get fromTexture(){return this._fromTexNode.value}set fromTexture(e){this._fromTexNode.value=e}get toneMapping(){return this._toneMapping.toneMapping}set toneMapping(e){this._toneMapping.setToneMapping(e)}get transition(){return this._transitionUniform.value}set transition(e){this._transitionUniform.value=e}get exposure(){return this._toneMapping.exposureNode.value}set exposure(e){this._toneMapping.exposureNode.value=e}constructor(e){super();let t=nf(new rf);this._texNode=t;let r=nf(new rf);this._fromTexNode=r;let i=Mx(Ex()),s=Fx(1);this._transitionUniform=s;let n=$`
			fn fade( uv: vec2f ) -> vec4f {

				if ( ${s} <= 0.0 ) {

					return ${Oo}( ${r}, uv );

				} else if ( ${s} >= 1.0 ) {

					return ${Oo}( ${t}, uv );

				} else {

					let col0 = ${Oo}( ${r}, uv );
					let col1 = ${Oo}( ${t}, uv );
					return mix( col0, col1, ${s} );

				}

			}
		`,l=Nx(Rx,1,n(i));this._toneMapping=l,this.transparent=!0,this.colorNode=Dx(l.rgb,l.a),this.setValues(e)}};import{Matrix4 as of,StructTypeNode as Lx}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as ci,uint as Ox,float as sf}from"/vendor/three/0.187.0/three.tsl.js";var El=$`
	fn debugHeatColor( t: f32 ) -> vec3f {

		let r = clamp( 1.5 - abs( 4.0 * t - 3.0 ), 0.0, 1.0 );
		let g = clamp( 1.5 - abs( 4.0 * t - 2.0 ), 0.0, 1.0 );
		let b = clamp( 1.5 - abs( 4.0 * t - 1.0 ), 0.0, 1.0 );
		return vec3f( r, g, b );

	}
`,zo=new Lx({tlasCount:"uint",blasCount:"uint",didHit:"bool",dist:"float"},"DebugBoundsResult");function af(o){let{transforms:e,index:t,attributes:r,materials:i}=o.storage,s=ci(new of),n=ci(new of),l=ci(1),a=ci(1),p=ci(0),u=ci(64),c=Ox(0).toVar("bvh_debugBoundsLevel"),f=sf(1).toVar("bvh_debugRayScalar"),h=sf(0).toVar("bvh_debugMaterialSide"),d=$`
		fn debugGetBoundsOrder( ray: ${D}, splitAxis: u32, node: ${Ge} ) -> bool {

			return ray.direction[ splitAxis ] >= 0.0;

		}
	`,m=$`
		fn debugIntersectsBounds( ray: ${D}, bounds: ${Pe}, result: ptr<function, ${zo}> ) -> u32 {

			let boundsMin = vec3( bounds.min[ 0 ], bounds.min[ 1 ], bounds.min[ 2 ] );
			let boundsMax = vec3( bounds.max[ 0 ], bounds.max[ 1 ], bounds.max[ 2 ] );

			let invDir = 1.0 / ray.direction;
			let tMinPlane = ( boundsMin - ray.origin ) * invDir;
			let tMaxPlane = ( boundsMax - ray.origin ) * invDir;

			let tMinHit = min( tMinPlane, tMaxPlane );
			let tMaxHit = max( tMinPlane, tMaxPlane );

			let t0 = max( max( tMinHit.x, tMinHit.y ), tMinHit.z );
			let t1 = min( min( tMaxHit.x, tMaxHit.y ), tMaxHit.z );

			let dist = max( t0, 0.0 );
			if ( t1 < dist ) {

				return 0u;

			} else if ( result.didHit && dist * ${f} >= result.dist ) {

				return 0u;

			}

			if ( ${c} == 1u ) {

				result.blasCount = result.blasCount + 1u;

			} else {

				result.tlasCount = result.tlasCount + 1u;

			}

			return 1u;

		}
	`,x=$`
		fn debugIntersectRange( ray: ${D}, offset: u32, count: u32, result: ptr<function, ${zo}> ) -> bool {

			var didHit = false;
			if ( ${p} > 0.5 ) {

				for ( var ti = offset; ti < offset + count; ti = ti + 1u ) {

					let i0 = ${t}[ ti * 3u ];
					let i1 = ${t}[ ti * 3u + 1u ];
					let i2 = ${t}[ ti * 3u + 2u ];

					let a = ${r}[ i0 ].position.xyz;
					let b = ${r}[ i1 ].position.xyz;
					let c = ${r}[ i2 ].position.xyz;

					var triResult = ${bl}( ray, a, b, c );
					triResult.dist *= ${f};
					if ( triResult.didHit && ( ! result.didHit || triResult.dist < result.dist ) ) {

						// cull faces that don't match the material side
						if ( ${h} != 0.0 && triResult.side != ${h} ) {

							continue;

						}

						result.didHit = true;
						result.dist = triResult.dist;
						didHit = true;

					}

				}

			}

			return didHit;

		}
	`,v=$`
		fn debugTransformRay( ray: ptr<function, ${D}>, objectIndex: u32 ) -> void {

			let object = ${e}[ objectIndex ];
			let toLocal = object.inverseMatrixWorld;
			ray.origin = ( toLocal * vec4f( ray.origin, 1.0 ) ).xyz;
			ray.direction = ( toLocal * vec4f( ray.direction, 0.0 ) ).xyz;

			let len = length( ray.direction );
			ray.direction = ray.direction / len;
			${f} = 1.0 / len;

			${h} = ${i}[ object.materialIndex ].side;
			${c} = 1u;

		}
	`,g=$`
		fn debugResetLevel( objectIndex: u32 ) -> void {

			${f} = 1.0;
			${c} = 0u;

		}
	`,y=o.getShapecastFn({name:"debugBoundsCount",shapeStruct:D,resultStruct:zo,boundsOrderFn:d,intersectsBoundsFn:m,intersectRangeFn:x,transformShapeFn:v,resetShapeFn:g}),w=$`
		fn debugBounds( vUv: vec2f ) -> vec4f {

			let ndc = vUv * 2.0 - vec2f( 1.0 );
			var ray = ${sr}( ndc, ${s} * ${n} );
			ray.direction = normalize( ray.direction );

			// count the boxes the ray crosses, pruning those behind the nearest hit surface
			var result: ${zo};
			${y}( ray, &result );

			let tlas = select( 0u, result.tlasCount, ${l} > 0.5 );
			let blas = select( 0u, result.blasCount, ${a} > 0.5 );
			let count = tlas + blas;

			let t = clamp( f32( count ) / max( ${u}, 1.0 ), 0.0, 1.0 );

			return vec4f( ${El}( t ), 1.0 );

		}
	`;return w.uniforms={cameraToModelMatrix:s,inverseProjectionMatrix:n,displayTLAS:l,displayBLAS:a,stopAtSurface:p,saturationCount:u},w}import{Matrix4 as fd,Vector2 as dd}from"/vendor/three/0.187.0/three.webgpu.js";import{DataTexture as Yf,Vector2 as jf}from"/vendor/three/0.187.0/three.webgpu.js";var z=class{get computeNode(){return this.kernel.computeNode}get workgroupSize(){return this.kernel.workgroupSize}set needsUpdate(e){this.setWorkgroupSize(...this.workgroupSize)}constructor(e,t={}){let{workgroupSize:r=[64],context:i={}}=t;this._fn=e,this.kernel=null,this.context=i,this.setWorkgroupSize(...r)}defineUniformAccessors(e){for(let t in e){if(t in this)throw new Error(`ComputeNode: Uniform name ${t} is already defined.`);let r=e[t];r.isStorageBufferNode&&!r.name&&r.setName(`kernel_${t}`),"value"in r&&Object.defineProperty(this,t,{get(){return e[t].value},set(i){e[t].value=i}})}}setWorkgroupSize(e=64,t=1,r=1){let i=this._fn.context(this.context);return i.parameters=this._fn.parameters,this.kernel?.dispose(),this.kernel=i.computeKernel([e,t,r]),this}dispose(){this.kernel?.dispose(),this.kernel=null}getDispatchSize(e=1,t=1,r=1,i=[]){let[s,n,l]=this.workgroupSize;return i.length=3,i[0]=Math.ceil(e/s),i[1]=Math.ceil(t/n),i[2]=Math.ceil(r/l),i}};import{ColorManagement as Wx,FloatType as Vx,HalfFloatType as Gx,RGBAFormat as uf}from"/vendor/three/0.187.0/three.webgpu.js";import{RedIntegerFormat as cf,StorageArrayTexture as qx,StorageTexture as Fl,UnsignedIntType as pf}from"/vendor/three/0.187.0/three.webgpu.js";import{wgsl as Ux}from"/vendor/three/0.187.0/three.tsl.js";import{StructTypeNode as Ct}from"/vendor/three/0.187.0/three.webgpu.js";var q=Ux(`

	const PI: f32 = 3.141592653589793;
	const EPSILON: f32 = 1e-5;
	const MIN_ROUGHNESS: f32 = 1e-3;
	const MIN_INCIDENT_COS: f32 = 1e-3;

`),ut=new Ct({color:"vec3f",isTransmissive:"bool",direction:"vec3f",pdf:"float"},"ScatterRecord"),Ml=new Ct({reflectance:"vec3f",transmittance:"vec3f",reflectProbability:"float"},"TransmissionFresnel"),Ho=new Ct({color:"vec3",map:"int",metalness:"float",metalnessMap:"int",roughness:"float",roughnessMap:"int",ior:"float",transmission:"float",transmissionMap:"int",emissiveIntensity:"float",emissive:"vec3",emissiveMap:"int",normalMap:"int",normalScale:"vec2",clearcoat:"float",clearcoatMap:"int",clearcoatNormalMap:"int",clearcoatNormalScale:"vec2",clearcoatRoughness:"float",clearcoatRoughnessMap:"int",iridescenceMap:"int",iridescenceThicknessMap:"int",iridescence:"float",iridescenceIor:"float",iridescenceThicknessMinimum:"float",iridescenceThicknessMaximum:"float",diffuseRoughness:"float",dispersion:"float",specularColor:"vec3",specularColorMap:"int",specularIntensity:"float",specularIntensityMap:"int",thinWall:"int",attenuationColor:"vec3",attenuationDistance:"float",alphaMap:"int",castShadow:"int",opacity:"float",alphaTest:"float",side:"float",matte:"int",sheen:"float",sheenColor:"vec3",sheenColorMap:"int",sheenRoughness:"float",sheenRoughnessMap:"int",vertexColors:"int",flatShading:"int",transparent:"int",fogVolume:"int",anisotropy:"float",anisotropyRotation:"float",anisotropyMap:"int",mapTransform:"mat3",metalnessMapTransform:"mat3",roughnessMapTransform:"mat3",transmissionMapTransform:"mat3",emissiveMapTransform:"mat3",normalMapTransform:"mat3",clearcoatMapTransform:"mat3",clearcoatNormalMapTransform:"mat3",clearcoatRoughnessMapTransform:"mat3",sheenColorMapTransform:"mat3",sheenRoughnessMapTransform:"mat3",iridescenceMapTransform:"mat3",iridescenceThicknessMapTransform:"mat3",specularColorMapTransform:"mat3",specularIntensityMapTransform:"mat3",alphaMapTransform:"mat3",anisotropyMapTransform:"mat3",multiscatterColor:"vec3",scatterStrength:"float",scatterAnisotropy:"float"},"Material"),_e=new Ct({volumeParticle:"bool",faceNormal:"vec3f",frontFace:"bool",normal:"vec3f",normalBasis:"mat3x3f",normalInvBasis:"mat3x3f",eta:"f32",f0:"f32",roughness:"f32",diffuseRoughness:"f32",metalness:"f32",anisotropy:"f32",color:"vec3f",emission:"vec3f",opacity:"f32",ior:"f32",transmission:"f32",thinWall:"bool",attenuationColor:"vec3f",attenuationDistance:" f32",clearcoatNormal:"vec3f",clearcoatBasis:"mat3x3f",clearcoatInvBasis:"mat3x3f",clearcoat:"f32",clearcoatRoughness:"f32",sheen:"f32",sheenColor:"vec3f",sheenRoughness:"f32",iridescence:"f32",iridescenceIor:"f32",iridescenceThickness:"f32",specularColor:"vec3f",specularIntensity:"f32"},"SurfaceRecord"),iI=new Ct({rotation:"mat3x3f",intensity:"float",blur:"float",totalSum:"float"},"EnvironmentInfo"),Dl=new Ct({color:"vec3f",direction:"vec3f",pdf:"float"},"EnvironmentSample"),zt=new Ct({position:"vec3f",lightType:"int",color:"vec3f",intensity:"float",u:"vec3f",visibleToCameraRays:"int",v:"vec3f",area:"float",radius:"float",decay:"float",distance:"float",coneCos:"float",penumbraCos:"float",iesProfile:"int"},"Light"),qe=new Ct({dist:"float",direction:"vec3f",pdf:"float",emission:"vec3f",lightType:"int"},"LightRecord"),lf=new Ct({diffuse:"float",specular:"float",transmission:"float",clearcoat:"float"},"LobeWeights"),Nl=new Ct({V:"vec3f",L:"vec3f",H:"vec3f",VdotH:"float"},"BxDFContext");var Re=3,zx=.02,Hx=.98,Wo=$`

	fn isAuxSurface( surface: ${_e}, scatterRec: ${ut} ) -> bool {

		let isMirror = surface.roughness <= ${zx} && surface.metalness >= ${Hx};
		return ! scatterRec.isTransmissive && ! isMirror;

	}

`,$n=$`

	fn unpackAuxAlbedo( packed: vec3u ) -> vec3f {

		return vec3f( unpack2x16float( packed.x ), unpack2x16float( packed.y ).x );

	}

`,Cn=$`

	fn unpackAuxNormal( packed: vec3u ) -> vec3f {

		return vec3f( unpack2x16float( packed.z ), unpack2x16float( packed.y ).y );

	}

`,Vo=$`

	fn blendAux( packed: vec3u, albedo: vec3f, normal: vec3f, weight: f32 ) -> vec3u {

		let meanAlbedo = mix( ${$n}( packed ), albedo, weight );
		let meanNormal = mix( ${Cn}( packed ), normal * 0.5 + 0.5, weight );
		return vec3u(
			pack2x16float( meanAlbedo.rg ),
			pack2x16float( vec2f( meanAlbedo.b, meanNormal.z ) ),
			pack2x16float( meanNormal.xy ),
		);

	}

`;function Ht(o=""){let e=new Fl(1,1);return e.format=uf,e.type=Vx,e.colorSpace=Wx.workingColorSpace,e.name=o,e.generateMipmaps=!1,e}function ar(o=""){let e=new Fl(1,1);return e.format=cf,e.type=pf,e.name=o,e.generateMipmaps=!1,e}function ct(o=""){let e=new qx(1,1,Re);return e.format=cf,e.type=pf,e.name=o,e.generateMipmaps=!1,e}function pi(o=""){let e=new Fl(1,1);return e.format=uf,e.type=Gx,e.name=o,e.generateMipmaps=!1,e}import{texture as c0,sampler as p0,uniform as je,globalId as f0,textureStore as Ps}from"/vendor/three/0.187.0/three.tsl.js";var Yo={};ag(Yo,{rand1:()=>Ll,rand2:()=>Ol,rand3:()=>Ul,rand4:()=>zl,rngInit:()=>Hl,rngNextBounce:()=>Wl});import{uint as qo,float as Xx,wgsl as Yx,wgslFn as An}from"/vendor/three/0.187.0/three.tsl.js";var ff=qo(256*256),hf=Xx(1/16777216),jx=Yx(`

	const SOBOL_DIRECTIONS_1 = array<u32, 32>(
		0x80000000u, 0xc0000000u, 0xa0000000u, 0xf0000000u,
		0x88000000u, 0xcc000000u, 0xaa000000u, 0xff000000u,
		0x80800000u, 0xc0c00000u, 0xa0a00000u, 0xf0f00000u,
		0x88880000u, 0xcccc0000u, 0xaaaa0000u, 0xffff0000u,
		0x80008000u, 0xc000c000u, 0xa000a000u, 0xf000f000u,
		0x88008800u, 0xcc00cc00u, 0xaa00aa00u, 0xff00ff00u,
		0x80808080u, 0xc0c0c0c0u, 0xa0a0a0a0u, 0xf0f0f0f0u,
		0x88888888u, 0xccccccccu, 0xaaaaaaaau, 0xffffffffu
	);

	const SOBOL_DIRECTIONS_2 = array<u32, 32>(
		0x80000000u, 0xc0000000u, 0x60000000u, 0x90000000u,
		0xe8000000u, 0x5c000000u, 0x8e000000u, 0xc5000000u,
		0x68800000u, 0x9cc00000u, 0xee600000u, 0x55900000u,
		0x80680000u, 0xc09c0000u, 0x60ee0000u, 0x90550000u,
		0xe8808000u, 0x5cc0c000u, 0x8e606000u, 0xc5909000u,
		0x6868e800u, 0x9c9c5c00u, 0xeeee8e00u, 0x5555c500u,
		0x8000e880u, 0xc0005cc0u, 0x60008e60u, 0x9000c590u,
		0xe8006868u, 0x5c009c9cu, 0x8e00eeeeu, 0xc5005555u
	);

	const SOBOL_DIRECTIONS_3 = array<u32, 32>(
		0x80000000u, 0xc0000000u, 0x20000000u, 0x50000000u,
		0xf8000000u, 0x74000000u, 0xa2000000u, 0x93000000u,
		0xd8800000u, 0x25400000u, 0x59e00000u, 0xe6d00000u,
		0x78080000u, 0xb40c0000u, 0x82020000u, 0xc3050000u,
		0x208f8000u, 0x51474000u, 0xfbea2000u, 0x75d93000u,
		0xa0858800u, 0x914e5400u, 0xdbe79e00u, 0x25db6d00u,
		0x58800080u, 0xe54000c0u, 0x79e00020u, 0xb6d00050u,
		0x800800f8u, 0xc00c0074u, 0x200200a2u, 0x50050093u
	);

	const SOBOL_DIRECTIONS_4 = array<u32, 32>(
		0x80000000u, 0x40000000u, 0x20000000u, 0xb0000000u,
		0xf8000000u, 0xdc000000u, 0x7a000000u, 0x9d000000u,
		0x5a800000u, 0x2fc00000u, 0xa1600000u, 0xf0b00000u,
		0xda880000u, 0x6fc40000u, 0x81620000u, 0x40bb0000u,
		0x22878000u, 0xb3c9c000u, 0xfb65a000u, 0xddb2d000u,
		0x78022800u, 0x9c0b3c00u, 0x5a0fb600u, 0x2d0ddb00u,
		0xa2878080u, 0xf3c9c040u, 0xdb65a020u, 0x6db2d0b0u,
		0x800228f8u, 0x400b3cdcu, 0x200fb67au, 0xb00ddb9du
	);

`),mf=qo(0).toVar("sobolPixelIndex"),gf=qo(0).toVar("sobolPathIndex"),kl=qo(0).toVar("sobolBounceIndex"),Go=An(`
	fn getMaskedSobol( index: u32, directions: array<u32, 32> ) -> u32 {

		var X = 0u;
		for ( var bit = 0u; bit < 32u; bit ++ ) {

			let mask = ( index >> bit ) & 1u;
			X ^= mask * directions[ bit ];

		}
		return X;

	}
`),Kx=(o=1)=>{if(o<=0)return;let e=o>1?`vec${o}u`:"u32",t=An(`
		fn sobolReverseBits_${e}( in: ${e} ) -> ${e} {

			var x = in;
			x = ( ( ( x & ${e}( 0xaaaaaaaau ) ) >> ${e}( 1 ) ) | ( ( x & ${e}( 0x55555555u ) ) << ${e}( 1 ) ) );
			x = ( ( ( x & ${e}( 0xccccccccu ) ) >> ${e}( 2 ) ) | ( ( x & ${e}( 0x33333333u ) ) << ${e}( 2 ) ) );
			x = ( ( ( x & ${e}( 0xf0f0f0f0u ) ) >> ${e}( 4 ) ) | ( ( x & ${e}( 0x0f0f0f0fu ) ) << ${e}( 4 ) ) );
			x = ( ( ( x & ${e}( 0xff00ff00u ) ) >> ${e}( 8 ) ) | ( ( x & ${e}( 0x00ff00ffu ) ) << ${e}( 8 ) ) );
			return ( ( x >> ${e}( 16 ) ) | ( x << ${e}( 16 ) ) );

		}
	`),r=An(`
		fn sobolHashCombine_${e}( seed: u32, v: ${e} ) -> ${e} {

			return ${e}( seed ) ^ ( v + ${e}( ( seed << 6 ) + ( seed >> 2 ) ) );

		}
	`),i=An(`
		fn sobolLaineKarrasPermutation_${e}( in: ${e}, seed: ${e} ) -> ${e} {

			var x = in;
			x += seed;
			x ^= x * 0x6c50b47cu;
			x ^= x * 0xb82f1e52u;
			x ^= x * 0xc7afe638u;
			x ^= x * 0x8d22f6e6u;
			return x;

		}
	`),s=$`
		fn nestedUniformScrambleBase2_${e}( x: ${e}, seed: ${e} ) -> ${e} {

			var res = ${i}( x, seed );
			res = ${t}( res );
			return res;

		}
	`;return{reverseBits:t,hashCombine:r,lkPermutation:i,scramble:s}},pt=Array.from({length:5},(o,e)=>Kx(e)),Qx=$`
  	${[jx]}
	fn generateSobolPoint( id: u32 ) -> vec4f {

		var index = id;
		if ( index >= ${ff} ) {

			index = index % ${ff};
			// return vec4( 0.0 );

		}

		// NOTE: this sobol "direction" is also available but we can't write out 5 components
		// uint x = index & 0x00ffffffu;
		let x = ${pt[1].reverseBits}( ${Go}( index, SOBOL_DIRECTIONS_1 ) ) & 0x00ffffffu;
		let y = ${pt[1].reverseBits}( ${Go}( index, SOBOL_DIRECTIONS_2 ) ) & 0x00ffffffu;
		let z = ${pt[1].reverseBits}( ${Go}( index, SOBOL_DIRECTIONS_3 ) ) & 0x00ffffffu;
		let w = ${pt[1].reverseBits}( ${Go}( index, SOBOL_DIRECTIONS_4 ) ) & 0x00ffffffu;

		return vec4( f32( x ), f32( y ), f32( z ), f32( w ) ) * ${hf};

	}
`,df=An(`
	fn sobolHash( in: u32 ) -> u32 {

		var x = in;
		// finalizer from murmurhash3
		x ^= x >> 16;
		x *= 0x85ebca6bu;
		x ^= x >> 13;
		x *= 0xc2b2ae35u;
		x ^= x >> 16;
		return x;

	}
`),Zx=$`
	fn sobolGetSeed( effect: u32 ) -> u32 {

		return ${df}(
			${pt[1].hashCombine}(
				${pt[1].hashCombine}(
					${df}( ${kl} ),
					${mf}
				),
				effect
			)
		);

	}
`,Xo=(o=1,e=Qx)=>{if(o<=0)return;let t=o>1?`vec${o}u`:"u32",r=o>1?`vec${o}f`:"f32",i=".r",s="1u";return o===2?(i=".rg",s="vec2( 1u, 2u )"):o===3?(i=".rgb",s="vec3( 1u, 2u, 3u )"):o===4&&(i="",s="vec4( 1u, 2u, 3u, 4u )"),$`
		fn sobol${o}( effect: u32 ) -> ${r} {

			let seed = ${Zx}( effect );
			let index = ${gf};

			let shuffle_seed = ${pt[1].hashCombine}( seed, 0u );
			let shuffled_index = ${pt[1].scramble}( ${pt[1].reverseBits}( index ), shuffle_seed );
			let sobol_pt = ${e}( shuffled_index )${i};
			var result = ${t}( sobol_pt * 16777216.0 );

			let seed2 = ${pt[o].hashCombine}( seed, ${s} );
			result = ${pt[o].scramble}( result, seed2 );

			return ${hf} * ${r}( result >> ${t}( 8 ) );

		}
	`},Ll=Xo(1),Ol=Xo(2),Ul=Xo(3),zl=Xo(4),Hl=$`
	fn sobolInit( pixel: vec2u, pathIndex: u32, bounceIndex: u32 ) -> void {

		${mf} = ( pixel.x << 16 ) | pixel.y;
		${gf} = pathIndex;
		${kl} = bounceIndex;

	}
`,Wl=$`
	fn sobolNextBounce() -> void {

		${kl}++;

	}
`;var jo=0,Ko=1,xf=2,Qo=3,yf=4,Zo=5,Jo=6,es=7,ts=8,rs=9,is=50,Xe=$e("random.rngInit",Hl),fi=$e("random.rngNextBounce",Wl),At=$e("random.rand1",Ll),Ee=$e("random.rand2",Ol),di=$e("random.rand3",Ul),mI=$e("random.rand4",zl);import{wgslFn as ft}from"/vendor/three/0.187.0/three.tsl.js";import{wgslFn as Te}from"/vendor/three/0.187.0/three.tsl.js";var bf=Te(`

	fn inverse(m: mat3x3f) -> mat3x3f {
		var adj: mat3x3f;
		adj[0][0] =   (m[1][1] * m[2][2] - m[2][1] * m[1][2]);
		adj[1][0] = - (m[1][0] * m[2][2] - m[2][0] * m[1][2]);
		adj[2][0] =   (m[1][0] * m[2][1] - m[2][0] * m[1][1]);
		adj[0][1] = - (m[0][1] * m[2][2] - m[2][1] * m[0][2]);
		adj[1][1] =   (m[0][0] * m[2][2] - m[2][0] * m[0][2]);
		adj[2][1] = - (m[0][0] * m[2][1] - m[2][0] * m[0][1]);
		adj[0][2] =   (m[0][1] * m[1][2] - m[1][1] * m[0][2]);
		adj[1][2] = - (m[0][0] * m[1][2] - m[1][0] * m[0][2]);
		adj[2][2] =   (m[0][0] * m[1][1] - m[1][0] * m[0][1]);

		let det = ( m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
			- m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
			+ m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]));

		return adj * ( 1.0 / det );
	}

`),wf=Te(`

	fn getBasisFromNormal( normal: vec3f ) -> mat3x3f {

		var other: vec3f;
		if ( abs( normal.x ) > 0.5 ) {

			other = vec3f( 0.0, 1.0, 0.0 );

		} else {

			other = vec3f( 1.0, 0.0, 0.0 );

		}

		let ortho = normalize( cross( normal, other ) );
		let ortho2 = normalize( cross( normal, ortho ) );
		return mat3x3f( ortho2, ortho, normal );

	}

`),Ye=Te(`

	fn iorToF0( ior: f32 ) -> f32 {
		return pow( ( 1 - ior ) / ( 1 + ior ), 2 );
	}

`),_f=Te(`

	fn iorToF0General( transmittedIor: f32, incidentIor: f32 ) -> f32 {

		return pow( ( transmittedIor - incidentIor ) / ( transmittedIor + incidentIor ), 2 );

	}

`),Tf=Te(`

	fn iorToF0GeneralVec( transmittedIor: vec3f, incidentIor: vec3f ) -> vec3f {

		let v = ( transmittedIor - incidentIor ) / ( transmittedIor + incidentIor );
		return v * v;

	}

`),Sf=Te(`

	fn fresnel0ToIor( f0: vec3f ) -> vec3f {

		let sqrtF0 = sqrt( f0 );
		return ( vec3( 1.0 ) + sqrtF0 ) / ( vec3( 1.0 ) - sqrtF0 );

	}

`),Pt=Te(`

	fn schlickFresnel( cosine: f32, f0: f32 ) -> f32 {

		return f0 + ( 1.0 - f0 ) * pow( 1.0 - cosine, 5.0 );

	}

`),hi=Te(`

	fn schlickFresnelVec( cosine: f32, f0: vec3f, f90: vec3f ) -> vec3f {

		return f0 + ( f90 - f0 ) * pow( 1.0 - cosine, 5.0 );

	}

`),os=Te(`

	fn totalInternalReflection( cosTheta: f32, eta: f32 ) -> bool {

		let sinTheta = sqrt( 1.0 - cosTheta * cosTheta );
		return eta * sinTheta > 1.0;

	}

`),If=Te(`

	fn totalInternalReflectionVec( cosTheta: f32, eta: vec3f ) -> vec3<bool> {

		let sinTheta = sqrt( 1.0 - cosTheta * cosTheta );
		return eta * sinTheta > vec3f( 1.0 );

	}

`),bI=Te(`

	fn evaluateFresnel( cosine: f32, eta: f32, f0: vec3f, f90: vec3f ) -> vec3f {

		if ( totalInternalReflection( cosine, eta ) ) {

			return f90;

		}

		return f0 + ( f90 - f0 ) * pow( 1.0 - cosine, 5.0 );
	}

`,[os]),$r=Te(`

	fn dielectricFresnel( cosThetaI: f32, eta: f32 ) -> f32 {

		// https://schuttejoe.github.io/post/disneybsdf/
		let ni = eta;
		let nt = 1.0;

		// Check for total internal reflection
		let sinThetaISq = 1.0 - cosThetaI * cosThetaI;
		let sinThetaTSq = eta * eta * sinThetaISq;
		if ( sinThetaTSq >= 1.0 ) {

			return 1.0;

		}

		let sinThetaT = sqrt( sinThetaTSq );
		let cosThetaT = sqrt( max( 0.0, 1.0 - sinThetaT * sinThetaT ) );
		let rParallel = ( ( nt * cosThetaI ) - ( ni * cosThetaT ) ) / ( ( nt * cosThetaI ) + ( ni * cosThetaT ) );
		let rPerpendicular = ( ( ni * cosThetaI ) - ( nt * cosThetaT ) ) / ( ( ni * cosThetaI ) + ( nt * cosThetaT ) );
		return ( rParallel * rParallel + rPerpendicular * rPerpendicular ) / 2.0;

	}

`),$f=Te(`

	fn disneyFresnel( wo: vec3f, wi: vec3f, wh: vec3f, f0: f32, eta: f32, metalness: f32 ) -> f32 {

		let dotHV = dot( wo, wh );
		if ( totalInternalReflection( dotHV, eta ) ) {

			return 1.0;

		}

		let dotHL = dot( wi, wh );
		let dielectricF = dielectricFresnel( abs( dotHV ), eta );
		let metallicF = schlickFresnel( dotHL, f0 );

		return mix( dielectricF, metallicF, metalness );

	}

`,[os,$r,Pt]),mi=Te(`

	fn isTerminatingScatter( scatterRec: ScatterRecord ) -> bool {

		return scatterRec.pdf <= 0;

	}

`,[ut]),Se=Te(`

	fn clampPathContribution( contribution: vec3f, pathDepth: u32, clampDirect: f32, clampIndirect: f32 ) -> vec3f {

		let limit = select( clampIndirect, clampDirect, pathDepth <= 1u ) * 3.0;
		if ( limit <= 0.0 ) {

			return contribution;

		}

		let strength = dot( abs( contribution ), vec3f( 1.0 ) );
		if ( strength > limit ) {

			return contribution * ( limit / strength );

		}

		return contribution;

	}

`),vf=Te(`

	fn applyWrap( v: f32, wrapMode: i32 ) -> f32 {

		var res = v;
		if ( wrapMode == 1 ) {

			// ClampToEdge
			res = clamp( res, 0.0, 1.0 );

		} else if ( wrapMode == 2 ) {

			// MirroredRepeat
			res = 1.0 - abs( 2.0 * fract( res * 0.5 ) - 1.0 );

		} else {

			// Repeat
			res = fract( res );

		}

		return res;

	}

`),Wt=Te(`

	fn offsetRayOrigin( point: vec3f, direction: vec3f, geometricNormal: vec3f ) -> vec3f {

		let normal = normalize( select( -geometricNormal, geometricNormal, dot( direction, geometricNormal ) >= 0.0 ) );
		let intScale = 256.0;
		let integerOffset = vec3i( intScale * normal );
		let pointBits = bitcast<vec3i>( point );
		let signedOffset = select( integerOffset, -integerOffset, point < vec3f( 0.0 ) );
		let offsetPoint = bitcast<vec3f>( pointBits + signedOffset );
		let origin = 1.0 / 32.0;
		let floatScale = 1.0 / 65536.0;
		return select( offsetPoint, point + floatScale * normal, abs( point ) < vec3f( origin ) );

	}

`),ns=Te(`

	fn wrapTexelIndex( i: i32, size: i32, wrapMode: i32 ) -> i32 {

		if ( wrapMode == 0 ) {

			// Repeat wraps to the opposite edge
			return ( ( i % size ) + size ) % size;

		}

		// ClampToEdge and MirroredRepeat both fold back onto the edge texel
		return clamp( i, 0, size - 1 );

	}

`),ss=(o,e,t="sampleTexel")=>$`

	fn ${t}( uv: vec2f, packed: i32, lod: f32 ) -> vec4f {

		let texIndex = packed & 0x7FFFFF;
		let wrapS    = ( packed >> 26 ) & 0x3;
		let wrapT    = ( packed >> 28 ) & 0x3;
		let nearest  = ( packed >> 30 ) & 0x1;

		// look up the texture's rect and page within the atlas. three wraps a
		// uniformArray in a struct ( textureInfoStruct ) with a "value" array member.
		let info   = ${o}[ u32( texIndex ) ];
		let offset = vec2f( vec2u( info.x & 0xFFFFu, info.x >> 16u ) );
		let size   = vec2f( vec2u( info.y & 0xFFFFu, info.y >> 16u ) );
		let page   = i32( info.z & 0xFFFFu );

		// wrap is applied on the logical uv first, then remapped into the tile
		let wrappedUv = vec2f(
			${vf}( uv.x, wrapS ),
			${vf}( uv.y, wrapT ),
		);

		if ( nearest == 1 ) {

			let tileTexel = clamp( vec2i( wrappedUv * size ), vec2i( 0 ), vec2i( size ) - vec2i( 1 ) );
			return textureLoad( ${e}, vec2i( offset ) + tileTexel, page, 0 );

		} else {

			// The tile's neighbors in the atlas are unrelated textures, so hardware
			// filtering cannot supply the taps that fall outside it. Filter here instead
			// and resolve each tap through the wrap mode.
			let texelPos = wrappedUv * size - vec2f( 0.5 );
			let basePos = floor( texelPos );
			let base = vec2i( basePos );
			let f = texelPos - basePos;

			let intSize = vec2i( size );
			let x0 = ${ns}( base.x,     intSize.x, wrapS );
			let x1 = ${ns}( base.x + 1, intSize.x, wrapS );
			let y0 = ${ns}( base.y,     intSize.y, wrapT );
			let y1 = ${ns}( base.y + 1, intSize.y, wrapT );

			let tile = vec2i( offset );
			let c00 = textureLoad( ${e}, tile + vec2i( x0, y0 ), page, 0 );
			let c10 = textureLoad( ${e}, tile + vec2i( x1, y0 ), page, 0 );
			let c01 = textureLoad( ${e}, tile + vec2i( x0, y1 ), page, 0 );
			let c11 = textureLoad( ${e}, tile + vec2i( x1, y1 ), page, 0 );

			return mix(
				mix( c00, c10, f.x ),
				mix( c01, c11, f.x ),
				f.y,
			);

		}

	}
`;var Jx=ft(`

	fn sampleSphere( uv: vec2f ) -> vec3f {

		let u = ( uv.x - 0.5 ) * 2.0;
		let t = uv.y * PI * 2.0;
		let f = sqrt( 1.0 - u * u );

		return vec3f( f * cos( t ), f * sin( t ), u );

	}

`,[q]),Cf=ft(`

	fn diffuseDirection( wo: vec3f, uv: vec2f ) -> vec3f {

		var lightDirection = sampleSphere( uv );
		lightDirection.z += 1.0;
		lightDirection = normalize( lightDirection );

		return lightDirection;

	}

`,[Jx]),Vl=ft(`

	fn getLobeWeights( wo: vec3f, wi: vec3f, woClearcoat: vec3f, wh: vec3f, clearcoatIor: f32, surf: SurfaceRecord ) -> LobeWeights {

		var weights: LobeWeights;
		var remaining = 1.0;

		let clearcoatF0 = iorToF0( clearcoatIor );
		let clearcoatFresnel = schlickFresnel( saturate( woClearcoat.z ), clearcoatF0 );
		weights.clearcoat = surf.clearcoat * clearcoatFresnel;
		remaining -= weights.clearcoat;

		// this specular lobe only handles the opaque portion of the specular
		let fEstimate = disneyFresnel( wo, wi, wh, surf.f0, surf.eta, surf.metalness );
		weights.specular = remaining * ( surf.metalness + ( 1.0 - surf.metalness ) * ( 1.0 - surf.transmission ) * fEstimate );
		remaining -= weights.specular;

		// the transmission lobe covers both the reflected and refracted halves of the transmissive portion
		weights.transmission = remaining * surf.transmission;
		remaining -= weights.transmission;

		weights.diffuse = remaining;

		return weights;

	}

`,[$f,Pt,Ye,lf,q]),gi=ft(`

	fn equirectDirectionToUv(direction: vec3f) -> vec2f {

		// from Spherical.setFromCartesianCoords
		var uv = vec2f( atan2( direction.z, direction.x ), acos( direction.y ) );
		uv /= vec2f( 2.0 * PI, PI );

		// apply adjustments to get values in range [0, 1] and y right side up
		uv.x += 0.5;
		uv.y = 1.0 - uv.y;
		return uv;

	}

`),Af=ft(`

	fn sampleHemisphere( n: vec3f, uv: vec2f ) -> vec3f {

		// https://www.rorydriscoll.com/2009/01/07/better-sampling/
		// https://graphics.pixar.com/library/OrthonormalB/paper.pdf
		let sign = select( sign( n.z ), 1.0, n.z == 0.0 );
		let a = - 1.0 / ( sign + n.z );
		let b = n.x * n.y * a;
		let b1 = vec3( 1.0 + sign * n.x * n.x * a, sign * b, - sign * n.x );
		let b2 = vec3( b, sign + n.y * n.y * a, - n.y );

		let r = sqrt( uv.x );
		let theta = 2.0 * PI * uv.y;
		let x = r * cos( theta );
		let y = r * sin( theta );
		return x * b1 + y * b2 + sqrt( 1.0 - uv.x ) * n;

	}

`,[q]),e0=.001,as=ft(`

	fn sampleHenyeyGreenstein( d: vec3f, g: f32, uv: vec2f ) -> vec3f {

		var cosTheta = 1.0 - 2.0 * uv.x;
		if ( abs( g ) > ${e0} ) {

			let sqrTerm = ( 1.0 - g * g ) / ( 1.0 - g + 2.0 * g * uv.x );
			cosTheta = clamp( ( 1.0 + g * g - sqrTerm * sqrTerm ) / ( 2.0 * g ), - 1.0, 1.0 );

		}

		// orthonormal basis around "d", from https://graphics.pixar.com/library/OrthonormalB/paper.pdf
		let sign = select( sign( d.z ), 1.0, d.z == 0.0 );
		let a = - 1.0 / ( sign + d.z );
		let b = d.x * d.y * a;
		let b1 = vec3( 1.0 + sign * d.x * d.x * a, sign * b, - sign * d.x );
		let b2 = vec3( b, sign + d.y * d.y * a, - d.y );

		let sinTheta = sqrt( max( 0.0, 1.0 - cosTheta * cosTheta ) );
		let phi = 2.0 * PI * uv.y;
		return normalize( sinTheta * cos( phi ) * b1 + sinTheta * sin( phi ) * b2 + cosTheta * d );

	}

`,[q]),Bt=ft(`

	fn misHeuristic( a: f32, b: f32 ) -> f32 {

		// computed as a ratio so squaring large pdfs can't overflow to Inf / Inf
		if ( a >= b ) {

			let r = b / a;
			return 1.0 / ( 1.0 + r * r );

		}

		let r = a / b;
		return r * r / ( 1.0 + r * r );

	}

`),Pf=ft(`

	fn luminance( color: vec3f ) -> f32 {

		return dot( color, vec3f( 0.2126, 0.7152, 0.0722 ) );

	}

`),Bf=ft(`

	fn equirectUvToDirection( uvIn: vec2f ) -> vec3f {

		// undo the adjustments applied in equirectDirectionToUv
		var uv = uvIn;
		uv.x -= 0.5;
		uv.y = 1.0 - uv.y;

		let theta = uv.x * 2.0 * PI;
		let phi = uv.y * PI;
		let sinPhi = sin( phi );

		return vec3f( sinPhi * cos( theta ), cos( phi ), sinPhi * sin( theta ) );

	}

`,[q]),II=ft(`

	fn equirectDirectionPdf( direction: vec3f ) -> f32 {

		let uv = equirectDirectionToUv( direction );
		let theta = uv.y * PI;
		let sinTheta = sin( theta );
		if ( sinTheta == 0.0 ) {

			return 0.0;

		}

		return 1.0 / ( 2.0 * PI * PI * sinTheta );

	}

`,[q,gi]),ls=ft(`

	fn weightedAlphaBlend( prevColor: vec4f, newColor: vec4f, weight: f32 ) -> vec4f {

		let invWeight = 1.0 - weight;
		let totalAlpha = prevColor.a * invWeight + newColor.a * weight;
		var blendedColor = vec4f( 0 );
		if ( totalAlpha != 0.0 ) {

			let prevContrib = prevColor.rgb * invWeight * prevColor.a / totalAlpha;
			let resContrib = newColor.rgb * weight * newColor.a / totalAlpha;
			blendedColor = vec4f( prevContrib + resContrib, totalAlpha );

		}

		return blendedColor;

	}

`);import{StructTypeNode as kf}from"/vendor/three/0.187.0/three.webgpu.js";import{wgslFn as lr}from"/vendor/three/0.187.0/three.tsl.js";var us=0,cs=1,Gl=2,ql=3,Xl=4,Pn=5,et=1e30,ps=1e-5,fs=lr(`

	fn isMISWeightLight( lightType: i32 ) -> bool {

		return lightType == ${Pn} || lightType == ${cs} || lightType == ${us};

	}

`),Yl=lr(`

	fn getSpotAttenuation( coneCosine: f32, penumbraCosine: f32, angleCosine: f32 ) -> f32 {

		return smoothstep( coneCosine, penumbraCosine, angleCosine );

	}

`),ds=lr(`

	fn getDistanceAttenuation( lightDistance: f32, cutoffDistance: f32, decayExponent: f32 ) -> f32 {

		// based upon Frostbite 3 Moving to Physically-based Rendering
		// https://seblagarde.files.wordpress.com/2015/07/course_notes_moving_frostbite_to_pbr_v32.pdf
		var distanceFalloff = 1.0 / max( pow( lightDistance, decayExponent ), EPSILON );
		if ( cutoffDistance > 0.0 ) {

			let window = clamp( 1.0 - pow( lightDistance / cutoffDistance, 4.0 ), 0.0, 1.0 );
			distanceFalloff *= window * window;

		}

		return distanceFalloff;

	}

`,[q]),Rf=lr(`

	fn intersectsRectangle( center: vec3f, normal: vec3f, u: vec3f, v: vec3f, rayOrigin: vec3f, rayDirection: vec3f ) -> f32 {

		let t = dot( center - rayOrigin, normal ) / dot( rayDirection, normal );
		if ( t > EPSILON ) {

			let p = rayOrigin + rayDirection * t;
			let vi = p - center;

			let a1 = dot( u, vi );
			if ( abs( a1 ) <= 0.5 ) {

				let a2 = dot( v, vi );
				if ( abs( a2 ) <= 0.5 ) {

					return t;

				}

			}

		}

		return - 1.0;

	}

`,[q]),Ef=lr(`

	fn intersectsCircle( position: vec3f, normal: vec3f, u: vec3f, v: vec3f, rayOrigin: vec3f, rayDirection: vec3f ) -> f32 {

		let t = dot( position - rayOrigin, normal ) / dot( rayDirection, normal );
		if ( t > EPSILON ) {

			let hit = rayOrigin + rayDirection * t;
			let vi = hit - position;

			let a1 = dot( u, vi );
			let a2 = dot( v, vi );
			if ( length( vec2f( a1, a2 ) ) <= 0.5 ) {

				return t;

			}

		}

		return - 1.0;

	}

`,[q]),Mf=lr(`

	fn randomAreaLightSample( light: Light, rayOrigin: vec3f, ruv: vec2f ) -> LightRecord {

		var randomPos = vec3f( 0.0 );
		if ( light.lightType == ${us} ) {

			randomPos = light.position + light.u * ( ruv.x - 0.5 ) + light.v * ( ruv.y - 0.5 );

		} else if ( light.lightType == ${cs} ) {

			let r = 0.5 * sqrt( ruv.x );
			let theta = ruv.y * 2.0 * PI;
			randomPos = light.position + light.u * ( r * cos( theta ) ) + light.v * ( r * sin( theta ) );

		}

		let toLight = randomPos - rayOrigin;
		let lightDistSq = dot( toLight, toLight );
		let dist = sqrt( lightDistSq );
		let direction = toLight / dist;
		let lightNormal = normalize( cross( light.u, light.v ) );

		var lightRec: LightRecord;
		lightRec.lightType = light.lightType;
		lightRec.emission = light.color * light.intensity;
		lightRec.dist = dist;
		lightRec.direction = direction;

		// points behind or edge-on to the light receive no contribution
		let cosTheta = dot( direction, lightNormal );
		if ( cosTheta <= 0.0 ) {

			lightRec.pdf = 0.0;

		} else {

			lightRec.pdf = lightDistSq / ( light.area * cosTheta );

		}

		return lightRec;

	}

`,[zt,qe,q]),Df=lr(`

	fn randomSpotLightSample( light: Light, rayOrigin: vec3f, ruv: vec2f ) -> LightRecord {

		let radius = light.radius * sqrt( ruv.x );
		let theta = ruv.y * 2.0 * PI;
		let x = radius * cos( theta );
		let y = radius * sin( theta );

		let u = light.u;
		let v = light.v;
		let normal = normalize( cross( u, v ) );

		let angle = acos( light.coneCos );
		let angleTan = tan( angle );
		let startDistance = light.radius / max( angleTan, EPSILON );

		let randomPos = light.position - normal * startDistance + u * x + v * y;
		let toLight = randomPos - rayOrigin;
		let lightDistSq = dot( toLight, toLight );
		let dist = sqrt( lightDistSq );

		let direction = toLight / max( dist, EPSILON );
		let distanceAttenuation = getDistanceAttenuation( dist, light.distance, light.decay );

		var lightRec: LightRecord;
		lightRec.lightType = light.lightType;
		lightRec.dist = dist;
		lightRec.direction = direction;
		lightRec.emission = light.color * light.intensity * distanceAttenuation;
		lightRec.pdf = 1.0;

		return lightRec;

	}

`,[zt,qe,q,ds]),Nf=lr(`

	fn getSpotLightNearest( light: Light, position: vec3f ) -> vec2f {

		let normal = normalize( cross( light.u, light.v ) );
		let startDistance = light.radius / max( tan( acos( light.coneCos ) ), EPSILON );
		let discCenter = light.position - normal * startDistance;

		let toPosition = position - discCenter;
		let along = dot( toPosition, - normal );
		let across = max( length( toPosition + normal * along ) - light.radius, 0.0 );
		let dist = length( vec2f( along, across ) );
		return vec2f( along / max( dist, EPSILON ), dist );

	}

`,[zt,q]);var t0=1e-6,hs=1e-6,Ff=.999,Vt=new kf({inside:"bool",object:"uint",extinction:"vec3f",albedo:"vec3f",anisotropy:"float"},"Medium"),Cr=new kf({extinction:"vec3f",object:"uint",albedo:"vec3f",anisotropy:"float",inside:"uint"},"MediumState"),Lf=$`

	fn storeMedium( medium: ${Vt} ) -> ${Cr} {

		var state: ${Cr};
		state.extinction = medium.extinction;
		state.object = medium.object;
		state.albedo = medium.albedo;
		state.anisotropy = medium.anisotropy;
		state.inside = select( 0u, 1u, medium.inside );
		return state;

	}

`,ms=$`

	fn loadMedium( state: ${Cr} ) -> ${Vt} {

		var medium: ${Vt};
		medium.inside = state.inside != 0u;
		medium.object = state.object;
		medium.extinction = state.extinction;
		medium.albedo = state.albedo;
		medium.anisotropy = state.anisotropy;
		return medium;

	}

`,r0=$`

	fn singleScatterAlbedo( multiscatterAlbedo: vec3f, anisotropy: f32 ) -> vec3f {

		let a = multiscatterAlbedo;
		let s = clamp( 4.09712 + 4.20863 * a - sqrt( 9.59217 + 41.6808 * a + 17.7126 * a * a ), vec3f( 0.0 ), vec3f( 1.0 ) );
		let s2 = select( s * s, vec3f( 1.0 ), a <= vec3f( 0.0 ) );
		return ( 1.0 - s2 ) / ( 1.0 - anisotropy * s2 );

	}

`,gs=$`

	fn enterMedium( material: ${Ho}, object: u32 ) -> ${Vt} {

		let anisotropy = clamp( material.scatterAnisotropy, - ${Ff}, ${Ff} );

		var medium: ${Vt};
		medium.extinction = - log( max( material.attenuationColor, vec3f( ${t0} ) ) ) / material.attenuationDistance;
		medium.albedo = ${r0}( material.multiscatterColor * material.scatterStrength, anisotropy );
		medium.inside = any( medium.albedo > vec3f( 0.0 ) ) && any( medium.extinction > vec3f( 0.0 ) );
		medium.object = object;
		medium.anisotropy = anisotropy;
		return medium;

	}

`,xs=$`

	fn mediumChannelPdf( throughput: vec3f, albedo: vec3f ) -> vec3f {

		let weights = abs( throughput * albedo );
		let weightSum = weights.r + weights.g + weights.b;
		if ( weightSum > 0.0 ) {

			return weights / weightSum;

		}

		return vec3f( 1.0 / 3.0 );

	}

`,ys=$`

	fn sampleMediumDistance( medium: ${Vt}, channelPdf: vec3f, u: vec2f ) -> f32 {

		var channel = 2u;
		if ( u.x < channelPdf.r ) {

			channel = 0u;

		} else if ( u.x < channelPdf.r + channelPdf.g ) {

			channel = 1u;

		}

		// a channel with no extinction never scatters, and WGSL doesn't guarantee an infinite quotient
		let extinction = medium.extinction[ channel ];
		if ( extinction <= 0.0 ) {

			return ${et};

		}

		return - log( 1.0 - u.y ) / extinction;

	}

`,vs=$`

	fn mediumDistancePdf( medium: ${Vt}, dist: f32, scattered: bool ) -> vec3f {

		let transmittance = exp( - medium.extinction * dist );
		return select( transmittance, medium.extinction * transmittance, scattered );

	}

`;import{wgslFn as Le,mat3 as n0}from"/vendor/three/0.187.0/three.tsl.js";import{wgslFn as Ar}from"/vendor/three/0.187.0/three.tsl.js";var Pr=Ar(`

	fn ggxDirection( incidentDir: vec3f, alpha: vec2f, uv: vec2f ) -> vec3f {

		// Implementation from reference [1]
		// stretch view
		let V = normalize( vec3f( alpha * incidentDir.xy, incidentDir.z ) );

		// orthonormal basis
		var T1: vec3f;
		if ( V.z < 0.9999 ) {

			T1 = normalize( cross( V, vec3( 0.0, 0.0, 1.0 ) ) );

		} else {

			T1 = vec3( 1.0, 0.0, 0.0 );

		}

		let T2 = cross( T1, V );

		// sample point with polar coordinates (r, phi)
		let a = 1.0 / ( 1.0 + V.z );
		let r = sqrt( uv.x );
		var phi: f32;
		if ( uv.y < a ) {

			phi = uv.y / a * PI;

		} else {

			phi = PI + ( uv.y - a ) / ( 1.0 - a ) * PI;

		}

		let P1 = r * cos( phi );
		var P2 = r * sin( phi );
		if ( uv.y >= a ) {

			P2 *= V.z;

		}

		// compute normal
		var N = P1 * T1 + P2 * T2 + V * sqrt( max( 0.0, 1.0 - P1 * P1 - P2 * P2 ) );

		// unstretch
		N = normalize( vec3( alpha * N.xy, max( 0.0, N.z ) ) );

		return N;

	}

`,[q]),i0=Ar(`

	fn ggxLambda( V: vec3f, alpha: vec2f ) -> f32 {

		let alphaT = alpha.x;
		let alphaB = alpha.y;

		// abs v.z to support transmitted rays
		let NdotV = max( abs( V.z ), MIN_INCIDENT_COS );
		let cos2 = NdotV * NdotV;
		let t = ( alphaT * alphaT * V.x * V.x + alphaB * alphaB * V.y * V.y ) / cos2;
		let numerator = - 1.0 + sqrt( 1.0 + t );
		return numerator / 2.0;

	}

`,[q]),bs=Ar(`

	fn ggxShadowMaskG1( V: vec3f, alpha: vec2f ) -> f32 {

		// TODO: this could be collapsed to a simpler form as an optimization
		return 1.0 / ( 1.0 + ggxLambda( V, alpha ) );

	}

`,[i0]),Of=Ar(`

	fn ggxSmithVisibility( V: vec3f, L: vec3f, alpha: vec2f ) -> f32 {

		let alphaT = alpha.x;
		let alphaB = alpha.y;

		let NdotV = max( V.z, MIN_INCIDENT_COS );
		let NdotL = max( L.z, MIN_INCIDENT_COS );

		let TdotV = V.x;
		let TdotL = L.x;

		let BdotV = V.y;
		let BdotL = L.y;

		let GGXV = NdotL * length( vec3f( alphaT * TdotV, alphaB * BdotV, NdotV ) );
		let GGXL = NdotV * length( vec3f( alphaT * TdotL, alphaB * BdotL, NdotL ) );

		return 0.5 / ( GGXV + GGXL );

	}

`,[q]),Bn=Ar(`

	fn ggxDistribution( H: vec3f, alpha: vec2f ) -> f32 {

		let alphaT = alpha.x;
		let alphaB = alpha.y;

		let NdotH = H.z;
		let TdotH = H.x;
		let BdotH = H.y;

		let a2 = alphaT * alphaB;
		let v = vec3f( alphaB * TdotH, alphaT * BdotH, a2 * NdotH );
		let v2 = dot( v, v );
		let w2 = a2 / v2;

		return a2 * w2 * w2 / PI;

	}

`,[q]),Br=Ar(`

	fn ggxReflectionAdjustedPDF( V: vec3f, H: vec3f, alpha: vec2f ) -> f32 {

		let NdotV = max( V.z, MIN_INCIDENT_COS );
		let D = ggxDistribution( H, alpha );
		let G1 = ggxShadowMaskG1( V, alpha );

		return D * G1 / ( 4 * NdotV );

	}
`,[Bn,bs]),ws=Ar(`

	fn ggxRefractionAdjustedPDF( V: vec3f, L: vec3f, H: vec3f, alpha: vec2f, eta: f32 ) -> f32 {

		let NdotV = max( V.z, MIN_INCIDENT_COS );
		let HdotV = dot( V, H );
		let HdotL = dot( L, H );
		let D = ggxDistribution( H, alpha );
		let G1 = ggxShadowMaskG1( V, alpha );

		let denom = eta * HdotV + HdotL;
		return D * G1 * abs( HdotV ) * abs( HdotL ) / ( NdotV * denom * denom );

	}
`,[Bn,bs]);var o0=$`

	fn ensureValidReflectionNormal( n: vec3f, ng: vec3f, i: vec3f ) -> vec3f {

		// reflected ray
		let R = 2.0 * dot( n, i ) * n - i;

		let Iz = dot( i, ng );

		// reflection rays may always be at least as shallow as the incoming ray
		let threshold = min( 0.9 * Iz, 0.01 );
		if ( dot( ng, R ) >= threshold ) {

			return n;

		}

		// Form coordinate system with Ng as the Z axis and N inside the X-Z-plane.
   		// The X axis is found by normalizing the component of N that's orthogonal to Ng.
   		// The Y axis isn't actually needed.
		let orthoN = n - dot( n, ng ) * ng;
		let orthoLen = length( orthoN );
		let X = select( n, orthoN / orthoLen, orthoLen != 0.0 );

		// Calculate N.z and N.x in the local coordinate system.
		//
		// The goal of this computation is to find a N' that is rotated towards Ng just enough
   		// to lift R' above the threshold (here called t), therefore dot(R', Ng) = t.
		//
		// See the Blender implementation for a full description of the solution.
		let Ix = dot( i, X );

		let a = Ix * Ix + Iz * Iz;
		let b = 2.0 * ( a + Iz * threshold );
		let c = ( threshold + Iz ) * ( threshold + Iz );

		// only one root is valid; the sign of Ix selects it
		var Nz2: f32;
		if ( Ix < 0.0 ) {

			Nz2 = 0.25 * ( b + sqrt( max( b * b - 4.0 * a * c, 0.0 ) ) ) / a;

		} else {

			Nz2 = 0.25 * ( b - sqrt( max( b * b - 4.0 * a * c, 0.0 ) ) ) / a;

		}

		let Nx = sqrt( max( 1.0 - Nz2, 0.0 ) );
		let Nz = sqrt( max( Nz2, 0.0 ) );

		return Nx * X + Nz * ng;

	}

`,s0=$`

	fn ensureValidViewNormal( n: vec3f, ng: vec3f, view: vec3f ) -> vec3f {

		// ensure the view is at least slightly above the surface normal hemisphere
		const MIN_VIEW_COS = 1e-6;

		// if we're positive already then early out
		let c = dot( n, view );
		if ( c > MIN_VIEW_COS ) {

			return n;

		}

		// perpendicular vector to normal and view
		var perp = n - c * view;
		let perpLen = length( perp );

		// if we reach here that means that he view is nearly the opposite direction and
		// we can't derive plan to rotate on towards the view, so just use the geometry
		// normal. This could likely only really happen with a severe normal map application.
		if ( perpLen <= 1e-6 ) {

			return ng;

		}

		perp = perp / perpLen;

		// rotate the normal to be at larger than the above threshold - pythagorean
		// theorem for generating normal with threshold*view component
		return MIN_VIEW_COS * view + sqrt( 1.0 - MIN_VIEW_COS * MIN_VIEW_COS ) * perp;

	}

`,_s=3402823466e29,Uf=(o,e,t)=>Le(`

	fn getSurfaceRecord(
		material: Material,
		vertexData: bvh_GeometryStruct,
		side: f32,
		faceNormal: vec3f,
		view: vec3f,
		blurRoughness: f32,
	) -> SurfaceRecord {

		// "faceNormal" is provided on the hit side so flip it back to the geometric side
		var normal = faceNormal * side;
		if ( material.flatShading == 0 ) {

			normal = normalize( vertexData.normal.xyz );

		}

		var baseNormal = normal;
		if ( material.normalMap != -1 ) {

			// some provided tangents can be malformed (0, 0, 0) causing the normal to be degenerate
			// resulting in NaNs and slow path tracing.
			if ( length( vertexData.tangent.xyz ) > 0.0 && vertexData.tangent.w != 0.0 ) {

				// TODO: consider re-orthonormalizing against the normal here since attribute
				// interpolation could result in drift.
				let tangent = normalize( vertexData.tangent.xyz );
				let bitangent = normalize( cross( baseNormal, tangent ) * vertexData.tangent.w );
				let vTBN = mat3x3f( tangent, bitangent, baseNormal );

				let uvPrime = material.normalMapTransform * vec3( getUvFromChannel( vertexData, material.normalMap ), 1.0 );
				var texNormal = sampleTexel( uvPrime.xy, material.normalMap, 0 ).xyz;
				texNormal = texNormal * 2.0 - 1.0;
				texNormal = texNormal * vec3f( material.normalScale, 1.0 );
				normal = normalize( vTBN * texNormal );

			}

		}

		normal *= side;

		normal = ensureValidViewNormal( normal, faceNormal, view );

		normal = ensureValidReflectionNormal( normal, faceNormal, view );

		var albedo = vec4( material.color, material.opacity );
		if ( material.vertexColors == 1 ) {

			albedo *= getColor( vertexData );

		}

		if ( material.map != -1 ) {

			let uvPrime = material.mapTransform * vec3f( getUvFromChannel( vertexData, material.map ), 1 );
			albedo *= sampleTexel( uvPrime.xy, material.map, 0 );

		}

		if ( material.alphaMap != -1 ) {

			let uvPrime = material.alphaMapTransform * vec3f( getUvFromChannel( vertexData, material.alphaMap ), 1 );
			albedo.a *= sampleTexel( uvPrime.xy, material.alphaMap, 0 ).g;

		}

		var roughness = material.roughness;
		if ( material.roughnessMap != -1 ) {

			let uvPrime = material.roughnessMapTransform * vec3f( getUvFromChannel( vertexData, material.roughnessMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.roughnessMap, 0 );
			roughness *= texColor.g;

		}

		var metalness = material.metalness;
		if ( material.metalnessMap != -1 ) {

			let uvPrime = material.metalnessMapTransform * vec3f( getUvFromChannel( vertexData, material.metalnessMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.metalnessMap, 0 );
			metalness *= texColor.b;

		}

		var emission = material.emissiveIntensity * material.emissive;
		if ( material.emissiveMap != -1 ) {

			let uvPrime = material.emissiveMapTransform * vec3f( getUvFromChannel( vertexData, material.emissiveMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.emissiveMap, 0 );
			emission *= texColor.rgb;

		}

		var transmission = material.transmission;
		if ( material.transmissionMap != -1 ) {

			let uvPrime = material.transmissionMapTransform * vec3f( getUvFromChannel( vertexData, material.transmissionMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.transmissionMap, 0 );
			transmission *= texColor.r;

		}

		var clearcoat = material.clearcoat;
		if ( material.clearcoatMap != -1 ) {

			let uvPrime = material.clearcoatMapTransform * vec3f( getUvFromChannel( vertexData, material.clearcoatMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.clearcoatMap, 0 );
			clearcoat *= texColor.r;

		}

		var clearcoatRoughness = material.clearcoatRoughness;
		if ( material.clearcoatRoughnessMap != -1 ) {

			let uvPrime = material.clearcoatRoughnessMapTransform * vec3f( getUvFromChannel( vertexData, material.clearcoatRoughnessMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.clearcoatRoughnessMap, 0 );
			clearcoatRoughness *= texColor.g;

		}

		var clearcoatNormal = baseNormal;
		if ( material.clearcoatNormalMap != -1 ) {

			// some provided tangents can be malformed (0, 0, 0) causing the normal to be degenerate
			// resulting in NaNs and slow path tracing.
			if ( length( vertexData.tangent.xyz ) > 0.0 && vertexData.tangent.w != 0.0 ) {

				// TODO: consider re-orthonormalizing against the normal here since attribute
				// interpolation could result in drift.
				let tangent = normalize( vertexData.tangent.xyz );
				let bitangent = normalize( cross( baseNormal, tangent ) * vertexData.tangent.w );
				let vTBN = mat3x3f( tangent, bitangent, baseNormal );

				let uvPrime = material.clearcoatNormalMapTransform * vec3( getUvFromChannel( vertexData, material.clearcoatNormalMap ), 1.0 );
				var texNormal = sampleTexel( uvPrime.xy, material.clearcoatNormalMap, 0 ).xyz;
				texNormal = texNormal * 2.0 - 1.0;
				texNormal = texNormal * vec3f( material.clearcoatNormalScale, 1.0 );
				clearcoatNormal = normalize( vTBN * texNormal );

			}

		}

		clearcoatNormal *= side;

		clearcoatNormal = ensureValidViewNormal( clearcoatNormal, faceNormal, view );

		clearcoatNormal = ensureValidReflectionNormal( clearcoatNormal, faceNormal, view );

		var sheenColor = material.sheenColor;
		if ( material.sheenColorMap != -1 ) {

			let uvPrime = material.sheenColorMapTransform * vec3f( getUvFromChannel( vertexData, material.sheenColorMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.sheenColorMap, 0 );
			sheenColor *= texColor.rgb;

		}

		var sheenRoughness = material.sheenRoughness;
		if ( material.sheenRoughnessMap != -1 ) {

			let uvPrime = material.sheenRoughnessMapTransform * vec3f( getUvFromChannel( vertexData, material.sheenRoughnessMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.sheenRoughnessMap, 0 );
			sheenRoughness *= texColor.r;

		}

		var iridescence = material.iridescence;
		if ( material.iridescenceMap != -1 ) {

			let uvPrime = material.iridescenceMapTransform * vec3f( getUvFromChannel( vertexData, material.iridescenceMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.iridescenceMap, 0 );
			iridescence *= texColor.r;

		}

		var iridescenceThickness = material.iridescenceThicknessMaximum;
		if ( material.iridescenceThicknessMap != -1 ) {

			let uvPrime = material.iridescenceThicknessMapTransform * vec3f( getUvFromChannel( vertexData, material.iridescenceThicknessMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.iridescenceThicknessMap, 0 );

			iridescenceThickness = mix(
				material.iridescenceThicknessMinimum,
				material.iridescenceThicknessMaximum,
				texColor.g,
			);

		}

		var specularColor = material.specularColor;
		if ( material.specularColorMap != -1 ) {

			let uvPrime = material.specularColorMapTransform * vec3f( getUvFromChannel( vertexData, material.specularColorMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.specularColorMap, 0 );
			specularColor *= texColor.rgb;

		}

		var specularIntensity = material.specularIntensity;
		if ( material.specularIntensityMap != -1 ) {

			let uvPrime = material.specularIntensityMapTransform * vec3f( getUvFromChannel( vertexData, material.specularIntensityMap ), 1 );
			let texColor = sampleTexel( uvPrime.xy, material.specularIntensityMap, 0 );
			specularIntensity *= texColor.a;

		}

		// extract the anisotropy magnitude vector in tangent space
		var anisotropyDirVec = material.anisotropy * vec2f( cos( material.anisotropyRotation ), sin( material.anisotropyRotation ) );
		if ( material.anisotropyMap != -1 ) {

			let uvPrime = material.anisotropyMapTransform * vec3f( getUvFromChannel( vertexData, material.anisotropyMap ), 1.0 );
			let aniTex = sampleTexel( uvPrime.xy, material.anisotropyMap, 0 );

			// map rg encode the direction ([-1,1]), b the strength; rotate + scale by the material anisotropy.
			let mapDir = aniTex.rg * 2.0 - vec2f( 1.0 );
			let mapStr = aniTex.b;
			let mapLen = length( mapDir );
			if ( mapLen > EPSILON ) {

				anisotropyDirVec = mat2x2f(
					anisotropyDirVec.x, anisotropyDirVec.y,
					- anisotropyDirVec.y, anisotropyDirVec.x
				) * mapStr * mapDir / mapLen;

			}

		}

		// adjust the surface basis to be oriented along the anisotropic vector
		let anisotropyStrength = length( anisotropyDirVec );
		var surfaceBasis = getBasisFromNormal( normal );
		if ( anisotropyStrength > 0.0 && length( vertexData.tangent.xyz ) > 0.0 && vertexData.tangent.w != 0.0 ) {

			let anisotropyDir = anisotropyDirVec / anisotropyStrength;

			// re-orthonormalize the tangent against the shading normal so the frame stays orthonormal -
			// normal map adjustments will cause the normal and tangent to become non-orthonormal.
			let tangent = normalize( vertexData.tangent.xyz - normal * dot( normal, vertexData.tangent.xyz ) );
			let bitangent = cross( normal, tangent ) * vertexData.tangent.w;

			surfaceBasis = mat3x3f(
				tangent * anisotropyDir.x + bitangent * anisotropyDir.y,
				bitangent * anisotropyDir.x - tangent * anisotropyDir.y,
				normal
			);

		}

		var surf: SurfaceRecord;

		surf.volumeParticle = false;
		surf.faceNormal = faceNormal;
		surf.normal = normal;

		surf.metalness = metalness;
		surf.diffuseRoughness = material.diffuseRoughness;
		surf.color = albedo.rgb;
		surf.emission = emission;

		surf.opacity = select( 1.0, albedo.a, material.transparent != 0 );

		surf.ior = material.ior;
		surf.transmission = transmission;
		surf.thinWall = material.thinWall == 1;
		surf.attenuationColor = material.attenuationColor;
		surf.attenuationDistance = material.attenuationDistance;

		surf.clearcoatNormal = clearcoatNormal;
		surf.clearcoat = clearcoat;

		surf.iridescence = iridescence;
		surf.iridescenceIor = material.iridescenceIor;
		surf.iridescenceThickness = iridescenceThickness;

		surf.specularColor = specularColor;
		surf.specularIntensity = specularIntensity;

		surf.sheen = material.sheen;
		surf.sheenColor = sheenColor;

		let minRoughness = max( MIN_ROUGHNESS, blurRoughness );
		surf.roughness = clamp( roughness, minRoughness, 1.0 );
		surf.clearcoatRoughness = clamp( clearcoatRoughness, minRoughness, 1.0 );
		surf.sheenRoughness = clamp( sheenRoughness, minRoughness, 1.0 );
		surf.anisotropy = anisotropyStrength;

		// frontFace is used to determine transmissive properties and PDF. If no transmission is used
		// then we can just always assume this is a front face.
		surf.frontFace = side == 1.0 || transmission == 0.0;
		if ( material.thinWall == 1 || surf.frontFace ) {

			surf.eta = 1.0 / material.ior;

		} else {

			surf.eta = material.ior;

		}
		surf.f0 = iorToF0( surf.eta );

		// get the normal frames
		surf.normalBasis = surfaceBasis;
		surf.normalInvBasis = inverse( surf.normalBasis );

		surf.clearcoatBasis = getBasisFromNormal( surf.clearcoatNormal );
		surf.clearcoatInvBasis = inverse( surf.clearcoatBasis );

		return surf;
	}

`,[bf,Ye,wf,o,e,t,_e,q,o0,s0]),WI=Le(`

	fn diffuseBrdf( NdotV: f32, NdotL: f32, VdotH: f32, surf: SurfaceRecord ) -> vec3f {

		// https://blog.selfshadow.com/publications/s2015-shading-course/burley/s2015_pbs_disney_bsdf_notes.pdf
		// See equation (4)

		let fl = schlickFresnel( NdotL, 0.0 );
		let fv = schlickFresnel( NdotV, 0.0 );

		let alpha = surf.roughness * surf.roughness;
		let bias = mix( 0.0, 0.5, alpha ) - 1;
		let energyFactor = mix( 1.0, 1.0 / 1.51, alpha );

		let rr = 2.0 * alpha * VdotH * VdotH;
		let retro = rr * ( fl + fv + fl * fv * ( rr + 2.0 * bias ) );
		let fresnel = ( 1.0 + bias * fl ) * ( 1.0f + bias * fv );

		// TODO: subsurface approx?

		return energyFactor * ( surf.color / PI ) * ( retro + fresnel );

	}

`,[q,Pt,_e]),VI=Le(`

	fn lambertBrdf( NdotV: f32, NdotL: f32, VdotH: f32, surf: SurfaceRecord ) -> vec3f {

		return surf.color / PI;

	}

`,[q,_e]),jl=Le(`

	fn specularBrdf( V: vec3f, L: vec3f, H: vec3f, alpha: vec2f ) -> vec3f {

		let alphaT = alpha.x;
		let alphaB = alpha.y;

		let Vis = ggxSmithVisibility( V, L, alpha );
		let D = ggxDistribution( H, alpha );

		return vec3f( D * Vis );

	}

`,[Of,Bn]),xi=Le(`

	fn isMatchedIor( eta: f32 ) -> bool {

		return abs( eta - 1.0 ) < EPSILON;

	}

`,[q]),Kl=Le(`

	fn specularBtdf( V: vec3f, L: vec3f, H: vec3f, alpha: vec2f, eta: f32 ) -> vec3f {

		let NdotV = V.z;
		let NdotL = L.z;
		let HdotV = dot( V, H );
		let HdotL = dot( L, H );

		// Heaviside function for G term
		if ( NdotV * HdotV < 0.0 || NdotL * HdotL < 0.0 || HdotV * HdotL > 0.0 ) {

			return vec3f( 0.0 );

		}

		let G1_i = ggxShadowMaskG1( V, alpha );
		let G1_o = ggxShadowMaskG1( L, alpha );

		// separable G2 product, no height correlation
		let denom = eta * HdotV + HdotL;
		let Vis = G1_i * G1_o * abs( HdotV ) * abs( HdotL ) /
			( abs( NdotV ) * abs( NdotL ) * denom * denom );

		let F = dielectricFresnel( abs( HdotV ), eta );

		let D = ggxDistribution( H, alpha );

		return vec3f( ( 1 - F ) * D * Vis );

	}

`,[bs,Bn,$r]),Ts=Le(`

	fn thinWallTransmissionRoughness( alpha: f32, eta: f32 ) -> f32 {

		let t = 3.4 * ( eta - 1.0 ) * ( eta - 0.5 ) * ( eta - 0.5 ) / ( eta * eta * eta );
		return saturate( alpha * sqrt( max( t, 0.0 ) ) );

	}

`),Ss=Le(`

	fn transmissionAttenuation( dist: f32, attColor: vec3f, attDist: f32 ) -> vec3f {

		return pow( attColor, vec3f( dist / attDist ) );

	}

`),yi=360,vi=830,a0=Le(`

	fn dispersionIor( ior: f32, dispersion: f32, wavelength: f32 ) -> f32 {

		let nd = max( ior, 1.0 );
		let scale = ( nd - 1.0 ) * max( dispersion, 0.0 ) / 20.0;
		let lambda = clamp( wavelength, ${yi}.0, ${vi}.0 );
		return max( nd + scale * ( 523655.0 / ( lambda * lambda ) - 1.5168 ), 1.0 );

	}

`),Is=$`

	fn applyDispersion( surf: ptr<function, ${_e}>, dispersion: f32, wavelength: f32 ) -> void {

		surf.ior = ${a0}( surf.ior, dispersion, wavelength );
		surf.eta = select( surf.ior, 1.0 / surf.ior, surf.thinWall || surf.frontFace );
		surf.f0 = ${Ye}( surf.eta );

	}

`,$s=Le(`

	fn dispersionColorWeight( wavelength: f32 ) -> vec3f {

		let lambda = clamp( wavelength, ${yi}.0, ${vi}.0 );

		let x1 = ( lambda - 442.0 ) * select( 0.0374, 0.0624, lambda < 442.0 );
		let x2 = ( lambda - 599.8 ) * select( 0.0323, 0.0264, lambda < 599.8 );
		let x3 = ( lambda - 501.1 ) * select( 0.0382, 0.0490, lambda < 501.1 );
		let x = 0.362 * exp( - 0.5 * x1 * x1 ) + 1.056 * exp( - 0.5 * x2 * x2 ) - 0.065 * exp( - 0.5 * x3 * x3 );

		let y1 = ( lambda - 568.8 ) * select( 0.0247, 0.0213, lambda < 568.8 );
		let y2 = ( lambda - 530.9 ) * select( 0.0322, 0.0613, lambda < 530.9 );
		let y = 0.821 * exp( - 0.5 * y1 * y1 ) + 0.286 * exp( - 0.5 * y2 * y2 );

		let z1 = ( lambda - 437.0 ) * select( 0.0278, 0.0845, lambda < 437.0 );
		let z2 = ( lambda - 459.0 ) * select( 0.0725, 0.0385, lambda < 459.0 );
		let z = 1.217 * exp( - 0.5 * z1 * z1 ) + 0.681 * exp( - 0.5 * z2 * z2 );

		let linearRgb = max( vec3f(
			3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
			- 0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
			0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
		), vec3f( 0.0 ) );

		return linearRgb * vec3f( 2.6699448, 4.0708066, 4.2980494 );

	}

`),zf=Le(`

	fn fresnelMix( VdotH: f32, f0Color: vec3f, ior: f32, eta: f32, weight: f32, base: vec3f, layer: vec3f ) -> vec3f {

		var f0 = iorToF0( ior ) * f0Color;
		f0 = min( f0, vec3f( 1.0 ) );

		// reflect all light on total internal reflection, matching the WebGL evaluateFresnel function
		var fr = vec3f( 1.0 );
		if ( ! totalInternalReflection( abs( VdotH ), eta ) ) {

			fr = schlickFresnelVec( abs( VdotH ), f0, vec3f( 1.0 ) );

		}

		let maxFr = max( max( fr.r, fr.g ), fr.b );

		return ( 1.0 - weight * maxFr ) * base + weight * fr * layer;

	}

`,[hi,Ye,os]),l0=n0(3.2404542,-.969266,.0556434,-1.5371385,1.8760108,-.2040259,-.4985314,.041556,1.0572252),u0=$`

	fn evalSensitivity( OPD: f32, shift: vec3f ) -> vec3f {

		let phase = 2.0 * ${Math.PI} * OPD * 1.0e-9;
		const val = vec3(5.4856e-13, 4.4201e-13, 5.2481e-13);
		const pos = vec3(1.6810e+06, 1.7953e+06, 2.2084e+06);
		const _var = vec3(4.3278e+09, 9.3046e+09, 6.6121e+09);

		var xyz = val * sqrt(2.0 * ${Math.PI} * _var) * cos(pos * phase + shift) * exp(-phase * phase * _var);
		xyz.x += 9.7470e-14 * sqrt(2.0 * ${Math.PI} * 4.5282e+09) * cos(2.2399e+06 * phase + shift.x) * exp(-4.5282e+09 * phase * phase);
		xyz /= 1.0685e-7;

		let rgb = ${l0} * xyz;
		return rgb;

	}

`,Ql=Le(`

	fn iridescentFresnel(
		cosTheta1: f32, baseF0: vec3f, iridescenceIor: f32,
		outsideIor: f32, iridescenceThickness: f32,
	) -> vec3f {

		let sinTheta2Sq = pow( outsideIor / iridescenceIor, 2.0 ) * ( 1.0 - pow( cosTheta1, 2.0 ) );
		let cosTheta2Sq = 1.0 - sinTheta2Sq;

		// Handle total internal reflection
		if ( cosTheta2Sq < 0.0 ) {

			return vec3( 1.0 );

		}

		let cosTheta2 = sqrt( cosTheta2Sq );

		// First interface: air -> iridescent thin film
		let R0 = iorToF0General( iridescenceIor, outsideIor );
		let R12 = schlickFresnel( cosTheta1, R0 );
		let R21 = R12;
		let T121 = 1.0 - R12;
		let phi12 = select( 0.0, PI, iridescenceIor < outsideIor );
		let phi21 = PI - phi12;

		// Second interface: iridescent thin film -> base material
		let baseIor = fresnel0ToIor( clamp( baseF0, vec3( 0.0 ), vec3( 0.9999 ) ) ); // guard against 1.0
		let R1 = iorToF0GeneralVec( baseIor, vec3( iridescenceIor ) );
		var R23 = schlickFresnelVec( cosTheta2, R1, vec3( 1.0 ) );

		// Handle total internal reflection at the film -> base interface
		// NOTE: Added separately from the original implementation. Is this correct?
		R23 = select( R23, vec3( 1.0 ), totalInternalReflectionVec( cosTheta2, vec3( iridescenceIor ) / baseIor ) );
		let phi23 = select( vec3( 0.0 ), vec3( PI ), baseIor < vec3( iridescenceIor ) );

		// Phase shift
		let OPD = 2.0 * iridescenceIor * iridescenceThickness * cosTheta2;
		let phi = vec3( phi21 ) + phi23;

		// Analytical integration
		// Compound terms
		let R123 = clamp( R12 * R23, vec3( 1e-5 ), vec3( 0.9999 ) );
		let r123 = sqrt( R123 );
		let Rs = T121 * T121 * R23 / ( vec3( 1.0 ) - R123 );

		// Reflectance term for m = 0 (DC term amplitude)
		let C0 = R12 + Rs;
		var I = C0;

		// Reflectance term for m > 0 (pairs of diracs)
		var Cm = Rs - T121;
		for (var m = 1; m <= 2; m += 1) {

			Cm *= r123;
			let Sm = 2.0 * evalSensitivity( f32( m ) * OPD, f32( m ) * phi );
			I += Cm * Sm;

		}

		return max( I, vec3(0.0) );

	}

`,[_f,Tf,Pt,Sf,u0,If]),Hf=(o=Ql)=>$`

	fn transmissionFresnel( cosTheta: f32, surf: ${_e} ) -> ${Ml} {

		var result: ${Ml};
		let cosine = saturate( abs( cosTheta ) );
		let matchedIor = ${xi}( surf.eta );
		let airIncident = surf.thinWall || surf.frontFace;
		let dielectricFresnel = ${$r}( cosine, surf.eta );

		// Keep matched media transparent unless a real thin-film layer is present. This avoids
		// introducing the non-physical Schlick F90 reflection produced by KHR_materials_specular
		// when the substrate itself has no IOR boundary.
		var reflectance = vec3f( 0.0 );
		if ( ! matchedIor ) {

			let specularF0 = min( surf.f0 * surf.specularColor, vec3f( 1.0 ) );
			let dielectricReflectance = select(
				vec3f( dielectricFresnel ),
				${hi}( cosine, specularF0, vec3f( 1.0 ) ),
				airIncident,
			);
			reflectance = surf.specularIntensity * dielectricReflectance;

		}

		reflectance = clamp( reflectance, vec3f( 0.0 ), vec3f( 1.0 ) );
		var transmittance = vec3f( 1.0 ) - reflectance;

		if ( surf.iridescence > 0.0 ) {

			// The media on either side of the film are air outside and the volume interior as
			// the base. Swap them on interior hits so film-interface TIR remains possible.
			let outsideIor = select( surf.ior, 1.0, airIncident );
			let filmBaseIor = select( 1.0, surf.ior, airIncident );
			let filmReflectance = clamp( ${o}(
				cosine,
				vec3f( ${Ye}( filmBaseIor ) ),
				surf.iridescenceIor,
				outsideIor,
				surf.iridescenceThickness,
			), vec3f( 0.0 ), vec3f( 1.0 ) );

			reflectance = mix( reflectance, filmReflectance, surf.iridescence );
			transmittance = mix( transmittance, vec3f( 1.0 ) - filmReflectance, surf.iridescence );

		}

		result.reflectance = reflectance;
		result.transmittance = transmittance;

		// Include the base-color tint in the transmission energy, as Cycles does when it
		// computes the glass reflection probability from its two tinted coefficients.
		let reflectEnergy = dot( reflectance, vec3f( 1.0 / 3.0 ) );
		let transmissionTint = select( sqrt( surf.color ), surf.color, surf.thinWall );
		let transmitEnergy = dot( transmittance * transmissionTint, vec3f( 1.0 / 3.0 ) );
		let totalEnergy = reflectEnergy + transmitEnergy;
		result.reflectProbability = select( 0.0, reflectEnergy / max( totalEnergy, EPSILON ), totalEnergy > EPSILON );

		return result;

	}

`,Wf=Le(`

	fn conductorFresnel( VdotH: f32, f0: vec3f, specular: vec3f ) -> vec3f {

		return specular * schlickFresnelVec( abs( VdotH ), f0, vec3f( 1 ) );

	}

`,[hi]),Vf=Le(`

	fn fresnelCoat( VdotNc: f32, ior: f32, base: vec3f, layer: vec3f, weight: f32 ) -> vec3f {

		let f0 = iorToF0( ior );
		let F = schlickFresnel( abs( VdotNc ), f0 );

		return mix( base, layer, weight * F );

	}

`,[Ye,Pt]),Gf=o=>$`

	fn albedo(
		eta: f32,
		includeFresnel: bool,
		includeRefraction: bool,
		globalId: vec3u,
		layer: u32,
	) -> void {

		// The matched-IOR limit is a unit-energy delta transmission event, so it cannot
		// be integrated with the rough BTDF below.
		if ( includeRefraction && ${xi}( eta ) ) {

			textureStore( ${o}, vec3( globalId.xy, layer ), vec4( 1.0 ) );
			return;

		}

		// sample the brdf directions in a grid pattern
		const GRID_SIZE = 64u;

		// TODO: this sampling means that energy at 0.0 & 1.0 roughness (and 0 and 90deg cos) are never
		// written to the texture due to the half texel inset, resulting in small, though possibly noticeable,
		// error in common cases.
		let dimensions = textureDimensions( ${o} ).xy;
		let uv = ( vec2f( globalId.xy ) + vec2f( 0.5 ) ) / vec2f( dimensions );

		let cosThetaO = uv.x;
		let roughness = uv.y;
		let alpha = roughness * roughness;

		let wo = vec3( sqrt( 1 - cosThetaO * cosThetaO ), 0 , cosThetaO );

		let f0 = ${Ye}( eta );

		var result = 0.0;
		for ( var x = 0u; x < GRID_SIZE; x++ ) {

			for ( var y = 0u; y < GRID_SIZE; y++ ) {

				// calculate the half vector to sample
				let gridPoint = vec2f( vec2u( x, y ) ) + vec2f( 0.5 );
				let sampleUv = gridPoint / f32( GRID_SIZE );
				let wh = ${Pr}( wo, vec2( alpha ), sampleUv );

				var f = 1.0;
				if ( includeFresnel ) {

					// use the exact dielectric fresnel so the table agrees with the fresnel used
					// for lobe sampling
					f = ${$r}( dot( wo, wh ), eta );

				}

				// air-incident reflection is evaluated with schlick in bsdfEval - only the
				// volume-incident ( TIR ) glass reflection uses the exact fresnel
				var reflectionF = f;
				if ( eta < 1.0 ) {

					reflectionF = ${Pt}( dot( wo, wh ), f0 );

				}

				// reflection lobe - samples landing below the surface are lost
				let wi = - reflect( wo, wh );
				let reflectionPdf = ${Br}( wo, wh, vec2f( alpha ) );
				if ( wi.z > 0.0 && reflectionPdf != 0.0 ) {

					let specular = ${jl}( wo, wi, wh, vec2f( alpha ) );
					result += reflectionF * specular.x * wi.z / reflectionPdf;

				}

				// refraction lobe - the btdf carries its own ( 1 - F ) fresnel weight, and
				// samples landing back above the surface are lost
				if ( includeRefraction ) {

					let wiRefract = refract( - wo, wh, eta );
					if ( wiRefract.z < 0.0 ) {

						let transmission = ${Kl}( wo, wiRefract, wh, vec2f( alpha ), eta );
						let pdf = ${ws}( wo, wiRefract, wh, vec2f( alpha ), eta );
						if ( pdf != 0.0 ) {

							result += transmission.x * abs( wiRefract.z ) / pdf;

						}

					}

				}

			}

		}

		result /= f32( GRID_SIZE * GRID_SIZE );

		textureStore( ${o}, vec3( globalId.xy, layer ), vec4( result ) );

	}

`;var qf=Yo;var Cs=0,bi=1,As=2,Xf=2147483648,wi=1073741824,Gt=1073741823;var Bs=class extends z{constructor(){let e={material:{value:null},envInfo:{value:null},lightsInfo:{value:null},prevOutputTarget:Ps(Ht()).toReadOnly(),outputTarget:Ps(Ht()).toWriteOnly(),sampleCountTarget:Ps(ar()).toReadWrite(),offset:je(new jf),tileSize:je(new jf),seed:je(0),maxBounces:je(5),maxTransparentBounces:je(5,"uint"),maxSubsurfaceBounces:je(256,"uint"),misEnabled:je(1,"uint"),maxSamples:je(0,"uint"),filterGlossy:je(1),clampDirect:je(0),clampIndirect:je(10),backgroundInfo:{value:null},transmissiveBackground:je(bi),accumulateAux:je(0,"uint"),auxTarget:Ps(ct()).toReadWrite(),textures:c0(new Yf),textureSampler:p0(new Yf),globalId:f0},t=Be("bvhData.fns.raycastFirstHit.outputType"),r=$e("bvhData.fns.raycastFirstHit"),i=$e("bvhData.fns.raycastShadow"),s=$e("bvhData.fns.sampleTrianglePoint"),n=$e("bvhData.fns.getSurfaceRecord"),l=$e("bvhData.fns.getCameraRay"),a=pe("material.value.bsdfSample",e),p=pe("material.value.bsdfEvalPdf",e),u=Be("bvhData.storage.materials"),c=Be("bvhData.storage.transforms"),f=ge("envInfo.value.totalSumNode",e),h=ge("envInfo.value.intensityNode",e),d=ge("envInfo.value.sampleColor",e),m=ge("envInfo.value.sampleDir",e),x=ge("envInfo.value.getDirPdf",e),v=ge("envInfo.value.getWeight",e),g=ge("backgroundInfo.value.sampleColor",e),y=ge("lightsInfo.value.countNode",e),w=pe("lightsInfo.value.randomLightSample",e),b=pe("lightsInfo.value.intersectLightAtIndex",e),T=pe("lightsInfo.value.getLightWeight",e),_=pe("lightsInfo.value.getLightsWeight",e),S=pe("lightsInfo.value.selectLight",e),A=pe("lightsInfo.value.isLightVisibleToCamera",e),I=$`

			fn compute(

				// indices and target
				globalId: vec3u,

				// tiles
				offset: vec2u,
				tileSize: vec2u,

				// settings
				seed: u32,
				maxBounces: u32,
				maxTransparentBounces: u32,
				maxSubsurfaceBounces: u32,
				misEnabled: u32,
				maxSamples: u32,
				filterGlossy: f32,
				clampDirect: f32,
				clampIndirect: f32,

				transmissiveBackground: u32,
				accumulateAux: u32,

			) -> void {

				// make sure we don't bleed over the edge of our tile
				if ( globalId.x >= tileSize.x || globalId.y >= tileSize.y ) {

					return;

				}

				// to screen coordinates
				let indexUV = offset + globalId.xy;
				let targetDimensions = textureDimensions( ${e.outputTarget} );
				if ( indexUV.x >= targetDimensions.x || indexUV.y >= targetDimensions.y ) {

					return;

				}

				// skip the pixel once it has hit the sample limit
				let sampleCount = textureLoad( ${e.sampleCountTarget}, indexUV ).r;
				if ( maxSamples != 0u && sampleCount >= maxSamples ) {

					return;

				}

				let uv = vec2f( indexUV ) / vec2f( targetDimensions );
				${Xe}( indexUV.xy, seed, 0 );

				// scene ray
				let jitteredUv = uv + ${Ee}( ${jo} ) / vec2f( targetDimensions );
				var ray: ${D};
				if ( ! ${l}( jitteredUv, vec2f( targetDimensions ), &ray ) ) {

					// a declined pixel never takes a sample, so with a cap it counts as finished
					if ( maxSamples != 0u ) {

						textureStore( ${e.sampleCountTarget}, indexUV, vec4( maxSamples ) );

					}

					return;

				}

				ray.direction = normalize( ray.direction );

				var resultColor = vec4f( 0, 0, 0, 1 );
				var throughputColor = vec3f( 1.0 );
				var bsdfPdf = 0.0;

				// Alpha pass throughs only move where the BVH traversal starts, "rayStart" along the ray, so the
				// ray keeps the origin it scattered from, which its MIS weights are measured from
				var rayStart = 0.0;
				var traceOrigin = ray.origin;
				var isFullyTransmissive = true;
				var minPdf = 1.0;
				var transparentBounce = 0u;

				// the scattering volume the path is in. Media don't nest
				var medium: ${Vt};
				var subsurfaceBounces = 0u;

				// no light is sampled at a scatter event, so whatever the next segment reaches takes full weight
				var mediumScattered = false;

				// A negative value marks a sampled wavelength whose RGB reconstruction weight has not been applied yet.
				var dispersionWavelength = - mix( ${yi}.0, ${vi}.0, ${At}( ${es} ) );

				// one-sample NEE chooses between the analytic lights and the environment
				let envActive = ${f} > 0.0 && ${h} > 0.0;
				let lightsCount = ${y};
				var envWeight = 0.0;
				if ( envActive ) {

					envWeight = ${v}();

				}

				// added last, since a camera ray miss overwrites the pixel with the background
				var lightHits = vec3f( 0.0 );
				var cameraHitLight = false;

				// The first surface the path scatters off fills the denoiser's auxiliary images. Until one is found
				// the last mirror or transmission hit stands in, for paths that reach the environment. The albedo
				// is tinted by the throughput of the surfaces looked past to reach it
				var auxFound = false;
				var auxAlbedo = vec3f( 0.0 );
				var auxNormal = vec3f( 0.0 );

				for ( var bounce = 0u; bounce < maxBounces; bounce ++ ) {

					// Inside a medium, sample a scatter event before the next surface and only trace up to it
					var channelPdf = vec3f( 0.0 );
					var scatterDist = 0.0;
					var traceRay = ray;
					traceRay.origin = traceOrigin;
					if ( medium.inside ) {

						channelPdf = ${xs}( throughputColor, medium.albedo );
						scatterDist = ${ys}( medium, channelPdf, ${Ee}( ${ts} ) );
						traceRay.maxDist = select( scatterDist, min( ray.maxDist, scatterDist ), ray.maxDist > 0.0 );

					}

					var hitResult: ${t};
					let didHit = ${r}( traceRay, &hitResult );
					let surfaceDist = select( ${et}, hitResult.dist, didHit );

					let segmentInMedium = medium.inside;
					if ( medium.inside ) {

						let scattered = scatterDist < surfaceDist;
						let segmentDist = select( surfaceDist, scatterDist, scattered );
						let transmittance = exp( - medium.extinction * segmentDist );
						let distancePdf = dot( channelPdf, ${vs}( medium, segmentDist, scattered ) );
						if ( distancePdf <= 0.0 ) {

							break;

						}

						if ( ! scattered ) {

							// reaching the surface, weighted by the probability of no scatter event before it
							throughputColor *= transmittance / distancePdf;

						} else {

							throughputColor *= medium.albedo * medium.extinction * transmittance / distancePdf;
							ray.origin = traceOrigin + ray.direction * scatterDist;
							traceOrigin = ray.origin;
							rayStart = 0.0;
							ray.direction = ${as}( ray.direction, medium.anisotropy, ${Ee}( ${rs} ) );
							ray.maxDist = 0.0;
							mediumScattered = true;
							isFullyTransmissive = false;

							// subsurface bounces are capped separately from bounces
							subsurfaceBounces ++;
							let maxThroughput = max( max( throughputColor.r, throughputColor.g ), throughputColor.b );
							if ( subsurfaceBounces >= maxSubsurfaceBounces || maxThroughput < ${hs} ) {

								break;

							}

							${fi}();
							bounce --;
							continue;

						}

					}

					// NEE's total weight at the vertex this ray left, for MIS. Camera rays have none, and
					// neither do scatter events in a medium
					let isMISWeighted = misEnabled != 0u && bounce > 0u && ! mediumScattered;
					var lightTotalWeight = 0.0;
					if ( isMISWeighted ) {

						lightTotalWeight = ${_}( ray.origin ) + envWeight;

					}

					// forward hits on area lights along the traced segment. Camera rays only see visibleToCameraRays
					// lights, at full weight
					for ( var li = 0u; li < lightsCount; li ++ ) {

						if ( bounce == 0u && ! ${A}( li ) ) {

							continue;

						}

						var lightRec: ${qe};
						if ( ${b}( ray.origin, ray.direction, li, &lightRec ) && lightRec.dist > rayStart && lightRec.dist < rayStart + surfaceDist ) {

							var misWeight = 1.0;
							if ( isMISWeighted ) {

								let selectionPdf = ${T}( li, ray.origin ) / lightTotalWeight;
								misWeight = ${Bt}( bsdfPdf, lightRec.pdf * selectionPdf );

							}

							lightHits += ${Se}( lightRec.emission * throughputColor * misWeight, bounce, clampDirect, clampIndirect );
							cameraHitLight = cameraHitLight || bounce == 0u;

						}

					}

					if ( didHit ) {

						// whole structs are read from the buffer, not a pointer alias, which Safari fails to compile
						let objectInfo = ${c}[ hitResult.objectIndex ];
						var materialInfo = ${u}[ objectInfo.materialIndex ];

						// a matte surface hit by the camera ray renders as a fully transparent
						if ( materialInfo.matte != 0 && bounce == 0u ) {

							resultColor = vec4f( 0.0 );
							break;

						}

						// apply per-object colors
						materialInfo.color *= objectInfo.color.rgb;
						materialInfo.opacity *= objectInfo.color.a;

						let view = - ray.direction;
						var vertexData = ${s}( hitResult.barycoord, hitResult.indices.xyz );
						vertexData.normal = normalize( transpose( objectInfo.inverseMatrixWorld ) * vertexData.normal );
						vertexData.tangent = vec4f( ( objectInfo.matrixWorld * vec4f( vertexData.tangent.xyz, 0.0 ) ).xyz, vertexData.tangent.w );
						vertexData.position = objectInfo.matrixWorld * vertexData.position;

						// blur glossy surfaces after low-probability bounces to suppress fireflies,
						// from the Cycles "filter glossy" approach in integrator/surface_shader.h
						// The smallest pdf seen along the path for the glossy filter is tracked below
						let blurRoughness = sqrt( clamp( 1.0 - filterGlossy * minPdf, 0.0, 1.0 ) ) * 0.5;

						var surface = ${n}( materialInfo, vertexData, hitResult.side, hitResult.normal, view, blurRoughness );

						// Stochastically pass through partially transparent surfaces by restarting
						// the traversal at the hit point, advancing the rng but not the bounce count.
						if ( ${At}( ${is} ) > surface.opacity ) {

							// stop once the transparent bounces run out
							if ( transparentBounce >= maxTransparentBounces ) {

								break;

							}

							traceOrigin = ${Wt}( vertexData.position.xyz, ray.direction, hitResult.normal );
							rayStart += hitResult.dist;
							${fi}();
							transparentBounce ++;
							bounce --;
							continue;

						}

						let isDispersive = materialInfo.dispersion > 0.0 && surface.ior > 1.0 && surface.transmission > 0.0 && ! surface.thinWall;
						if ( isDispersive ) {

							let wavelength = abs( dispersionWavelength );
							${Is}( &surface, materialInfo.dispersion, wavelength );
							if ( dispersionWavelength < 0.0 ) {

								dispersionWavelength = wavelength;
								throughputColor *= ${$s}( wavelength );

							}

						}

						// attenuate the light transmitted through the volume when exiting a backface. A scattering
						// medium has already applied its transmittance above
						if ( hitResult.side < 0.0 && materialInfo.transmission > 0.0 && ! segmentInMedium ) {

							throughputColor *= ${Ss}( hitResult.dist, materialInfo.attenuationColor, materialInfo.attenuationDistance );

						}

						// emission, clamped at the depth of the segment that found it, as in Cycles
						let emission = ${Se}( throughputColor * surface.emission, bounce, clampDirect, clampIndirect );
						resultColor += vec4f( emission, 0.0 );

						// next event estimation: choose one light or the environment by its estimated
						// contribution
						if ( misEnabled != 0u ) {

							let ruv = ${di}( ${Zo} );
							var selectionPdf = 0.0;
							let lightIndex = ${S}( vertexData.position.xyz, envWeight, ruv.x, &selectionPdf );
							if ( selectionPdf > 0.0 ) {

								var lightRec: ${qe};
								if ( envActive && lightIndex == lightsCount ) {

									// the environment, sampled from its CDF, as a light of kind ENVIRONMENT
									let envSample = ${m}( ruv.yz );
									lightRec.direction = envSample.direction;
									lightRec.emission = envSample.color;
									lightRec.pdf = envSample.pdf;
									lightRec.dist = ${et};
									lightRec.lightType = ${Pn};

								} else {

									lightRec = ${w}( lightIndex, vertexData.position.xyz, ruv.yz );

								}

								if ( lightRec.pdf > 0.0 ) {

									let evalRec = ${p}( view, lightRec.direction, surface );
									if ( evalRec.pdf > 0.0 ) {

										var shadowRay: ${D};
										shadowRay.origin = ${Wt}( vertexData.position.xyz, lightRec.direction, hitResult.normal );
										shadowRay.direction = lightRec.direction;
										shadowRay.maxDist = lightRec.dist * ( 1.0 - ${ps} );

										// opaque occlusion up to the light distance, attenuated by the partially
										// transparent surfaces passed
										var shadowHit: ${t};
										let occluded = ${i}( shadowRay, &shadowHit );
										if ( ! occluded ) {

											let lightPdf = lightRec.pdf * selectionPdf;

											// env + area lights are also found by bsdf rays, so MIS-weight them. Punctual lights, and lights
											// past the bounce limit, take full weight. Bsdf rays stopped by faces the shadow ray skipped can't
											// find the light, so their share of the weight moves to the light sample
											let isMISWeighted = ${fs}( lightRec.lightType ) && bounce + 1u < maxBounces;
											let misWeight = select( 1.0, mix( ${Bt}( lightPdf, evalRec.pdf ), 1.0, shadowHit.skippedOcclusion ), isMISWeighted );
											let directLight = throughputColor * lightRec.emission * evalRec.color * misWeight * ( 1.0 - shadowHit.occlusion ) / lightPdf;
											let contribution = ${Se}( directLight, bounce + 1u, clampDirect, clampIndirect );
											resultColor += vec4f( contribution, 0.0 );

										}

									}

								}

							}

						}

						let scatterRec = ${a}( view, surface );

						if ( ! auxFound ) {

							auxFound = ${Wo}( surface, scatterRec );
							auxAlbedo = saturate( throughputColor * surface.color );
							auxNormal = surface.normal;

						}

						if ( ${mi}( scatterRec ) ) {

							break;

						}

						isFullyTransmissive = isFullyTransmissive && scatterRec.isTransmissive;

						// track the smallest pdf seen along the path for the glossy filter
						minPdf = min( minPdf, scatterRec.pdf );

						// russian roulette early out:
						// Matches Cycles path_state_continuation_probability in integrator/path_state.h
						if ( bounce >= 3u ) {

							let rrThroughput = throughputColor * scatterRec.color / scatterRec.pdf;
							let rrProb = saturate( sqrt( max( max( rrThroughput.r, rrThroughput.g ), rrThroughput.b ) ) );
							if ( rrProb <= 0.0 || ${At}( ${Jo} ) > rrProb ) {

								break;

							}

							throughputColor /= rrProb;

						}

						throughputColor *= scatterRec.color;
						throughputColor /= scatterRec.pdf;
						bsdfPdf = scatterRec.pdf;
						mediumScattered = false;

						// As in "KHR_materials_scatter", the medium's surface bsdf is its boundary: the walk ends when
						// light refracts out through it, and continues inside when it reflects back
						if ( segmentInMedium && hitResult.objectIndex == medium.object && hitResult.side < 0.0 && scatterRec.isTransmissive ) {

							medium.inside = false;

						}

						// refracting into a front face of a scattering material starts a random walk inside it
						if ( scatterRec.isTransmissive && ! surface.thinWall && hitResult.side > 0.0 ) {

							let entered = ${gs}( materialInfo, hitResult.objectIndex );

							if ( entered.inside ) {

								medium = entered;

							}

						}

						// exit if our throughput is 0.0
						if ( all( throughputColor == vec3f( 0.0 ) ) ) {

							break;

						}

						ray.origin = ${Wt}( vertexData.position.xyz, scatterRec.direction, hitResult.normal );
						ray.direction = scatterRec.direction;
						ray.maxDist = 0.0;
						traceOrigin = ray.origin;
						rayStart = 0.0;

					} else {

						if ( bounce > 0u && ! isFullyTransmissive ) {

							var misWeight = 1.0;
							if ( isMISWeighted && envActive ) {

								// match the env pdf scaling used by the NEE selection so the two estimators balance
								let envPdf = ${x}( ray.direction ) * envWeight / lightTotalWeight;
								misWeight = ${Bt}( bsdfPdf, envPdf );

							}

							let environment = ${d}( ray.direction ).rgb * throughputColor * misWeight;
							let contribution = ${Se}( environment, bounce, clampDirect, clampIndirect );
							resultColor += vec4f( contribution, 0.0 );

						} else {

							// hit the background
							// support multiple transparent background blending techniques
							let rng = ${Ee}( ${Ko} );
							let bg = ${g}( ray.direction, rng );
							if ( bounce == 0u ) {

								// sample the background directly if this is the primary ray
								let background = ${Se}( bg.a * bg.rgb, bounce, clampDirect, clampIndirect );
								resultColor = vec4f( background, bg.a );

							} else {

								// transmissive ray handling
								let env = ${d}( ray.direction );
								let avg = saturate( dot( throughputColor, vec3f( 1.0 / 3.0 ) ) );
								let transparency = ( 1.0 - bg.a ) * avg;
								var envMisWeight = 1.0;
								if ( misEnabled != 0u && ${f} > 0.0 ) {

									let envPdf = ${x}( ray.direction );
									envMisWeight = ${Bt}( bsdfPdf, envPdf );

								}

								if ( transmissiveBackground == ${Cs}u ) {

									// display the env map through transmissive surfaces
									let background = ${Se}( env.rgb * throughputColor * envMisWeight, bounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0,
									);

								} else if ( transmissiveBackground == ${As}u ) {

									// fade the background by the throughput color average
									let background = ${Se}( bg.a * bg.rgb * throughputColor * envMisWeight, bounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0 - transparency,
									);

								} else {

									// fade the background by the throughput color average, mixing in env lighting
									var light = mix( env.rgb, bg.rgb, bg.a ) * envMisWeight;
									let background = ${Se}( light * throughputColor, bounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0 - transparency,
									);

								}

							}

						}

						break;

					}

					${fi}();

				}

				// visible lights are opaque against a transparent background
				resultColor += vec4f( lightHits, 0.0 );
				if ( cameraHitLight ) {

					resultColor.a = 1.0;

				}

				let nextSampleCount = sampleCount + 1;
				// store the color rows top down to match a rasterized render target
				let colorIndex = vec2u( indexUV.x, targetDimensions.y - 1u - indexUV.y );

				let prevColor = textureLoad( ${e.prevOutputTarget}, colorIndex );
				let blendedColor = ${ls}( prevColor, resultColor, 1.0 / f32( nextSampleCount ) );
				textureStore( ${e.sampleCountTarget}, indexUV, vec4( nextSampleCount ) );
				textureStore( ${e.outputTarget}, colorIndex, blendedColor );

				// the auxiliary images are blended the same way, in the same row order
				if ( accumulateAux != 0u ) {

					var auxPacked: vec3u;
					for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

						auxPacked[ layer ] = textureLoad( ${e.auxTarget}, colorIndex, layer ).r;

					}

					auxPacked = ${Vo}( auxPacked, auxAlbedo, auxNormal, 1.0 / f32( nextSampleCount ) );
					for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

						textureStore( ${e.auxTarget}, colorIndex, layer, vec4u( auxPacked[ layer ] ) );

					}

				}

			}`;super(I(e)),this.defineUniformAccessors(e)}};import{Matrix3 as b0,StorageBufferAttribute as Zf}from"/vendor/three/0.187.0/three.webgpu.js";import{texture as w0,sampler as _0,uniform as eu,storage as T0,PI as Rn}from"/vendor/three/0.187.0/three.tsl.js";import{DataTexture as Zl,RedFormat as Kf,LinearFilter as _i,DataUtils as Rr,HalfFloatType as ur,TextureSource as h0,RepeatWrapping as Jl,RGBAFormat as m0,FloatType as g0,ClampToEdgeWrapping as x0}from"/vendor/three/0.187.0/three.webgpu.js";import{DataUtils as d0}from"/vendor/three/0.187.0/three.webgpu.js";function Rs(o){let e=new Uint16Array(o.length);for(let t=0,r=o.length;t<r;++t)e[t]=d0.toHalfFloat(o[t]);return e}function Qf(o,e,t=0,r=o.length){let i=t,s=t+r-1;for(;i<s;){let n=i+s>>1;o[n]<e?i=n+1:s=n}return i-t}function y0(o,e,t){return .2126*o+.7152*e+.0722*t}function v0(o,e=ur){let t=o.clone();t.source=new h0({...t.image});let{width:r,height:i,data:s}=t.image,n=s;if(t.type!==e){e===ur?n=new Uint16Array(s.length):n=new Float32Array(s.length);let l;s instanceof Int8Array||s instanceof Int16Array||s instanceof Int32Array?l=2**(8*s.BYTES_PER_ELEMENT-1)-1:l=2**(8*s.BYTES_PER_ELEMENT)-1;for(let a=0,p=s.length;a<p;a++){let u=s[a];t.type===ur&&(u=Rr.fromHalfFloat(s[a])),t.type!==g0&&t.type!==ur&&(u/=l),e===ur&&(n[a]=Rr.toHalfFloat(u))}t.image.data=n,t.type=e}if(t.flipY){let l=n;n=n.slice();for(let a=0;a<i;a++)for(let p=0;p<r;p++){let u=i-a-1,c=4*(a*r+p),f=4*(u*r+p);n[f+0]=l[c+0],n[f+1]=l[c+1],n[f+2]=l[c+2],n[f+3]=l[c+3]}t.flipY=!1,t.image.data=n}return t}var Es=class{constructor(){let e=new Zl(Rs(new Float32Array([0,0,0,0])),1,1);e.type=ur,e.format=m0,e.minFilter=_i,e.magFilter=_i,e.wrapS=Jl,e.wrapT=Jl,e.generateMipmaps=!1,e.needsUpdate=!0;let t=new Zl(Rs(new Float32Array([0,1])),1,2);t.type=ur,t.format=Kf,t.minFilter=_i,t.magFilter=_i,t.generateMipmaps=!1,t.needsUpdate=!0;let r=new Zl(Rs(new Float32Array([0,0,1,1])),2,2);r.type=ur,r.format=Kf,r.minFilter=_i,r.magFilter=_i,r.generateMipmaps=!1,r.needsUpdate=!0,this.map=e,this.marginalWeights=t,this.conditionalWeights=r,this.totalSum=0}getPixelWeight(e,t,r){return y0(e,t,r)}dispose(){this.marginalWeights.dispose(),this.conditionalWeights.dispose(),this.map.dispose()}updateFrom(e){let t=v0(e);t.wrapS=Jl,t.wrapT=x0;let{width:r,height:i,data:s}=t.image,n=new Float32Array(r*i),l=new Float32Array(r*i),a=new Float32Array(i),p=new Float32Array(i),u=0,c=0;for(let x=0;x<i;x++){let v=0;for(let g=0;g<r;g++){let y=x*r+g,w=Rr.fromHalfFloat(s[4*y+0]),b=Rr.fromHalfFloat(s[4*y+1]),T=Rr.fromHalfFloat(s[4*y+2]),_=this.getPixelWeight(w,b,T,x,i);v+=_,u+=_,n[y]=_,l[y]=v}if(v!==0)for(let g=x*r,y=x*r+r;g<y;g++)n[g]/=v,l[g]/=v;c+=v,a[x]=v,p[x]=c}if(c!==0)for(let x=0,v=a.length;x<v;x++)a[x]/=c,p[x]/=c;let f=new Uint16Array(i),h=new Uint16Array(r*i);for(let x=0;x<i;x++){let v=(x+1)/i,g=Qf(p,v);f[x]=Rr.toHalfFloat((g+.5)/i)}for(let x=0;x<i;x++)for(let v=0;v<r;v++){let g=x*r+v,y=(v+1)/r,w=Qf(l,y,x*r,r);h[g]=Rr.toHalfFloat((w+.5)/r)}this.dispose();let{marginalWeights:d,conditionalWeights:m}=this;d.image={width:i,height:1,data:f},d.needsUpdate=!0,m.image={width:r,height:i,data:h},m.needsUpdate=!0,this.totalSum=u,this.map=t,this.cdfMarginal=p,this.cdfConditional=l}};var Ti=class extends Es{constructor(){super(),this.mapNode=w0(this.map),this.mapSampler=_0(this.mapNode),this.cdfNode=T0(new Zf(new Float32Array([0,1,0,1]),1),"float").toReadOnly(),this.rotationNode=eu(new b0),this.intensityNode=eu(1),this.totalSumNode=eu(this.totalSum),this._initFns()}getPixelWeight(e,t,r,i,s){let n=Math.PI*(i+.5)/s;return super.getPixelWeight(e,t,r)*Math.sin(n)}updateFrom(e){super.updateFrom(e);let{mapNode:t,cdfNode:r,totalSumNode:i}=this;t.value=this.map,i.value=this.totalSum;let{width:s,height:n}=this.map.image,{cdfMarginal:l,cdfConditional:a}=this,p=new Float32Array(n+1+n*(s+1));p.set(l,1);for(let u=0;u<n;u++)p.set(a.subarray(u*s,u*s+s),n+1+u*(s+1)+1);r.value=new Zf(p,1)}dispose(){super.dispose(),this.cdfNode.value.dispose()}_initFns(){let{mapNode:e,mapSampler:t,cdfNode:r,totalSumNode:i,rotationNode:s,intensityNode:n}=this,l=$`
			fn searchEnvCdf( r: f32, offset: u32, count: u32, fraction: ptr<function, f32> ) -> u32 {

				var lower = 0u;
				var upper = count - 1u;
				while ( lower < upper ) {

					let mid = ( lower + upper ) >> 1u;
					if ( ${r}[ offset + mid + 1u ] <= r ) {

						lower = mid + 1u;

					} else {

						upper = mid;

					}

				}

				let start = ${r}[ offset + lower ];
				let end = ${r}[ offset + lower + 1u ];
				*fraction = select( 0.5, saturate( ( r - start ) / ( end - start ) ), end > start );
				return lower;

			}
		`,a=$`
			fn getEnvTexelPdf( texel: vec2u, v: f32 ) -> f32 {

				let resolution = textureDimensions( ${e} );
				let rowOffset = resolution.y + 1u + texel.y * ( resolution.x + 1u );
				let rowPdf = ${r}[ texel.y + 1u ] - ${r}[ texel.y ];
				let pixelPdf = ${r}[ rowOffset + texel.x + 1u ] - ${r}[ rowOffset + texel.x ];

				// the equirect projection stretches each pixel by 1 / sin( theta ) over the sphere
				let sinTheta = sin( ${Rn} * v );
				return select( 0.0, rowPdf * pixelPdf * f32( resolution.x * resolution.y ) / ( 2.0 * ${Rn} * ${Rn} * sinTheta ), sinTheta > 0.0 );

			}
		`;this.sampleColor=$`
			fn sampleEnv( direction: vec3f ) -> vec4f {

				let sampleDir = ${s} * direction;
				let mapUv = ${gi}( sampleDir );
				let col = textureSampleLevel( ${e}, ${t}, mapUv, 0 );

				return vec4f( ${n} * col.rgb, col.a );

			}
		`,this.sampleDir=$`
			fn sampleEnvDir( r: vec2f ) -> ${Dl} {

				var result: ${Dl};

				// search the CDFs: marginal picks the row (v), conditional picks the column (u), and the
				// fractions place the sample within the pixel
				let resolution = textureDimensions( ${e} );
				var rowFraction = 0.0;
				var columnFraction = 0.0;
				let row = ${l}( r.x, 0u, resolution.y, &rowFraction );
				let column = ${l}( r.y, resolution.y + 1u + row * ( resolution.x + 1u ), resolution.x, &columnFraction );
				let texel = vec2u( column, row );
				let uv = ( vec2f( texel ) + vec2f( columnFraction, rowFraction ) ) / vec2f( resolution );

				let direction = ${Bf}( uv );
				let color = textureSampleLevel( ${e}, ${t}, uv, 0 ).rgb;

				result.direction = transpose( ${s} ) * direction;
				result.color = color * ${n};
				result.pdf = ${a}( texel, uv.y );

				return result;

			}
		`,this.getWeight=$`
			fn getEnvWeight() -> f32 {

				let resolution = textureDimensions( ${e} );
				return ${n} * ${Rn} * ${Rn} * ${i} / ( 2.0 * f32( resolution.x * resolution.y ) );

			}
		`,this.getDirPdf=$`
			fn getEnvDirPdf( direction: vec3f ) -> f32 {

				if ( ${i} == 0.0 ) {

					return 0.0;

				}

				let rotatedDir = ${s} * direction;
				let mapUv = ${gi}( rotatedDir );
				let resolution = textureDimensions( ${e} );
				let texel = min( vec2u( mapUv * vec2f( resolution ) ), resolution - 1u );

				return ${a}( texel, mapUv.y );

			}
		`}};import{DataTexture as S0,LinearFilter as Jf,Matrix4 as I0,RGBAFormat as $0,RepeatWrapping as ed}from"/vendor/three/0.187.0/three.webgpu.js";import{sampler as C0,texture as A0,uniform as tu}from"/vendor/three/0.187.0/three.tsl.js";var Si=class{get map(){return this.mapNode.value}set map(e){this.mapNode.value=e}get blur(){return this.blurNode.value}set blur(e){this.blurNode.value=e}get rotation(){return this.rotationNode.value}get intensity(){return this.intensityNode.value}set intensity(e){this.intensityNode.value=e}constructor(){let e=new S0(new Uint8Array([0,0,0,255]),1,1,$0);e.minFilter=Jf,e.magFilter=Jf,e.wrapS=ed,e.wrapT=ed,e.generateMipmaps=!1,e.needsUpdate=!0,this.mapNode=A0(e),this.mapSampler=C0(this.mapNode),this.blurNode=tu(0),this.rotationNode=tu(new I0),this.intensityNode=tu(1),this._initFns()}dispose(){this.map.dispose()}_initFns(){let{mapNode:e,mapSampler:t,blurNode:r,rotationNode:i,intensityNode:s}=this;this.sampleColor=$`
			fn sampleBackground( direction: vec3f, uv: vec2f ) -> vec4f {

				let rotatedDir = ( ${i} * vec4f( direction, 0.0 ) ).xyz;
				let offsetDir = ${Af}( rotatedDir, uv ) * 0.5 * ${r};
				let sampleDir = normalize( rotatedDir + offsetDir );

				let mapUv = ${gi}( sampleDir );
				let col = textureSampleLevel( ${e}, ${t}, mapUv, 0 );

				return vec4f( ${s} * col.rgb, col.a );

			}
		`}};import{storage as K0,uniform as Q0,uniformArray as Z0,texture as J0}from"/vendor/three/0.187.0/three.tsl.js";import{StorageBufferAttribute as cd,HalfFloatType as ey}from"/vendor/three/0.187.0/three.webgpu.js";import{MeshBasicMaterial as P0,RenderTarget as B0,NoToneMapping as R0,QuadMesh as E0,NoBlending as M0,Color as D0,Vector4 as ru,Matrix3 as N0}from"/vendor/three/0.187.0/three.webgpu.js";var td=new D0,rd=new N0;function id(o){return`${o.source.uuid}:${o.source.version}`}function F0(o,e,t,r){if(o[e].x+t>r)return-1;let s=t,n=0,l=e;for(;s>0;){if(l>=o.length)return-1;n=Math.max(n,o[l].y),s-=o[l].width,l++}return n}function k0(o,e,t,r){let i=e+r,s=[],n=!1;for(let l=0;l<o.length;l++){let a=o[l],p=a.x+a.width;p<=e?s.push(a):a.x>=i?(n||(s.push({x:e,y:t,width:r}),n=!0),s.push(a)):(n||(s.push({x:e,y:t,width:r}),n=!0),p>i&&s.push({x:i,y:a.y,width:p-i}))}n||s.push({x:e,y:t,width:r}),o.length=0;for(let l=0;l<s.length;l++){let a=s[l],p=o[o.length-1];p&&p.y===a.y&&p.x+p.width===a.x?p.width+=a.width:o.push(a)}}function nd(o,e,t,r,i){let s=1/0,n=0,l=-1;for(let a=0;a<o.length;a++){let p=F0(o,a,e,r);p>=0&&p+t<=i&&p<s&&(s=p,n=o[a].x,l=a)}return l===-1?null:(k0(o,n,s+t,e),{x:n,y:s})}var Ii=class{get width(){return this.renderTarget.width}get height(){return this.renderTarget.height}get pageCount(){return this.renderTarget.depth}get texture(){return this.renderTarget.texture}constructor(e={}){this.renderTarget=new B0(1,1,{depthBuffer:!1,depth:1,...e}),this.texture.isArrayTexture=!0,this.textureInfo=[new ru],this.hashes=[],this.placements=[],this.quadMesh=new E0(new P0),this.quadMesh.material.blending=M0}setTextures(e,t){let r=t.map((c,f)=>f);if(r.sort((c,f)=>t[c].source.uuid<t[f].source.uuid?-1:1),t=r.map(c=>t[c]),this._hashesMatch(t)){this._buildCallerTextureInfo(r);return}let i=t.reduce((c,f)=>Math.max(c,f.width,f.height),1);i=Math.min(e.backend.device.limits.maxTextureDimension2D,i);let s=i,n=i,{renderTarget:l}=this,{placements:a,pageCount:p}=this._packTextures(t,s,n);l.setSize(s,n,p),this.texture.isArrayTexture=!0,this._renderTextures(e,t,a),this.placements=a;let u=new Array(t.length);for(let c=0,f=t.length;c<f;c++){let h=id(t[c]);u[c]=h}this.hashes=u,this._buildCallerTextureInfo(r)}_buildCallerTextureInfo(e){let t=new Array(e.length);for(let r=0,i=e.length;r<i;r++)t[e[r]]=this.placements[r];this._buildTextureInfo(t)}dispose(){this.renderTarget.dispose(),this.quadMesh.material.dispose()}_hashesMatch(e){let t=this.hashes;if(t.length!==e.length)return!1;for(let r=0,i=e.length;r<i;r++)if(t[r]!==id(e[r]))return!1;return!0}_packTextures(e,t,r){let i=[];for(let a=0,p=e.length;a<p;a++){let u=e[a];i.push({index:a,w:Math.min(u.width,t),h:Math.min(u.height,r)})}let s=i.sort((a,p)=>p.h-a.h),n=new Array(e.length),l=[];for(let a=0,p=s.length;a<p;a++){let u=s[a],c=null,f=-1;for(let h=0;h<l.length;h++)if(c=nd(l[h],u.w,u.h,t,r),c){f=h;break}if(!c){let h=[{x:0,y:0,width:t}];c=nd(h,u.w,u.h,t,r),l.push(h),f=l.length-1}n[u.index]={x:c.x,y:c.y,w:u.w,h:u.h,page:f}}return{placements:n,pageCount:Math.max(l.length,1)}}_buildTextureInfo(e){let t=this.textureInfo;t.length=0;for(let r=0,i=e.length;r<i;r++){let{x:s,y:n,w:l,h:a,page:p}=e[r],u=new ru;u.x=s&65535|(n&65535)<<16,u.y=l&65535|(a&65535)<<16,u.z=p&65535,u.w=0,t.push(u)}t.length===0&&t.push(new ru(0,0,0,0))}_renderTextures(e,t,r){let{quadMesh:i,renderTarget:s,width:n,height:l}=this,a=e.getRenderTarget(),p=e.toneMapping,u=e.autoClear,c=e.getScissorTest(),f=e.getClearAlpha();e.getClearColor(td),e.toneMapping=R0,e.autoClear=!1,e.setScissorTest(!0),e.setClearColor(0,0);for(let h=0,d=s.depth;h<d;h++)s.viewport.set(0,0,n,l),s.scissor.set(0,0,n,l),e.setRenderTarget(s,h),e.clear();for(let h=0,d=t.length;h<d;h++){let{x:m,y:x,w:v,h:g,page:y}=r[h],w=t[h];if(s.viewport.set(m,x,v,g),s.scissor.set(m,x,v,g),e.setRenderTarget(s,y),w.isRenderTargetTexture){let b=w.matrixAutoUpdate;rd.copy(w.matrix),w.matrix.identity(),w.matrixAutoUpdate=!1,i.material.map=w,i.render(e),w.matrixAutoUpdate=b,w.matrix.copy(rd)}else{let b=w.clone();b.matrixAutoUpdate=!1,b.matrix.identity(),i.material.map=b,i.render(e),b.dispose()}}s.viewport.set(0,0,n,l),s.scissor.set(0,0,n,l),i.material.map=null,e.setClearColor(td,f),e.setScissorTest(c),e.autoClear=u,e.setRenderTarget(a),e.toneMapping=p}};import{DataTexture as L0,RGBAFormat as O0,ClampToEdgeWrapping as sd,FloatType as U0,Vector3 as En,Quaternion as z0,Matrix4 as H0,NearestFilter as ad}from"/vendor/three/0.187.0/three.webgpu.js";function od(o){let e=0;if(o.byteLength!==0){let t=new Uint8Array(o);for(let r=0;r<o.byteLength;r++){let i=t[r];e=(e<<5)-e+i,e|=0}}return e}var iu=6,W0=11,V0=0,G0=1,q0=2,X0=3,Y0=4,dt=new En,Oe=new En,ld=new H0,$i=new z0,ud=new En,Rt=new En,j0=new En(0,1,0),Ms=class{constructor(){let e=new L0(new Float32Array(4),1,1);e.format=O0,e.type=U0,e.wrapS=sd,e.wrapT=sd,e.generateMipmaps=!1,e.minFilter=ad,e.magFilter=ad,this.tex=e,this.count=0}updateFrom(e,t=[]){let r=this.tex,i=Math.max(e.length*iu,1),s=Math.ceil(Math.sqrt(i));r.image.width!==s&&(r.dispose(),r.image.data=new Float32Array(s*s*4),r.image.width=s,r.image.height=s);let n=r.image.data;for(let a=0,p=e.length;a<p;a++){let u=e[a],c=a*iu*4,f=0;for(let d=0;d<iu*4;d++)n[c+d]=0;u.getWorldPosition(Oe),n[c+f++]=Oe.x,n[c+f++]=Oe.y,n[c+f++]=Oe.z;let h=V0;if(u.isRectAreaLight&&u.isCircular?h=G0:u.isSpotLight?h=q0:u.isDirectionalLight||u.isSunLight?h=X0:u.isPointLight&&(h=Y0),n[c+f++]=h,n[c+f++]=u.color.r,n[c+f++]=u.color.g,n[c+f++]=u.color.b,n[c+f++]=u.intensity,u.getWorldQuaternion($i),u.isRectAreaLight)dt.set(u.width,0,0).applyQuaternion($i),n[c+f++]=dt.x,n[c+f++]=dt.y,n[c+f++]=dt.z,f++,Oe.set(0,u.height,0).applyQuaternion($i),n[c+f++]=Oe.x,n[c+f++]=Oe.y,n[c+f++]=Oe.z,n[c+f++]=dt.cross(Oe).length()*(u.isCircular?Math.PI/4:1);else if(u.isSpotLight){let d=u.radius||0;ud.setFromMatrixPosition(u.matrixWorld),Rt.setFromMatrixPosition(u.target.matrixWorld),ld.lookAt(ud,Rt,j0),$i.setFromRotationMatrix(ld),dt.set(1,0,0).applyQuaternion($i),n[c+f++]=dt.x,n[c+f++]=dt.y,n[c+f++]=dt.z,f++,Oe.set(0,1,0).applyQuaternion($i),n[c+f++]=Oe.x,n[c+f++]=Oe.y,n[c+f++]=Oe.z,n[c+f++]=Math.PI*d*d,n[c+f++]=d,n[c+f++]=u.decay,n[c+f++]=u.distance,n[c+f++]=Math.cos(u.angle),n[c+f++]=Math.cos(u.angle*(1-u.penumbra)),n[c+f++]=u.iesMap?t.indexOf(u.iesMap):-1}else if(u.isPointLight){let d=dt.setFromMatrixPosition(u.matrixWorld);n[c+f++]=d.x,n[c+f++]=d.y,n[c+f++]=d.z,f++,f+=4,f+=1,n[c+f++]=u.decay,n[c+f++]=u.distance}else if(u.isDirectionalLight){let d=dt.setFromMatrixPosition(u.matrixWorld),m=Oe.setFromMatrixPosition(u.target.matrixWorld);Rt.subVectors(d,m).normalize(),n[c+f++]=Rt.x,n[c+f++]=Rt.y,n[c+f++]=Rt.z}else u.isSunLight&&(Rt.setFromMatrixPosition(u.matrixWorld).normalize(),n[c+f++]=Rt.x,n[c+f++]=Rt.y,n[c+f++]=Rt.z);n[c+W0]=u.visibleToCameraRays?1:0}this.count=e.length;let l=od(n.buffer);return this.hash!==l?(this.hash=l,r.needsUpdate=!0,!0):!1}};var Ci=class extends Ms{constructor(){super(),this.countNode=Q0(this.count,"uint"),this.buffer=new cd(new Float32Array(2*zt.getLength()),zt.getLength()),this.bufferNode=K0(this.buffer,zt).toReadOnly().setName("lights"),this.iesAtlas=new Ii({type:ey}),this.iesProfilesNode=J0(this.iesAtlas.texture),this.iesInfoNode=Z0(this.iesAtlas.textureInfo,"uvec4").setName("iesInfo"),this._initFns()}updateFrom(e,t){let r=Array.from(new Set(t.map(u=>u.iesMap).filter(u=>u))),i=super.updateFrom(t,r);this.iesAtlas.setTextures(e,r),this.iesProfilesNode.value=this.iesAtlas.texture;let s=zt.getLength(),n=this.count,l=Math.max(n,2);this.buffer.array.length!==l*s&&(this.buffer.dispose(),this.buffer=new cd(new Float32Array(l*s),s),this.bufferNode.value=this.buffer);let a=this.tex.image.data;this.buffer.array.set(a.subarray(0,n*s));let p=new Int32Array(this.buffer.array.buffer);for(let u=0;u<n;u++){let c=u*s;p[c+3]=Math.round(a[c+3]),p[c+11]=Math.round(a[c+11]),p[c+21]=Math.round(a[c+21])}return this.buffer.needsUpdate=!0,this.countNode.value=this.count,i}_initFns(){let{bufferNode:e,countNode:t,iesProfilesNode:r,iesInfoNode:i}=this,s=ss(i,r,"sampleIesTexel");this.isLightVisibleToCamera=$`
			fn isLightVisibleToCamera( index: u32 ) -> bool {

				return ${e}[ index ].visibleToCameraRays != 0;

			}
		`,this.getLightWeight=$`
			fn getLightWeight( index: u32, position: vec3f ) -> f32 {

				let light = ${e}[ index ];
				let power = ${Pf}( light.color * light.intensity );
				if ( light.lightType == ${Gl} ) {

					// the most any point on the spot's disc lights the position. Ies profiles are not
					// evaluated here
					let nearest = ${Nf}( light, position );
					var attenuation = 1.0;
					if ( light.iesProfile < 0 ) {

						attenuation = ${Yl}( light.coneCos, light.penumbraCos, nearest.x );

					}

					return power * attenuation * ${ds}( nearest.y, light.distance, light.decay );

				} else if ( light.lightType == ${Xl} ) {

					// the point light's world position is packed into the u slot
					let dist = length( light.u - position );
					return power * ${ds}( dist, light.distance, light.decay );

				} else if ( light.lightType == ${ql} ) {

					return power;

				} else {

					let toLight = light.position - position;
					let distSq = dot( toLight, toLight );
					if ( distSq == 0.0 ) {

						return 0.0;

					}

					// area lights only emit from their front face, and the distance is held above the size
					// of the light so positions on or near it do not dominate
					let normal = normalize( cross( light.u, light.v ) );
					let cosTheta = dot( toLight, normal ) * inverseSqrt( distSq );
					return power * light.area * max( cosTheta, 0.0 ) / max( distSq, light.area );

				}

			}
		`,this.getLightsWeight=$`
			fn getLightsWeight( position: vec3f ) -> f32 {

				var total = 0.0;
				for ( var i = 0u; i < ${t}; i ++ ) {

					total += ${this.getLightWeight}( i, position );

				}

				return total;

			}
		`,this.selectLight=$`
			fn selectLight( position: vec3f, envWeight: f32, r: f32, selectionPdf: ptr<function, f32> ) -> u32 {

				let count = ${t};
				let totalWeight = ${this.getLightsWeight}( position ) + envWeight;
				if ( totalWeight <= 0.0 ) {

					*selectionPdf = 0.0;
					return 0u;

				}

				let targetWeight = r * totalWeight;
				var accumulated = 0.0;
				var lastWeighted = 0u;
				var index = count;
				for ( var i = 0u; i < count; i ++ ) {

					let lightWeight = ${this.getLightWeight}( i, position );
					accumulated += lightWeight;
					if ( lightWeight > 0.0 ) {

						lastWeighted = i;

					}

					if ( targetWeight < accumulated ) {

						index = i;
						break;

					}

				}

				// rounding can carry the target past the last light when there is no environment
				if ( index == count && envWeight <= 0.0 ) {

					index = lastWeighted;

				}

				var weight = envWeight;
				if ( index < count ) {

					weight = ${this.getLightWeight}( index, position );

				}

				*selectionPdf = weight / totalWeight;
				return index;

			}
		`,this.randomLightSample=$`
			fn randomLightSample( lightIndex: u32, rayOrigin: vec3f, ruv: vec2f ) -> ${qe} {

				let light = ${e}[ lightIndex ];

				var result: ${qe};
				if ( light.lightType == ${Gl} ) {

					result = ${Df}( light, rayOrigin, ruv );

					let spotNormal = normalize( cross( light.u, light.v ) );
					let cosTheta = dot( result.direction, spotNormal );
					var spotAttenuation: f32;
					if ( light.iesProfile >= 0 ) {

						// half a texel of the profile's tile in the atlas
						let profileInfo = ${i}[ u32( light.iesProfile ) ];
						let texelInset = 0.5 / vec2f( vec2u( profileInfo.y & 0xFFFFu, profileInfo.y >> 16u ) );

						// tilt spans [ 0, 180 ]deg inset to the texel centers, twist covers [ 0, 359 ]deg and wraps
						let tiltAngle = mix( texelInset.x, 1.0 - texelInset.x, acos( cosTheta ) / PI );
						let twistAngle = ( atan2( dot( result.direction, light.v ), dot( result.direction, light.u ) ) + PI ) / ( 2.0 * PI ) + texelInset.y;
						let packedProfile = ( 1 << 26 ) | light.iesProfile;
						spotAttenuation = ${s}( vec2f( tiltAngle, twistAngle ), packedProfile, 0.0 ).r;

					} else {

						spotAttenuation = ${Yl}( light.coneCos, light.penumbraCos, cosTheta );

					}

					result.emission *= spotAttenuation;

				} else if ( light.lightType == ${Xl} ) {

					// the point light's world position is packed into the u slot
					let lightRay = light.u - rayOrigin;
					let lightDist = length( lightRay );
					let cutoffDistance = light.distance;
					var distanceFalloff = 1.0 / max( pow( lightDist, light.decay ), 0.01 );
					if ( cutoffDistance > 0.0 ) {

						let window = clamp( 1.0 - pow( lightDist / cutoffDistance, 4.0 ), 0.0, 1.0 );
						distanceFalloff *= window * window;

					}

					result.direction = normalize( lightRay );
					result.dist = lightDist;
					result.pdf = 1.0;
					result.emission = light.color * light.intensity * distanceFalloff;
					result.lightType = light.lightType;

				} else if ( light.lightType == ${ql} ) {

					// the directional light's direction is packed into the u slot
					result.dist = ${et};
					result.direction = light.u;
					result.pdf = 1.0;
					result.emission = light.color * light.intensity;
					result.lightType = light.lightType;

				} else {

					result = ${Mf}( light, rayOrigin, ruv );

				}

				return result;

			}
		`,this.intersectLightAtIndex=$`
			fn intersectLightAtIndex( rayOrigin: vec3f, rayDirection: vec3f, index: u32, lightRec: ptr<function, ${qe}> ) -> bool {

				let light = ${e}[ index ];

				var u = light.u;
				var v = light.v;
				let normal = normalize( cross( u, v ) );

				// only front-facing area lights can be hit
				if ( dot( normal, rayDirection ) > 0.0 ) {

					u *= 1.0 / dot( u, u );
					v *= 1.0 / dot( v, v );

					var dist = - 1.0;
					if ( light.lightType == ${us} ) {

						dist = ${Rf}( light.position, normal, u, v, rayOrigin, rayDirection );

					} else if ( light.lightType == ${cs} ) {

						dist = ${Ef}( light.position, normal, u, v, rayOrigin, rayDirection );

					}

					if ( dist > 0.0 ) {

						let cosTheta = dot( rayDirection, normal );
						lightRec.dist = dist;
						lightRec.pdf = ( dist * dist ) / ( light.area * cosTheta );
						lightRec.emission = light.color * light.intensity;
						lightRec.direction = rayDirection;
						lightRec.lightType = light.lightType;
						return true;

					}

				}

				return false;

			}
		`}dispose(){this.tex.dispose(),this.buffer.dispose(),this.iesAtlas.dispose()}};import{TimestampQuery as ny}from"/vendor/three/0.187.0/three.webgpu.js";import{StorageTexture as ty}from"/vendor/three/0.187.0/three.webgpu.js";import{textureStore as ry,globalId as iy}from"/vendor/three/0.187.0/three.tsl.js";var Ai=class extends z{constructor(){let e={globalId:iy,outputTarget:ry(new ty(1,1)).toWriteOnly()},t=()=>e.outputTarget.value.isArrayTexture?"globalId.xy, globalId.z":"globalId.xy",r=$`
			fn compute( globalId: vec3u ) -> void {

				textureStore( ${e.outputTarget}, ${t}, vec4( 0, 0, 0, 1 ) );

			}
		`;super(r(e)),this.defineUniformAccessors({target:e.outputTarget})}};var oy=0,pd=1,Pi=class{constructor(e){this.renderer=e,this.maxBounces=15,this.frameBudget=25e4,this.maxTransparentBounces=15,this.maxSubsurfaceBounces=256,this.lowResMode=!1,this.maxSamples=0,this.asyncCompile=!1,this.compiling=!1,this.timing={count:0,frameTime:0,frameBudget:0},this._renderTask=null,this._compileState=new WeakMap,this._timestampUIDs=null,this._timingFrame=!1,this.outputTarget=Ht("Output #0"),this.prevOutputTarget=Ht("Output #1"),this.sampleCountTarget=ar("Sample Count"),this.sampleCountClearKernel=new Ai().setWorkgroupSize(8,8,1),this.outputTargetClearKernel=new Ai().setWorkgroupSize(8,8,1),this.auxTargetClearKernel=new Ai().setWorkgroupSize(8,8,1),this.auxTarget=null,this._auxPlaceholder=ct()}setAccumulateAux(e){if(!!this.auxTarget!==e){if(this.auxTarget?.dispose(),this.auxTarget=null,e){let{width:t,height:r}=this.outputTarget;this.auxTarget=ct("Auxiliary Images"),this.auxTarget.setSize(t,r,Re)}this.reset()}}setRandom(e){}setBVHData(e){}setTextures(e){}setMaterial(e){}setFilterGlossy(e){}setClamping(e,t){}setEnvironment(e,t,r){}setLights(e){}setBackground(e,t,r,i){}*compileKernels(e){let{renderer:t,_compileState:r}=this;if(!this.asyncCompile||!t.compileComputeAsync)return;let i=e.map(n=>n.kernel),s=i.filter(n=>!r.has(n));for(s.length>0&&(this.compiling=!0,yield,s.forEach(n=>r.set(n,oy)),t.compileComputeAsync(s).catch(()=>{}).finally(()=>{s.forEach(n=>r.set(n,pd))}));i.some(n=>r.get(n)!==pd);)this.compiling=!0,yield;this.compiling=!1}rebuild(){this._renderTask=null}setSize(e,t){e=Math.ceil(e),t=Math.ceil(t);let{width:r,height:i}=this.outputTarget;return r===e&&i===t?!1:(this.outputTarget.dispose(),this.prevOutputTarget.dispose(),this.sampleCountTarget.dispose(),this.outputTarget=this.outputTarget.clone(),this.prevOutputTarget=this.outputTarget.clone(),this.sampleCountTarget=this.sampleCountTarget.clone(),this.outputTarget.setSize(e,t),this.prevOutputTarget.setSize(e,t),this.sampleCountTarget.setSize(e,t),this.auxTarget?.setSize(e,t,Re),this.reset(),!0)}getSize(e){let{width:t,height:r}=this.outputTarget;return e.x=t,e.y=r,e}getPreviewPixelBudget(){return this.frameBudget}update(){let{renderer:e}=this;if(!e.initialized)return;this._renderTask||(this._renderTask=this.createRenderTask());let t=e.backend.trackTimestamp&&!this._timingFrame;this._timestampUIDs=t?[]:null,this._renderTask.next(),t&&this._timestampUIDs.length>0&&this._measureFrameTime(this._timestampUIDs),this._timestampUIDs=null}dispatch(e,t){let{renderer:r}=this;r.compute(e.kernel,t),this._timestampUIDs?.push(r.backend.getTimestampUID(e.kernel))}async _measureFrameTime(e){let{renderer:t,timing:r,frameBudget:i}=this,{backend:s}=t;this._timingFrame=!0,await t.resolveTimestampsAsync(ny.COMPUTE),this._timingFrame=!1,e.every(n=>s.hasTimestampQuery(n))&&(r.frameTime=e.reduce((n,l)=>n+s.getTimestamp(l),0),r.frameBudget=i,r.count++)}*createRenderTask(){}async getSampleCountsAsync(){return{min:0,max:0,avg:0}}resetSeed(){}reset(){let{renderer:e,outputTargetClearKernel:t,sampleCountClearKernel:r,outputTarget:i,prevOutputTarget:s,sampleCountTarget:n}=this;if(!e.initialized)return;let{width:l,height:a}=i,p=t.getDispatchSize(l,a);if(t.target=i,e.compute(t.kernel,p),t.target=s,e.compute(t.kernel,p),r.target=n,e.compute(r.kernel,p),this.auxTarget){let{auxTargetClearKernel:u}=this;u.target=this.auxTarget,e.compute(u.kernel,u.getDispatchSize(l,a,Re))}this._renderTask=null}dispose(){this.outputTarget.dispose(),this.prevOutputTarget.dispose(),this.sampleCountTarget.dispose(),this.sampleCountClearKernel.dispose(),this.outputTargetClearKernel.dispose(),this.auxTargetClearKernel.dispose(),this.auxTarget?.dispose(),this._auxPlaceholder.dispose(),this._renderTask=null}};var nu=new dd,Ds=class extends Pi{constructor(e){super(e),this.envInfo=new Ti,this._completedSamples=0,this._tileProgress=0,this.kernel=new Bs().setWorkgroupSize(8,8,1),this.kernel.envInfo=this.envInfo,this.backgroundInfo=new Si,this.kernel.backgroundInfo=this.backgroundInfo,this.lightsInfo=new Ci,this.kernel.lightsInfo=this.lightsInfo}resetSeed(){this.kernel.seed=0}reset(){super.reset(),this._completedSamples=0,this._tileProgress=0}async getSampleCountsAsync(){if(this.lowResMode)return{min:0,max:0,avg:0};let e=this._completedSamples,t=this._tileProgress;return{min:t===1?e+1:e,max:e+1,avg:e+t}}setBVHData(e){this.kernel.context.bvhData=e,this.rebuild()}rebuild(){super.rebuild(),this.kernel.needsUpdate=!0,this.reset()}setRandom(e){this.kernel.context.random=e,this.kernel.needsUpdate=!0,this.reset()}setMaterial(e){this.kernel.material=e.getData(),this.kernel.needsUpdate=!0,this.reset()}setFilterGlossy(e){this.kernel.filterGlossy=e===0?_s:1/e,this.reset()}setClamping(e,t){this.kernel.clampDirect=e,this.kernel.clampIndirect=t,this.reset()}setEnvironment(e){this.envInfo.updateFrom(e)}setLights(e){this.lightsInfo.updateFrom(this.renderer,e),this.reset()}setMultipleImportanceSampling(e){this.kernel.misEnabled=e?1:0,this.reset()}setEnvironmentParams(e,t){let{envInfo:r}=this,i=new fd().makeRotationFromEuler(t).invert();r.rotationNode.value.setFromMatrix4(i),r.intensityNode.value=e}setBackground(e){let{backgroundInfo:t}=this;t.dispose(),t.map=e}setBackgroundParams(e,t,r){let{backgroundInfo:i}=this,s=new fd().makeRotationFromEuler(t).invert();i.rotationNode.value.copy(s),i.intensity=e,i.blur=r}setTransmissiveBackground(e){this.kernel.transmissiveBackground=e,this.reset()}*createRenderTask(){let{renderer:e,kernel:t,maxBounces:r,maxTransparentBounces:i,maxSubsurfaceBounces:s,outputTarget:n,sampleCountTarget:l,lowResMode:a}=this,p=new dd;t.outputTarget=n,t.sampleCountTarget=l,t.maxBounces=r,t.maxTransparentBounces=i,t.maxSubsurfaceBounces=s,yield*this.compileKernels([t]);let u=0;for(;;){if(t.maxSamples=this.maxSamples,t.auxTarget=this.auxTarget??this._auxPlaceholder,t.accumulateAux=this.auxTarget?1:0,this.maxSamples!==0&&u>=this.maxSamples){yield;continue}this.getSize(nu);let c=a?1:Math.max(1,Math.ceil(nu.x*nu.y/this.frameBudget));p.x=Math.ceil(Math.sqrt(c)),p.y=Math.ceil(c/p.x);let f=this.lowResMode?1:p.x*p.y,h=0,d=t.tileSize;a?this.getSize(d):this.getSize(d).divide(p).ceil();let m=t.getDispatchSize(d.x,d.y);t.seed+=1,e.copyTextureToTexture(this.outputTarget,this.prevOutputTarget),[this.outputTarget,this.prevOutputTarget]=[this.prevOutputTarget,this.outputTarget],t.prevOutputTarget=this.prevOutputTarget,t.outputTarget=this.outputTarget;for(let x=0;x<p.x;x++)for(let v=0;v<p.y;v++)t.offset.set(x,v).multiply(d),this.dispatch(t,m),h=Math.min(h+1,f),this._completedSamples=u,this._tileProgress=h/f,yield;u++}}dispose(){super.dispose(),this.envInfo.dispose(),this.lightsInfo.dispose(),this.kernel.dispose()}};import{Matrix4 as Dd,StorageBufferAttribute as Xt,Vector2 as By}from"/vendor/three/0.187.0/three.webgpu.js";import{Vector2 as ay}from"/vendor/three/0.187.0/three.webgpu.js";import{StorageBufferAttribute as md}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as gd,storage as xd,globalId as ly}from"/vendor/three/0.187.0/three.tsl.js";import{StructTypeNode as Mn}from"/vendor/three/0.187.0/three.webgpu.js";var ou=class extends Mn{constructor(e,t,r=[]){super(e,t),this.dependencies=r}setup(e){for(let t of this.dependencies)t.build(e);super.setup(e)}},hd={throughputColor:"vec3f",currentBounce:"uint",resultColor:"vec4f",emission:"vec3f",scatterPdf:"float",scatterColor:"vec3f",minPdf:"float",origin:"vec3f",isFullyTransmissive:"uint",direction:"vec3f",side:"float",normal:"vec3f",objectIndex:"int",barycoord:"vec3f",pixelIndex:"uint",indices:"vec3u",seed:"uint",lightDirection:"vec3f",lightPdf:"float",lightEmission:"vec3f",lightDist:"float",lightBsdf:"vec3f",lightBsdfPdf:"float",rayIntersectionIndex:"int",shadowRayIntersectionIndex:"int",lightType:"int",dist:"float",alphaDepth:"uint",rayStart:"float",maxDist:"float",dispersionWavelength:"float",auxAlbedo:"uint",auxNormal:"uint",subsurfaceBounces:"uint",walkPending:"uint",mediumScattered:"uint"},Ce=new ou({...hd,medium:Cr.name},"RayData",[Cr]),sy=new Mn(hd).getLength();Ce.getLength=()=>sy+Cr.getLength();var Et=new Mn({origin:"vec3f",pixelIndex:"uint",direction:"vec3f",rngIndex:"uint",seed:"uint",maxDist:"float",_alignment0:"uint",_alignment1:"uint"},"TraceQueuedRay"),cr=new Mn({barycoord:"vec3f",objectIndex:"int",position:"vec3f",dist:"float",normal:"vec3f",side:"float",indices:"vec3u"},"TraceResult"),Bi=new Mn({objectIndex:"int",skippedOcclusion:"float",occlusion:"float"},"ShadowTraceResult"),Mt=0,Ri=1,Ns=2,Ei=0,Er=1,Fs=0,Mr=1,qt=2;var ks=class extends z{constructor(){let e={rayDataStorage:xd(new md(1,1),Ce),pixelQueue:xd(new md(1,1),"uint"),targetDimensions:gd(new ay),frameBudget:gd(0,"uint"),globalId:ly},t=$`
			fn compute( targetDimensions: vec2u, frameBudget: u32, globalId: vec3u ) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let pixelQueue = &${e.pixelQueue};

				if ( globalId.x >= targetDimensions.x || globalId.y >= targetDimensions.y ) {

					return;

				}

				let rayCount = min( frameBudget, arrayLength( rayDataStorage ) );
				let pixelCount = targetDimensions.x * targetDimensions.y;
				if ( globalId.x == 0u && globalId.y == 0u ) {

					pixelQueue[ ${Fs}u ] = 0u;
					pixelQueue[ ${Mr}u ] = select( 0u, pixelCount - rayCount, pixelCount >= rayCount );

				}

				let pixelHash = ( globalId.x << 16 ) | globalId.y;
				let pixelIndex = globalId.x + globalId.y * targetDimensions.x;
				if ( pixelIndex < rayCount ) {

					rayDataStorage[ pixelIndex ].pixelIndex = pixelHash;
					rayDataStorage[ pixelIndex ].resultColor = vec4f( 0.0 );
					rayDataStorage[ pixelIndex ].throughputColor = vec3f( 0.0 );
					rayDataStorage[ pixelIndex ].objectIndex = - 1;
					rayDataStorage[ pixelIndex ].alphaDepth = 0u;
					rayDataStorage[ pixelIndex ].rayIntersectionIndex = - 1;
					rayDataStorage[ pixelIndex ].shadowRayIntersectionIndex = - 1;

				} else {

					pixelQueue[ ${qt}u + pixelIndex - rayCount ] = pixelHash;

				}

			}
		`;super(t(e)),this.defineUniformAccessors(e)}};import{StorageBufferAttribute as su}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as Dr,storage as au,textureStore as Ls,globalId as uy}from"/vendor/three/0.187.0/three.tsl.js";var Os=class extends z{constructor(){let e={envInfo:{value:null},backgroundInfo:{value:null},lightsInfo:{value:null},prevOutputTarget:Ls(Ht()).toReadOnly(),outputTarget:Ls(Ht()).toWriteOnly(),sampleCountTarget:Ls(ar()).toReadWrite(),auxTarget:Ls(ct()).toReadWrite(),misEnabled:Dr(1,"uint"),rayCount:Dr(0,"uint"),maxBounces:Dr(5,"uint"),clampDirect:Dr(0),clampIndirect:Dr(10),transmissiveBackground:Dr(bi),accumulateAux:Dr(0,"uint"),rayDataStorage:au(new su(1,1),Ce),rayIntersectionsStorage:au(new su(1,1),cr),shadowRayIntersectionsStorage:au(new su(1,1),Bi),globalId:uy},t=ge("envInfo.value.totalSumNode",e),r=ge("envInfo.value.intensityNode",e),i=ge("envInfo.value.sampleColor",e),s=ge("envInfo.value.sampleDir",e),n=ge("envInfo.value.getDirPdf",e),l=ge("envInfo.value.getWeight",e),a=ge("backgroundInfo.value.sampleColor",e),p=ge("lightsInfo.value.countNode",e),u=pe("lightsInfo.value.randomLightSample",e),c=pe("lightsInfo.value.intersectLightAtIndex",e),f=pe("lightsInfo.value.getLightWeight",e),h=pe("lightsInfo.value.getLightsWeight",e),d=pe("lightsInfo.value.selectLight",e),m=pe("lightsInfo.value.isLightVisibleToCamera",e),x=$`

			fn compute(
				// settings
				misEnabled: u32,
				rayCount: u32,
				maxBounces: u32,
				clampDirect: f32,
				clampIndirect: f32,
				transmissiveBackground: u32,
				accumulateAux: u32,

				globalId: vec3u
			) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let rayIntersectionsStorage = &${e.rayIntersectionsStorage};
				let shadowRayIntersectionsStorage = &${e.shadowRayIntersectionsStorage};

				// bound by "rayCount" rather than the pool length. See MaterialKernel
				let index = globalId.x;
				if ( index >= rayCount ) {

					return;

				}

				// skip slots that have never spawned a ray
				// whole structs are read from the buffer, not the pointer alias, which Safari fails to compile
				let input = ${e.rayDataStorage}[ index ];
				if ( input.rayIntersectionIndex < 0 ) {

					return;

				}

				let indexUV = vec2u( input.pixelIndex >> 16, input.pixelIndex & 0xFFFF );
				${Xe}( indexUV, input.seed, input.currentBounce + input.alphaDepth + input.subsurfaceBounces );

				// one-sample NEE chooses between the analytic lights and the environment, matched with
				// the megakernel
				let envActive = ${t} > 0.0 && ${r} > 0.0;
				let lightsCount = ${p};
				var envWeight = 0.0;
				if ( envActive ) {

					envWeight = ${l}();

				}

				var resultColor = input.resultColor;
				var throughputColor = input.throughputColor;

				// added last, since a camera ray miss overwrites the pixel with the background
				var lightHits = vec3f( 0.0 );
				var cameraHitLight = false;

				// resolve the previous surface's NEE shadow ray (pre-scatter throughput). The index
				// is negative when no shadow ray was enqueued last frame
				if ( input.shadowRayIntersectionIndex >= 0 && input.lightPdf > 0.0 ) {

					let shadowHit = ${e.shadowRayIntersectionsStorage}[ u32( input.shadowRayIntersectionIndex ) ];
					let occluded = shadowHit.objectIndex >= 0;
					if ( ! occluded ) {

						// env + area lights are also found by bsdf rays, so MIS-weight them. Punctual lights, and lights
						// past the bounce limit, take full weight. Bsdf rays stopped by faces the shadow ray skipped can't
						// find the light, so their share of the weight moves to the light sample
						let isMISWeighted = ${fs}( input.lightType ) && input.currentBounce < maxBounces;
						let misWeight = select( 1.0, mix( ${Bt}( input.lightPdf, input.lightBsdfPdf ), 1.0, shadowHit.skippedOcclusion ), isMISWeighted );
						let directLight = throughputColor * input.lightEmission * input.lightBsdf * misWeight * ( 1.0 - shadowHit.occlusion ) / input.lightPdf;
						let contribution = ${Se}( directLight, input.currentBounce, clampDirect, clampIndirect );
						resultColor += vec4f( contribution, 0.0 );

					}

				}

				// emission gathered at the previous surface (pre-scatter throughput). Found light clamps
				// at the depth of the segment that found it, as in Cycles, so one less than the current
				let emission = ${Se}( throughputColor * input.emission, max( input.currentBounce, 1u ) - 1u, clampDirect, clampIndirect );
				resultColor += vec4f( emission, 0.0 );

				// reconstruct the scatter record staged by MaterialKernel
				var scatterRec: ${ut};
				scatterRec.color = input.scatterColor;
				scatterRec.pdf = input.scatterPdf;

				// the bounce limit, russian roulette, and terminating scatter checks all ran in
				// MaterialKernel, which stages a zeroed pdf and skips the bounce trace when they fire
				var isTerminated = all( throughputColor == vec3f( 0.0 ) ) || input.currentBounce >= maxBounces || ${mi}( scatterRec );

				// apply the scatter across the traced segment, including the weight of a medium walk
				if ( ! isTerminated ) {

					throughputColor *= scatterRec.color / scatterRec.pdf;

				}

				let isWalking = ! isTerminated && input.walkPending != 0u;
				if ( isWalking ) {

					// the walk hasn't reached a surface yet, so there's no hit to stage. Clear what was
					// resolved and let MediumKernel accumulate the walk's weight
					rayDataStorage[ index ].emission = vec3f( 0.0 );
					rayDataStorage[ index ].lightPdf = 0.0;
					rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
					rayDataStorage[ index ].scatterColor = vec3f( 1.0 );
					rayDataStorage[ index ].scatterPdf = 1.0;

				} else if ( ! isTerminated ) {

					let hitResult = ${e.rayIntersectionsStorage}[ u32( input.rayIntersectionIndex ) ];
					let didHit = hitResult.objectIndex >= 0;
					let surfaceDist = select( ${et}, hitResult.dist, didHit );

					// NEE's total weight at the vertex this segment left, for MIS. Camera segments have none,
					// and neither do scatter events in a medium
					let isMISWeighted = misEnabled != 0u && input.currentBounce > 0u && input.mediumScattered == 0u;
					var lightTotalWeight = 0.0;
					if ( isMISWeighted ) {

						lightTotalWeight = ${h}( input.origin ) + envWeight;

					}

					// forward hits on area lights along the traced segment, which starts "rayStart" along the ray.
					// Camera rays only see visibleToCameraRays lights, at full weight
					for ( var li = 0u; li < lightsCount; li ++ ) {

						if ( input.currentBounce == 0u && ! ${m}( li ) ) {

							continue;

						}

						var lightRec: ${qe};
						if ( ${c}( input.origin, input.direction, li, &lightRec ) && lightRec.dist > input.rayStart && lightRec.dist < input.rayStart + surfaceDist ) {

							var misWeight = 1.0;
							if ( isMISWeighted ) {

								let selectionPdf = ${f}( li, input.origin ) / lightTotalWeight;
								misWeight = ${Bt}( input.scatterPdf, lightRec.pdf * selectionPdf );

							}

							lightHits += ${Se}( lightRec.emission * throughputColor * misWeight, input.currentBounce, clampDirect, clampIndirect );
							cameraHitLight = cameraHitLight || input.currentBounce == 0u;

						}

					}

					if ( didHit ) {

						// stage the hit for MaterialKernel
						rayDataStorage[ index ].barycoord = hitResult.barycoord;
						rayDataStorage[ index ].normal = hitResult.normal;
						rayDataStorage[ index ].side = hitResult.side;
						rayDataStorage[ index ].indices = hitResult.indices;
						rayDataStorage[ index ].objectIndex = hitResult.objectIndex;
						rayDataStorage[ index ].dist = hitResult.dist;

						// next event estimation: choose one light or the environment by its estimated
						// contribution. MaterialKernel evaluates the bsdf and enqueues the shadow ray.
						var lightPdf = 0.0;
						if ( misEnabled != 0u ) {

							let ruv = ${di}( ${Zo} );
							var selectionPdf = 0.0;
							let lightIndex = ${d}( hitResult.position, envWeight, ruv.x, &selectionPdf );
							if ( selectionPdf > 0.0 ) {

								var lightRec: ${qe};
								if ( envActive && lightIndex == lightsCount ) {

									// the environment, sampled from its CDF, as a light of kind ENVIRONMENT
									let envSample = ${s}( ruv.yz );
									lightRec.direction = envSample.direction;
									lightRec.emission = envSample.color;
									lightRec.pdf = envSample.pdf;
									lightRec.dist = ${et};
									lightRec.lightType = ${Pn};

								} else {

									lightRec = ${u}( lightIndex, hitResult.position, ruv.yz );

								}

								lightPdf = lightRec.pdf * selectionPdf;
								rayDataStorage[ index ].lightDirection = lightRec.direction;
								rayDataStorage[ index ].lightEmission = lightRec.emission;
								rayDataStorage[ index ].lightDist = lightRec.dist;
								rayDataStorage[ index ].lightType = lightRec.lightType;

							}

						}

						rayDataStorage[ index ].lightPdf = lightPdf;

					} else {

						// the segment escaped the scene: gather the environment for opaque paths, or
						// the background for camera segments and fully transmissive paths
						if ( input.currentBounce > 0u && input.isFullyTransmissive == 0u ) {

							var misWeight = 1.0;
							if ( isMISWeighted && envActive ) {

								// match the env pdf scaling used by the NEE selection so the two estimators balance
								let envPdf = ${n}( input.direction ) * envWeight / lightTotalWeight;
								misWeight = ${Bt}( input.scatterPdf, envPdf );

							}

							let environment = ${i}( input.direction ).rgb * throughputColor * misWeight;
							let contribution = ${Se}( environment, input.currentBounce, clampDirect, clampIndirect );
							resultColor += vec4f( contribution, 0.0 );

						} else {

							// hit the background
							// support multiple transparent background blending techniques
							let rng = ${Ee}( ${Ko} );
							let bg = ${a}( input.direction, rng );
							if ( input.currentBounce == 0u ) {

								// sample the background directly if this is the primary ray
								let background = ${Se}( bg.a * bg.rgb, input.currentBounce, clampDirect, clampIndirect );
								resultColor = vec4f( background, bg.a );

							} else {

								// transmissive ray handling
								let env = ${i}( input.direction );
								let avg = saturate( dot( throughputColor, vec3f( 1.0 / 3.0 ) ) );
								let transparency = ( 1.0 - bg.a ) * avg;

								var misWeight = 1.0;
								if ( misEnabled != 0u && envActive ) {

									let envPdf = ${n}( input.direction );
									misWeight = ${Bt}( input.scatterPdf, envPdf );

								}

								if ( transmissiveBackground == ${Cs}u ) {

									// display the env map through transmissive surfaces
									let background = ${Se}( env.rgb * throughputColor * misWeight, input.currentBounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0,
									);

								} else if ( transmissiveBackground == ${As}u ) {

									// fade the background by the throughput color average
									let background = ${Se}( bg.a * bg.rgb * throughputColor * misWeight, input.currentBounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0 - transparency,
									);

								} else {

									// fade the background by the throughput color average, mixing in env lighting
									var light = mix( env.rgb, bg.rgb, bg.a ) * misWeight;
									let background = ${Se}( light * throughputColor, input.currentBounce, clampDirect, clampIndirect );
									resultColor = vec4f(
										resultColor.rgb + background,
										1.0 - transparency,
									);

								}

							}

						}

						isTerminated = true;

					}

				}

				// visible lights are opaque against a transparent background
				resultColor += vec4f( lightHits, 0.0 );
				if ( cameraHitLight ) {

					resultColor.a = 1.0;

				}

				// the color rows are stored top down to match a rasterized render target
				let colorIndex = vec2u( indexUV.x, textureDimensions( ${e.outputTarget} ).y - 1u - indexUV.y );
				let storedSamples = textureLoad( ${e.sampleCountTarget}, indexUV ).r & ${Gt}u;

				if ( isTerminated ) {

					// blend the finished sample into the output and free the slot for a new camera ray
					let sampleCount = storedSamples + 1;
					let prevColor = textureLoad( ${e.prevOutputTarget}, colorIndex );
					let blendedColor = ${ls}( prevColor, resultColor, 1.0 / f32( sampleCount ) );
					textureStore( ${e.sampleCountTarget}, indexUV, vec4( ${wi}u | sampleCount ) );
					textureStore( ${e.outputTarget}, colorIndex, blendedColor );

					// the auxiliary images are blended the same way, in the same row order
					if ( accumulateAux != 0u ) {

						var auxPacked: vec3u;
						for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

							auxPacked[ layer ] = textureLoad( ${e.auxTarget}, colorIndex, layer ).r;

						}

						let albedo = unpack4x8unorm( input.auxAlbedo ).rgb;
						let normal = unpack4x8snorm( input.auxNormal ).xyz;
						auxPacked = ${Vo}( auxPacked, albedo, normal, 1.0 / f32( sampleCount ) );
						for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

							textureStore( ${e.auxTarget}, colorIndex, layer, vec4u( auxPacked[ layer ] ) );

						}

					}

					rayDataStorage[ index ].objectIndex = - 1;

				} else {

					// show the path so far, which the first sample overwrites
					if ( storedSamples == 0u ) {

						textureStore( ${e.outputTarget}, colorIndex, resultColor );

					}

					rayDataStorage[ index ].resultColor = resultColor;
					rayDataStorage[ index ].throughputColor = throughputColor;

				}

			}
		`;super(x(e)),this.defineUniformAccessors(e)}};import{Vector2 as cy}from"/vendor/three/0.187.0/three.webgpu.js";import{StorageBufferAttribute as Dn}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as Nr,storage as Nn,textureStore as py,globalId as fy}from"/vendor/three/0.187.0/three.tsl.js";var Us=class extends z{constructor(){let e={material:{value:null},seed:Nr(0,"uint"),targetDimensions:Nr(new cy),maxSamples:Nr(0,"uint"),rayCount:Nr(0,"uint"),filterGlossy:Nr(1),maxTransparentBounces:Nr(5,"uint"),maxBounces:Nr(5,"uint"),sampleCountTarget:py(ar()).toReadWrite(),rayDataStorage:Nn(new Dn(1,1),Ce),rayQueue:Nn(new Dn(1,1),Et),shadowRayQueue:Nn(new Dn(1,1),Et),queueCounters:Nn(new Dn(1,1),"uint").toAtomic(),pixelQueue:Nn(new Dn(1,1),"uint").toAtomic(),globalId:fy},t=$e("bvhData.fns.getCameraRay"),r=$e("bvhData.fns.sampleTrianglePoint"),i=$e("bvhData.fns.getSurfaceRecord"),s=pe("material.value.bsdfSample",e),n=pe("material.value.bsdfEvalPdf",e),l=Be("bvhData.storage.materials"),a=Be("bvhData.storage.transforms"),p=$`

			fn compute(
				seed: u32,
				targetDimensions: vec2u,
				maxSamples: u32,
				rayCount: u32,
				filterGlossy: f32,
				maxTransparentBounces: u32,
				maxBounces: u32,

				globalId: vec3u
			) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let rayQueue = &${e.rayQueue};
				let shadowRayQueue = &${e.shadowRayQueue};
				let queueCounters = &${e.queueCounters};
				let pixelQueue = &${e.pixelQueue};

				// bound by "rayCount" rather than the pool length. The dispatch rounds up to the
				// workgroup size and those extra slots hold a zeroed pixel index
				let index = globalId.x;
				if ( index >= rayCount ) {

					return;

				}

				// whole structs are read from the buffer, not the pointer alias, which Safari fails to compile
				let input = ${e.rayDataStorage}[ index ];
				if ( input.objectIndex < 0 ) {

					// the slot's path has terminated: recycle the pixel through the overflow queue and
					// generate a fresh camera ray
					var pixelIndex = input.pixelIndex;
					let elementCount = atomicLoad( &pixelQueue[ ${Mr}u ] );
					if ( elementCount > 0u ) {

						// TODO: If we've pulled off a pixel that's already finished we currently just
						// write a no-op ray, wasting a frame. It may be better to iterate over a few
						// points in the queue to see if we can find one we can use.
						let queueIndex = atomicAdd( &pixelQueue[ ${Fs}u ], 1u ) % elementCount;
						pixelIndex = atomicExchange( &pixelQueue[ ${qt}u + queueIndex ], pixelIndex );

					}

					let indexUV = vec2u( pixelIndex >> 16, pixelIndex & 0xFFFF );

					// skip the pixel if it has hit the sample limit
					let combinedField = textureLoad( ${e.sampleCountTarget}, indexUV ).r;
					let samples = ( ${Gt}u & combinedField );
					let isComplete = maxSamples != 0u && samples >= maxSamples;

					if ( isComplete ) {

						rayDataStorage[ index ].pixelIndex = pixelIndex;
						rayDataStorage[ index ].rayIntersectionIndex = - 1;
						rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
						return;

					}

					${Xe}( indexUV, seed + samples, 0 );

					let uv = vec2f( indexUV ) / vec2f( targetDimensions );
					let jitteredUv = uv + ${Ee}( ${jo} ) / vec2f( targetDimensions );
					var ray: ${D};
					if ( ! ${t}( jitteredUv, vec2f( targetDimensions ), &ray ) ) {

						// the camera declined the pixel, so leave the slot dormant for this round. With a
						// sample cap the pixel counts as finished, since it will never take a sample
						// TODO: same as above - this work is a bit wasteful and leaves slots empty for
						// a frame. Is it possible to quickly skip rays outside the mask or are finished?
						if ( maxSamples != 0u ) {

							textureStore( ${e.sampleCountTarget}, indexUV, vec4( ( combinedField & ~${Gt}u ) | maxSamples ) );

						}

						rayDataStorage[ index ].pixelIndex = pixelIndex;
						rayDataStorage[ index ].rayIntersectionIndex = - 1;
						rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
						return;

					}

					ray.direction = normalize( ray.direction );

					let rayIndex = atomicAdd( &queueCounters[ ${Mt}u ], 1u );
					rayQueue[ rayIndex ].origin = ray.origin;
					rayQueue[ rayIndex ].direction = ray.direction;
					rayQueue[ rayIndex ].pixelIndex = pixelIndex;
					rayQueue[ rayIndex ].rngIndex = 0u;
					rayQueue[ rayIndex ].seed = seed + samples;
					rayQueue[ rayIndex ].maxDist = ray.maxDist;

					rayDataStorage[ index ].origin = ray.origin;
					rayDataStorage[ index ].direction = ray.direction;
					rayDataStorage[ index ].pixelIndex = pixelIndex;
					rayDataStorage[ index ].seed = seed + samples;
					rayDataStorage[ index ].currentBounce = 0u;
					rayDataStorage[ index ].throughputColor = vec3f( 1.0 );
					rayDataStorage[ index ].resultColor = vec4f( 0.0, 0.0, 0.0, 1.0 );
					rayDataStorage[ index ].scatterColor = vec3f( 1.0 );
					rayDataStorage[ index ].scatterPdf = 1.0;
					rayDataStorage[ index ].minPdf = 1.0;
					rayDataStorage[ index ].isFullyTransmissive = 1u;
					rayDataStorage[ index ].emission = vec3f( 0.0 );
					rayDataStorage[ index ].lightPdf = 0.0;
					rayDataStorage[ index ].alphaDepth = 0u;
					rayDataStorage[ index ].rayStart = 0.0;
					rayDataStorage[ index ].maxDist = ray.maxDist;
					rayDataStorage[ index ].rayIntersectionIndex = i32( rayIndex );
					rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
					rayDataStorage[ index ].dispersionWavelength = - mix( ${yi}.0, ${vi}.0, ${At}( ${es} ) );
					rayDataStorage[ index ].auxAlbedo = 0u;
					rayDataStorage[ index ].auxNormal = 0u;
					rayDataStorage[ index ].subsurfaceBounces = 0u;
					rayDataStorage[ index ].walkPending = 0u;
					rayDataStorage[ index ].mediumScattered = 0u;
					rayDataStorage[ index ].medium.inside = 0u;

					// write the active params & dispatched flag
					textureStore( ${e.sampleCountTarget}, indexUV, vec4( ${Xf}u | ${wi}u | samples ) );

				} else if ( input.walkPending == 0u ) {

					// evaluate the surface staged by LogicKernel. Paths still walking a medium have none
					let indexUV = vec2u( input.pixelIndex >> 16, input.pixelIndex & 0xFFFF );
					${Xe}( indexUV, input.seed, input.currentBounce + input.alphaDepth + input.subsurfaceBounces );

					// the medium the path is in, which the bsdf sample may enter or exit
					var medium = ${ms}( input.medium );
					let segmentInMedium = medium.inside;

					let objectInfo = ${a}[ u32( input.objectIndex ) ];
					var materialInfo = ${l}[ objectInfo.materialIndex ];

					// a matte surface hit by the camera ray renders as a fully transparent
					let isMatte = materialInfo.matte != 0 && input.currentBounce == 0u;
					if ( isMatte ) {

						rayDataStorage[ index ].resultColor = vec4f( 0.0 );
						rayDataStorage[ index ].throughputColor = vec3f( 0.0 );
						rayDataStorage[ index ].emission = vec3f( 0.0 );
						rayDataStorage[ index ].lightPdf = 0.0;
						rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
						return;

					}

					// apply per-object colors
					materialInfo.color *= objectInfo.color.rgb;
					materialInfo.opacity *= objectInfo.color.a;

					var vertexData = ${r}( input.barycoord, input.indices );
					vertexData.normal = normalize( transpose( objectInfo.inverseMatrixWorld ) * vertexData.normal );
					vertexData.tangent = vec4f( ( objectInfo.matrixWorld * vec4f( vertexData.tangent.xyz, 0.0 ) ).xyz, vertexData.tangent.w );
					vertexData.position = objectInfo.matrixWorld * vertexData.position;

					let view = - input.direction;

					// blur glossy surfaces after low-probability bounces to suppress fireflies,
					// from the Cycles "filter glossy" approach in integrator/surface_shader.h
					let blurRoughness = sqrt( clamp( 1.0 - filterGlossy * input.minPdf, 0.0, 1.0 ) ) * 0.5;

					var surface = ${i}( materialInfo, vertexData, input.side, input.normal, view, blurRoughness );

					// Stochastically pass through partially transparent surfaces by re-enqueueing
					// the ray at the hit point, advancing the alpha depth but not the bounce count.
					let passesThrough = ${At}( ${is} ) > surface.opacity;
					if ( passesThrough ) {

						// out of transparent bounces, so stop rather than shading a surface that
						// should be invisible. A zeroed throughput terminates in LogicKernel.
						if ( input.alphaDepth >= maxTransparentBounces ) {

							rayDataStorage[ index ].throughputColor = vec3f( 0.0 );
							rayDataStorage[ index ].emission = vec3f( 0.0 );
							rayDataStorage[ index ].lightPdf = 0.0;
							rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
							return;

						}

						let alphaOrigin = ${Wt}( vertexData.position.xyz, input.direction, input.normal );
						if ( segmentInMedium ) {

							// inside a medium, MediumKernel walks on past the surface, from the surface
							rayDataStorage[ index ].walkPending = 1u;
							rayDataStorage[ index ].origin = alphaOrigin;
							rayDataStorage[ index ].rayStart = 0.0;

						} else {

							let alphaIndex = atomicAdd( &queueCounters[ ${Mt}u ], 1u );
							rayQueue[ alphaIndex ].origin = alphaOrigin;
							rayQueue[ alphaIndex ].direction = input.direction;
							rayQueue[ alphaIndex ].pixelIndex = input.pixelIndex;
							rayQueue[ alphaIndex ].rngIndex = input.currentBounce + input.alphaDepth + 1u + input.subsurfaceBounces;
							rayQueue[ alphaIndex ].seed = input.seed;
							// the origin advanced to the hit point, so the remaining budget shrinks by the
							// distance already traced
							rayQueue[ alphaIndex ].maxDist = max( input.maxDist - input.dist, 0.0 );
							rayDataStorage[ index ].rayIntersectionIndex = i32( alphaIndex );

							// the ray keeps its origin, and only the queued traversal starts past the surface
							rayDataStorage[ index ].rayStart = input.rayStart + input.dist;

						}

						// the surface is skipped, so no scatter or emission is staged for LogicKernel.
						// "pdf" is left alone so the previous scatter still weights the forward MIS,
						// and "bsdf" matches it so applying the scatter leaves the throughput as is.
						rayDataStorage[ index ].alphaDepth = input.alphaDepth + 1u;
						rayDataStorage[ index ].emission = vec3f( 0.0 );
						rayDataStorage[ index ].scatterColor = vec3f( input.scatterPdf );
						rayDataStorage[ index ].lightPdf = 0.0;
						rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;
						return;

					}

					// apply the hero wavelength to dispersive surfaces, folding the spectral weight
					// into the throughput at the path's first dispersive interaction
					var throughputColor = input.throughputColor;
					let isDispersive = materialInfo.dispersion > 0.0 && surface.ior > 1.0 && surface.transmission > 0.0 && ! surface.thinWall;
					if ( isDispersive ) {

						let wavelength = abs( input.dispersionWavelength );
						${Is}( &surface, materialInfo.dispersion, wavelength );
						if ( input.dispersionWavelength < 0.0 ) {

							rayDataStorage[ index ].dispersionWavelength = wavelength;
							throughputColor *= ${$s}( wavelength );
							rayDataStorage[ index ].throughputColor = throughputColor;

						}

					}

					// attenuate the light transmitted through the volume when exiting a backface so
					// the surface's emission and NEE resolve against the attenuated throughput. MediumKernel
					// already applied a scattering medium's transmittance
					if ( input.side < 0.0 && materialInfo.transmission > 0.0 && ! segmentInMedium ) {

						throughputColor *= ${Ss}( input.dist, materialInfo.attenuationColor, materialInfo.attenuationDistance );
						rayDataStorage[ index ].throughputColor = throughputColor;

					}

					// sample the next bounce direction and stage the scatter state for LogicKernel
					var scatterRec = ${s}( view, surface );
					let newBounce = input.currentBounce + 1u;

					// The first surface the path scatters off fills the denoiser's auxiliary images. Until one is
					// found the last mirror or transmission hit stands in, for paths that reach the environment.
					// The albedo is tinted by the throughput of the surfaces looked past to reach it. The packed
					// albedo's alpha marks the surface found
					let auxFound = unpack4x8unorm( input.auxAlbedo ).a != 0.0;
					if ( ! auxFound ) {

						let isAuxSurface = ${Wo}( surface, scatterRec );
						let auxAlbedo = saturate( throughputColor * surface.color );
						rayDataStorage[ index ].auxAlbedo = pack4x8unorm( vec4f( auxAlbedo, select( 0.0, 1.0, isAuxSurface ) ) );
						rayDataStorage[ index ].auxNormal = pack4x8snorm( vec4f( surface.normal, 0.0 ) );

					}

					// decide termination now so finished paths skip the bounce trace entirely - a
					// zeroed pdf reads as a terminating scatter in LogicKernel, which still resolves
					// the surface's emission and NEE before freeing the slot
					var isTerminated = newBounce >= maxBounces || all( scatterRec.color == vec3f( 0.0 ) ) || ${mi}( scatterRec );

					// russian roulette early out:
					// Matches Cycles path_state_continuation_probability in integrator/path_state.h
					if ( ! isTerminated && newBounce >= 3u ) {

						let rrThroughput = throughputColor * scatterRec.color / scatterRec.pdf;
						let rrProb = saturate( sqrt( max( max( rrThroughput.r, rrThroughput.g ), rrThroughput.b ) ) );
						isTerminated = rrProb <= 0.0 || ${At}( ${Jo} ) > rrProb;
						if ( ! isTerminated ) {

							// fold the survival boost into the scatter color so LogicKernel's
							// throughput update applies it without a separate division
							scatterRec.color /= rrProb;

						}

					}

					// Write the ray storage content here since things like the emissive value is read
					// in the logic kernel on the subsequent frame.
					rayDataStorage[ index ].scatterColor = scatterRec.color;
					rayDataStorage[ index ].scatterPdf = select( scatterRec.pdf, 0.0, isTerminated );
					rayDataStorage[ index ].minPdf = min( input.minPdf, scatterRec.pdf );
					rayDataStorage[ index ].isFullyTransmissive = input.isFullyTransmissive & select( 0u, 1u, scatterRec.isTransmissive );
					rayDataStorage[ index ].emission = surface.emission;
					rayDataStorage[ index ].currentBounce = newBounce;
					rayDataStorage[ index ].mediumScattered = 0u;

					// As in "KHR_materials_scatter", the surface bsdf is the medium's boundary: refracting out
					// ends the walk, reflecting continues it
					if ( segmentInMedium && u32( input.objectIndex ) == medium.object && input.side < 0.0 && scatterRec.isTransmissive ) {

						medium.inside = false;

					}

					// refracting into a front face of a scattering material starts a random walk inside it
					if ( scatterRec.isTransmissive && ! surface.thinWall && input.side > 0.0 ) {

						let entered = ${gs}( materialInfo, u32( input.objectIndex ) );
						if ( entered.inside ) {

							medium = entered;

						}

					}

					// keep the medium the path left or entered
					if ( segmentInMedium || medium.inside ) {

						// a whole struct is written to the buffer, not through the pointer alias, which Safari fails to compile
						${e.rayDataStorage}[ index ].medium = ${Lf}( medium );

					}

					// the NEE shadow ray below still resolves the surface's direct light, so only
					// the bounce segment is skipped for finished paths. Inside a medium, MediumKernel walks it
					if ( ! isTerminated && medium.inside ) {

						rayDataStorage[ index ].walkPending = 1u;
						rayDataStorage[ index ].origin = ${Wt}( vertexData.position.xyz, scatterRec.direction, input.normal );
						rayDataStorage[ index ].direction = scatterRec.direction;
						rayDataStorage[ index ].rayStart = 0.0;

					} else if ( ! isTerminated ) {

						let rayIndex = atomicAdd( &queueCounters[ ${Mt}u ], 1u );
						rayQueue[ rayIndex ].origin = ${Wt}( vertexData.position.xyz, scatterRec.direction, input.normal );
						rayQueue[ rayIndex ].direction = scatterRec.direction;
						rayQueue[ rayIndex ].pixelIndex = input.pixelIndex;
						rayQueue[ rayIndex ].rngIndex = newBounce + input.alphaDepth + input.subsurfaceBounces;
						rayQueue[ rayIndex ].seed = input.seed;
						rayQueue[ rayIndex ].maxDist = 0.0;
						rayDataStorage[ index ].rayIntersectionIndex = i32( rayIndex );

						rayDataStorage[ index ].origin = rayQueue[ rayIndex ].origin;
						rayDataStorage[ index ].direction = scatterRec.direction;
						rayDataStorage[ index ].rayStart = 0.0;

					}

					// evaluate the bsdf toward the light LogicKernel selected and enqueue the shadow ray.
					// the light pdf will be 0 if NEE is disabled.
					var lightPdf = input.lightPdf;
					if ( lightPdf > 0.0 ) {

						let evalRec = ${n}( view, input.lightDirection, surface );
						if ( evalRec.pdf > 0.0 ) {

							rayDataStorage[ index ].lightBsdf = evalRec.color;
							rayDataStorage[ index ].lightBsdfPdf = evalRec.pdf;

							let shadowIndex = atomicAdd( &queueCounters[ ${Ri}u ], 1u );
							shadowRayQueue[ shadowIndex ].origin = ${Wt}( vertexData.position.xyz, input.lightDirection, input.normal );
							shadowRayQueue[ shadowIndex ].direction = input.lightDirection;
							shadowRayQueue[ shadowIndex ].pixelIndex = input.pixelIndex;
							shadowRayQueue[ shadowIndex ].rngIndex = input.currentBounce + input.alphaDepth + input.subsurfaceBounces;
							shadowRayQueue[ shadowIndex ].seed = input.seed;
							shadowRayQueue[ shadowIndex ].maxDist = input.lightDist * ( 1.0 - ${ps} );
							rayDataStorage[ index ].shadowRayIntersectionIndex = i32( shadowIndex );

						} else {

							lightPdf = 0.0;

						}

					}

					if ( lightPdf <= 0.0 ) {

						rayDataStorage[ index ].lightPdf = 0.0;
						rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;

					}

				}

			}
		`;super(p(e)),this.defineUniformAccessors(e)}};import{StorageBufferAttribute as lu}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as uu,globalId as dy}from"/vendor/three/0.187.0/three.tsl.js";var zs=class extends z{constructor(){let e={rayQueue:uu(new lu(1,1),Et),queueCounters:uu(new lu(1,1),"uint"),rayIntersectionsStorage:uu(new lu(1,1),cr),globalId:dy},t=Be("bvhData.fns.raycastFirstHit.outputType"),r=Be("bvhData.fns.raycastFirstHit"),i=$`

			fn compute( globalId: vec3u ) -> void {

				let rayQueue = &${e.rayQueue};
				let queueCounters = &${e.queueCounters};
				let rayIntersectionsStorage = &${e.rayIntersectionsStorage};

				let index = globalId.x;
				if ( index >= queueCounters[ ${Mt}u ] ) {

					return;

				}

				// whole structs are read from the buffer, not the pointer alias, which Safari fails to compile
				let queuedRay = ${e.rayQueue}[ index ];
				let indexUV = vec2u( queuedRay.pixelIndex >> 16, queuedRay.pixelIndex & 0xFFFF );
				${Xe}( indexUV, queuedRay.seed, queuedRay.rngIndex );

				let ray = Ray( queuedRay.origin, queuedRay.direction, queuedRay.maxDist );
				var hitResult: ${t};
				if ( ${r}( ray, &hitResult ) ) {

					rayIntersectionsStorage[ index ].barycoord = hitResult.barycoord;
					rayIntersectionsStorage[ index ].objectIndex = i32( hitResult.objectIndex );
					rayIntersectionsStorage[ index ].position = ray.origin + ray.direction * hitResult.dist;
					rayIntersectionsStorage[ index ].dist = hitResult.dist;
					rayIntersectionsStorage[ index ].normal = hitResult.normal.xyz;
					rayIntersectionsStorage[ index ].side = hitResult.side;
					rayIntersectionsStorage[ index ].indices = hitResult.indices.xyz;

				} else {

					rayIntersectionsStorage[ index ].objectIndex = - 1;

				}

			}
		`;super(i(e)),this.defineUniformAccessors(e)}};import{StorageBufferAttribute as cu}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as pu,globalId as hy}from"/vendor/three/0.187.0/three.tsl.js";var Hs=class extends z{constructor(){let e={shadowRayQueue:pu(new cu(1,1),Et),queueCounters:pu(new cu(1,1),"uint"),shadowRayIntersectionsStorage:pu(new cu(1,1),Bi),globalId:hy},t=Be("bvhData.fns.raycastFirstHit.outputType"),r=Be("bvhData.fns.raycastShadow"),i=$`

			fn compute( globalId: vec3u ) -> void {

				let shadowRayQueue = &${e.shadowRayQueue};
				let queueCounters = &${e.queueCounters};
				let shadowRayIntersectionsStorage = &${e.shadowRayIntersectionsStorage};

				let index = globalId.x;
				if ( index >= queueCounters[ ${Ri}u ] ) {

					return;

				}

				// whole structs are read from the buffer, not the pointer alias, which Safari fails to compile
				let queuedRay = ${e.shadowRayQueue}[ index ];
				let indexUV = vec2u( queuedRay.pixelIndex >> 16, queuedRay.pixelIndex & 0xFFFF );
				${Xe}( indexUV, queuedRay.seed, queuedRay.rngIndex );

				let ray = Ray( queuedRay.origin, queuedRay.direction, queuedRay.maxDist );
				var hitResult: ${t};
				let occluded = ${r}( ray, &hitResult );
				shadowRayIntersectionsStorage[ index ].objectIndex = select( - 1, i32( hitResult.objectIndex ), occluded );
				shadowRayIntersectionsStorage[ index ].skippedOcclusion = hitResult.skippedOcclusion;
				shadowRayIntersectionsStorage[ index ].occlusion = hitResult.occlusion;

			}
		`;super(i(e)),this.defineUniformAccessors(e)}};import{StorageBufferAttribute as fu}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as du,uniform as hu,globalId as my}from"/vendor/three/0.187.0/three.tsl.js";var Ws=class extends z{constructor(){let e={maxSubsurfaceBounces:hu(256,"uint"),stepsPerPass:hu(16,"uint"),rayCount:hu(0,"uint"),rayDataStorage:du(new fu(1,1),Ce),mediumQueue:du(new fu(1,1),"uint"),rayIntersectionsStorage:du(new fu(1,1),cr),globalId:my},t=Be("bvhData.fns.raycastFirstHit.outputType"),r=Be("bvhData.fns.raycastFirstHit"),i=$`

			fn compute( maxSubsurfaceBounces: u32, stepsPerPass: u32, rayCount: u32, globalId: vec3u ) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let mediumQueue = &${e.mediumQueue};
				let rayIntersectionsStorage = &${e.rayIntersectionsStorage};

				if ( globalId.x >= mediumQueue[ ${Ei}u ] ) {

					return;

				}

				let index = mediumQueue[ ${Er}u + globalId.x ];
				// whole structs are read from the buffer, not the pointer alias, which Safari fails to compile
				let input = ${e.rayDataStorage}[ index ];
				let medium = ${ms}( input.medium );

				let indexUV = vec2u( input.pixelIndex >> 16, input.pixelIndex & 0xFFFF );
				${Xe}( indexUV, input.seed, input.currentBounce + input.alphaDepth + input.subsurfaceBounces );

				var ray = Ray( input.origin, input.direction, 0.0 );
				var weight = input.scatterColor;
				var subsurfaceBounces = input.subsurfaceBounces;
				var hasScattered = false;
				var isFinished = false;
				var isTerminated = false;

				for ( var i = 0u; i < stepsPerPass; i ++ ) {

					// only trace up to the sampled scatter event
					let channelPdf = ${xs}( input.throughputColor * weight, medium.albedo );
					let scatterDist = ${ys}( medium, channelPdf, ${Ee}( ${ts} ) );
					ray.maxDist = scatterDist;

					var hitResult: ${t};
					let didHit = ${r}( ray, &hitResult );
					let surfaceDist = select( ${et}, hitResult.dist, didHit );

					let scattered = scatterDist < surfaceDist;
					let segmentDist = select( surfaceDist, scatterDist, scattered );
					let transmittance = exp( - medium.extinction * segmentDist );
					let distancePdf = dot( channelPdf, ${vs}( medium, segmentDist, scattered ) );
					if ( distancePdf <= 0.0 ) {

						isTerminated = true;
						break;

					}

					if ( ! scattered ) {

						// reaching the surface, weighted by the chance of not scattering before it
						weight *= transmittance / distancePdf;

						// The hit fills the ray results from the back, by the walk's medium queue position, while the
						// traced rays fill them from the front. A path slot walking a medium queued no ray this round,
						// so the two ranges never exceed the slot count or overlap
						let resultIndex = rayCount - 1u - globalId.x;
						rayIntersectionsStorage[ resultIndex ].barycoord = hitResult.barycoord;
						// a walk that leaves without hitting anything is a miss, as in the trace kernel
						rayIntersectionsStorage[ resultIndex ].objectIndex = select( - 1, i32( hitResult.objectIndex ), didHit );
						rayIntersectionsStorage[ resultIndex ].position = ray.origin + ray.direction * hitResult.dist;
						rayIntersectionsStorage[ resultIndex ].dist = hitResult.dist;
						rayIntersectionsStorage[ resultIndex ].normal = hitResult.normal.xyz;
						rayIntersectionsStorage[ resultIndex ].side = hitResult.side;
						rayIntersectionsStorage[ resultIndex ].indices = hitResult.indices.xyz;
						rayDataStorage[ index ].rayIntersectionIndex = i32( resultIndex );

						isFinished = true;
						break;

					}

					weight *= medium.albedo * medium.extinction * transmittance / distancePdf;
					ray.origin += ray.direction * scatterDist;
					ray.direction = ${as}( ray.direction, medium.anisotropy, ${Ee}( ${rs} ) );
					hasScattered = true;

					// capped separately from bounces, and ends once the throughput is negligible
					subsurfaceBounces ++;
					let throughput = input.throughputColor * weight / input.scatterPdf;
					if ( subsurfaceBounces >= maxSubsurfaceBounces || max( max( throughput.r, throughput.g ), throughput.b ) < ${hs} ) {

						isTerminated = true;
						break;

					}

					${fi}();

				}

				// a zeroed pdf terminates the path in LogicKernel
				rayDataStorage[ index ].scatterColor = weight;
				rayDataStorage[ index ].scatterPdf = select( input.scatterPdf, 0.0, isTerminated );
				rayDataStorage[ index ].origin = ray.origin;
				rayDataStorage[ index ].direction = ray.direction;
				rayDataStorage[ index ].subsurfaceBounces = subsurfaceBounces;
				rayDataStorage[ index ].walkPending = select( 1u, 0u, isFinished || isTerminated );
				if ( hasScattered ) {

					rayDataStorage[ index ].mediumScattered = 1u;
					rayDataStorage[ index ].isFullyTransmissive = 0u;

				}

			}
		`;super(i(e)),this.defineUniformAccessors(e)}};import{StorageBufferAttribute as yd}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as gy,storage as vd,globalId as xy}from"/vendor/three/0.187.0/three.tsl.js";var Vs=class extends z{constructor(){let e={rayCount:gy(0,"uint"),rayDataStorage:vd(new yd(1,1),Ce).toReadOnly(),mediumQueue:vd(new yd(1,1),"uint").toAtomic(),globalId:xy},t=$`

			fn compute( rayCount: u32, globalId: vec3u ) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let mediumQueue = &${e.mediumQueue};

				let index = globalId.x;
				if ( index >= rayCount ) {

					return;

				}

				if ( rayDataStorage[ index ].walkPending != 0u && rayDataStorage[ index ].objectIndex >= 0 ) {

					let queueIndex = atomicAdd( &mediumQueue[ ${Ei}u ], 1u );
					atomicStore( &mediumQueue[ ${Er}u + queueIndex ], index );

				}

			}
		`;super(t(e)),this.defineUniformAccessors(e)}};import{IndirectStorageBufferAttribute as yy,StorageBufferAttribute as vy}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as bd}from"/vendor/three/0.187.0/three.tsl.js";var Mi=class extends z{constructor(e=Mt,t=64){let r={queueCounters:bd(new vy(1,1),"uint").toAtomic(),outputDispatch:bd(new yy(3,1),"u32").setName("outputDispatch")},i=$`
			fn compute() -> void {

				let queueCounters = &${r.queueCounters};
				let outputDispatch = &${r.outputDispatch};

				let queueLength = atomicLoad( &queueCounters[ ${e}u ] );
				outputDispatch[ 0 ] = ( queueLength + ${t-1}u ) / ${t}u;
				outputDispatch[ 1 ] = 1u;
				outputDispatch[ 2 ] = 1u;

			}
		`;super(i(r)),this.defineUniformAccessors(r)}};import{StorageBufferAttribute as wd}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as mu,storage as _d,globalId as by}from"/vendor/three/0.187.0/three.tsl.js";var Gs=class extends z{constructor(){let e={rayDataStorage:_d(new wd(1,1),Ce),pixelQueue:_d(new wd(1,1),"uint").toAtomic(),start:mu(0,"uint"),end:mu(0,"uint"),addingSlots:mu(0,"uint"),globalId:by},t=$`
			fn compute( start: u32, end: u32, addingSlots: u32, globalId: vec3u ) -> void {

				let rayDataStorage = &${e.rayDataStorage};
				let pixelQueue = &${e.pixelQueue};

				let index = start + globalId.x;
				if ( index >= end ) {

					return;

				}

				if ( addingSlots == 0u ) {

					// slots being removed hand their pixel back before the pool is discarded
					let queueIndex = atomicAdd( &pixelQueue[ ${Mr}u ], 1u );
					atomicStore( &pixelQueue[ ${qt}u + queueIndex ], rayDataStorage[ index ].pixelIndex );

				} else {

					// the pool never exceeds the pixel count, so the queue holds enough for every new slot
					let previousCount = atomicSub( &pixelQueue[ ${Mr}u ], 1u );
					rayDataStorage[ index ].pixelIndex = atomicLoad( &pixelQueue[ ${qt}u + previousCount - 1u ] );
					rayDataStorage[ index ].resultColor = vec4f( 0.0 );
					rayDataStorage[ index ].throughputColor = vec3f( 0.0 );
					rayDataStorage[ index ].objectIndex = - 1;
					rayDataStorage[ index ].alphaDepth = 0u;
					rayDataStorage[ index ].rayIntersectionIndex = - 1;
					rayDataStorage[ index ].shadowRayIntersectionIndex = - 1;

				}

			}
		`;super(t(e)),this.defineUniformAccessors(e)}};import{IndirectStorageBufferAttribute as wy}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as _y,globalId as Ty}from"/vendor/three/0.187.0/three.tsl.js";var qs=class extends z{constructor(e={}){let{type:t="u32"}=e,r={globalId:Ty,outputTarget:_y(new wy(1,1),t)},i=$`
			fn compute( globalId: vec3u ) -> void {

				${r.outputTarget}[ globalId.x ] = 0;

			}
		`;super(i(r)),this.defineUniformAccessors({target:r.outputTarget})}};import{StorageBufferAttribute as Td}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as Sy,storage as Sd,globalId as Iy}from"/vendor/three/0.187.0/three.tsl.js";var Xs=class extends z{constructor(e){let t={source:Sd(new Td(1,1),e).toReadOnly(),target:Sd(new Td(1,1),e),count:Sy(0,"uint"),globalId:Iy},r=$`
			fn compute( count: u32, globalId: vec3u ) -> void {

				let index = globalId.x;
				if ( index >= count ) {

					return;

				}

				${t.target}[ index ] = ${t.source}[ index ];

			}
		`;super(r(t)),this.defineUniformAccessors(t)}};import{StorageBufferAttribute as Id,StorageTexture as $y,StructTypeNode as Cy}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as $d,textureStore as Ay,globalId as Py}from"/vendor/three/0.187.0/three.tsl.js";var Cd=0,Ad=1,Pd=2,Bd=3,Rd=4,gu=5,Ed=4294967296,Md=new Cy({minSamples:{type:"uint",atomic:!0},maxSamples:{type:"uint",atomic:!0},totalLo:{type:"uint",atomic:!0},totalHi:{type:"uint",atomic:!0},pixelCount:{type:"uint",atomic:!0}},"SampleCounters"),Ys=class extends z{constructor(){let e={counters:$d(new Id(1,1),Md)},t=$`
			fn compute() -> void {

				atomicStore( &${e.counters}[ 0 ].minSamples, 0xFFFFFFFFu );
				atomicStore( &${e.counters}[ 0 ].maxSamples, 0u );
				atomicStore( &${e.counters}[ 0 ].totalLo, 0u );
				atomicStore( &${e.counters}[ 0 ].totalHi, 0u );
				atomicStore( &${e.counters}[ 0 ].pixelCount, 0u );

			}
		`;super(t(e)),this.defineUniformAccessors(e)}},js=class extends z{constructor(){let e={globalId:Py,counters:$d(new Id(1,1),Md),sampleCountTarget:Ay(new $y(1,1)).toReadOnly()},t=$`
			fn compute( globalId: vec3u ) -> void {

				let targetDimensions = textureDimensions( ${e.sampleCountTarget} );
				if ( globalId.x >= targetDimensions.x || globalId.y >= targetDimensions.y ) {

					return;

				}

				// pixels without a camera ray aren't part of the image, so skip rather than count zero
				let combinedField = textureLoad( ${e.sampleCountTarget}, globalId.xy ).r;
				if ( ( combinedField & ${wi}u ) == 0u ) {

					return;

				}

				let samples = combinedField & ${Gt}u;

				atomicMin( &${e.counters}[ 0 ].minSamples, samples );
				atomicMax( &${e.counters}[ 0 ].maxSamples, samples );
				atomicAdd( &${e.counters}[ 0 ].pixelCount, 1u );

				// the sum overflowed if it came out smaller than the value already there, so carry
				// the wrap into "totalHi"
				let previousTotal = atomicAdd( &${e.counters}[ 0 ].totalLo, samples );
				if ( previousTotal + samples < previousTotal ) {

					atomicAdd( &${e.counters}[ 0 ].totalHi, 1u );

				}

			}
		`;super(t(e)),this.defineUniformAccessors(e)}};var Ry=134217728,Nd=Math.floor(Ry/(Ce.getLength()*4)),Ey=5,Fn=class extends Pi{constructor(e){super(e),this.seed=0,this.mediumStepsPerPass=16,this.envInfo=new Ti,this.backgroundInfo=new Si,this.lightsInfo=new Ci,this.rayCount=0,this.rayDataStorage=null,this.rayQueue=null,this.shadowRayQueue=null,this.queueCounters=new Xt(new Uint32Array(Ns),1),this.queueCounters.name="Queue Counters",this.rayIntersectionsStorage=null,this.shadowRayIntersectionsStorage=null,this.mediumQueue=null,this.pixelQueue=null,this.sampleCountersStorage=new Xt(new Uint32Array(gu),gu),this.sampleCountersStorage.name="Sample Counters",this._samplesPromise=null,this.populatePixelIndicesKernel=new ks().setWorkgroupSize(8,8,1),this.logicKernel=new Os().setWorkgroupSize(64,1,1),this.materialKernel=new Us().setWorkgroupSize(64,1,1),this.traceRayKernel=new zs().setWorkgroupSize(64,1,1),this.traceShadowRayKernel=new Hs().setWorkgroupSize(64,1,1),this.mediumKernel=new Ws().setWorkgroupSize(64,1,1),this.queueMediumWalksKernel=new Vs().setWorkgroupSize(64,1,1),this.rayDispatchConverter=new Mi(Mt).setWorkgroupSize(1,1,1),this.shadowDispatchConverter=new Mi(Ri).setWorkgroupSize(1,1,1),this.mediumDispatchConverter=new Mi(Ei).setWorkgroupSize(1,1,1),this.primeSampleCountersKernel=new Ys().setWorkgroupSize(1,1,1),this.tallySampleCountsKernel=new js().setWorkgroupSize(8,8,1),this.resetSlotsKernel=new Gs().setWorkgroupSize(64,1,1),this.copyRayDataKernel=new Xs(Ce).setWorkgroupSize(64,1,1),this.logicKernel.envInfo=this.envInfo,this.logicKernel.backgroundInfo=this.backgroundInfo,this.logicKernel.lightsInfo=this.lightsInfo,this.zeroDispatchKernel=new qs().setWorkgroupSize(1,1,1)}resetSeed(){this.seed=0}reset(){super.reset(),this._samplesPromise=null}setBVHData(e){this.materialKernel.context.bvhData=e,this.materialKernel.needsUpdate=!0,this.traceRayKernel.context.bvhData=e,this.traceRayKernel.needsUpdate=!0,this.traceShadowRayKernel.context.bvhData=e,this.traceShadowRayKernel.needsUpdate=!0,this.mediumKernel.context.bvhData=e,this.mediumKernel.needsUpdate=!0,this.reset()}rebuild(){super.rebuild(),this.materialKernel.needsUpdate=!0,this.reset()}setRandom(e){this.logicKernel.context.random=e,this.logicKernel.needsUpdate=!0,this.materialKernel.context.random=e,this.materialKernel.needsUpdate=!0,this.traceRayKernel.context.random=e,this.traceRayKernel.needsUpdate=!0,this.traceShadowRayKernel.context.random=e,this.traceShadowRayKernel.needsUpdate=!0,this.mediumKernel.context.random=e,this.mediumKernel.needsUpdate=!0,this.reset()}setMaterial(e){this.materialKernel.material=e.getData(),this.materialKernel.needsUpdate=!0,this.reset()}setFilterGlossy(e){this.materialKernel.filterGlossy=e===0?_s:1/e,this.reset()}setClamping(e,t){this.logicKernel.clampDirect=e,this.logicKernel.clampIndirect=t,this.reset()}setTransmissiveBackground(e){this.logicKernel.transmissiveBackground=e,this.reset()}setEnvironment(e){this.envInfo.updateFrom(e)}setLights(e){this.lightsInfo.updateFrom(this.renderer,e),this.reset()}setEnvironmentParams(e,t){let{envInfo:r}=this,i=new Dd().makeRotationFromEuler(t).invert();r.rotationNode.value.setFromMatrix4(i),r.intensityNode.value=e}setMultipleImportanceSampling(e){this.logicKernel.misEnabled=e?1:0,this.reset()}setBackground(e){let{backgroundInfo:t}=this;t.dispose(),t.map=e}setBackgroundParams(e,t,r){let{backgroundInfo:i}=this,s=new Dd().makeRotationFromEuler(t).invert();i.rotationNode.value.copy(s),i.intensity=e,i.blur=r}dispose(){super.dispose(),this.envInfo.dispose(),this.lightsInfo.dispose(),this.rayDataStorage?.dispose(),this.rayQueue?.dispose(),this.shadowRayQueue?.dispose(),this.queueCounters.dispose(),this.rayIntersectionsStorage?.dispose(),this.shadowRayIntersectionsStorage?.dispose(),this.mediumQueue?.dispose(),this.sampleCountersStorage.dispose(),this.pixelQueue?.dispose(),this.rayDispatchConverter.outputDispatch.dispose(),this.shadowDispatchConverter.outputDispatch.dispose(),this.mediumDispatchConverter.outputDispatch.dispose(),this.populatePixelIndicesKernel.dispose(),this.logicKernel.dispose(),this.materialKernel.dispose(),this.traceRayKernel.dispose(),this.traceShadowRayKernel.dispose(),this.mediumKernel.dispose(),this.queueMediumWalksKernel.dispose(),this.rayDispatchConverter.dispose(),this.shadowDispatchConverter.dispose(),this.mediumDispatchConverter.dispose(),this.primeSampleCountersKernel.dispose(),this.tallySampleCountsKernel.dispose(),this.resetSlotsKernel.dispose(),this.copyRayDataKernel.dispose(),this.zeroDispatchKernel.dispose()}_updatePixelQueue(e,t){let r=qt+Math.max(e*t,1);(!this.pixelQueue||this.pixelQueue.array.length<r)&&(this.pixelQueue?.dispose(),this.pixelQueue=new Xt(new Float32Array(r),r),this.pixelQueue.name="Pixel Queue",this.materialKernel.pixelQueue=this.pixelQueue,this.populatePixelIndicesKernel.pixelQueue=this.pixelQueue,this.resetSlotsKernel.pixelQueue=this.pixelQueue)}_resizePool(e,t){let{resetSlotsKernel:r,copyRayDataKernel:i}=this,s=this.rayCount,n=Math.min(s,t),l=Math.min(e,t);r.pixelQueue=this.pixelQueue,l<n&&(r.rayDataStorage=this.rayDataStorage,r.start=l,r.end=n,r.addingSlots=!1,this.dispatch(r,r.getDispatchSize(n-l,1,1)));let a=new Xt(e,Ce.getLength());a.name="Ray Data";let p=new Xt(e,Et.getLength());p.name="Ray Queue";let u=new Xt(e,Et.getLength());u.name="Shadow Ray Queue";let c=new Xt(e,cr.getLength());c.name="Ray Intersections";let f=new Xt(e,Bi.getLength());f.name="Shadow Ray Intersections";let h=Er+e,d=new Xt(new Uint32Array(h),h);d.name="Medium Queue";let m=Math.min(e,s);m>0&&(i.source=this.rayDataStorage,i.target=a,i.count=m,this.dispatch(i,i.getDispatchSize(m,1,1))),this.rayDataStorage?.dispose(),this.rayQueue?.dispose(),this.shadowRayQueue?.dispose(),this.rayIntersectionsStorage?.dispose(),this.shadowRayIntersectionsStorage?.dispose(),this.mediumQueue?.dispose(),this.rayDataStorage=a,this.rayQueue=p,this.shadowRayQueue=u,this.rayIntersectionsStorage=c,this.shadowRayIntersectionsStorage=f,this.mediumQueue=d,this.rayCount=e,l>n&&(r.rayDataStorage=a,r.start=n,r.end=l,r.addingSlots=!0,this.dispatch(r,r.getDispatchSize(l-n,1,1)))}_getRequestedSlotCount(){return Math.min(Nd,Math.max(1,Math.floor(this.frameBudget)))}_getPreviewIterations(){return Math.max(1,Math.min(this.maxBounces,Ey))}getPreviewPixelBudget(){return Math.min(this.frameBudget/this._getPreviewIterations(),Nd)}_applyBudget(e){let t=this._getRequestedSlotCount();t!==this.rayCount&&this._resizePool(t,e)}*createRenderTask(){let{renderer:e,maxTransparentBounces:t,logicKernel:r,materialKernel:i,traceRayKernel:s,traceShadowRayKernel:n,mediumKernel:l,queueMediumWalksKernel:a,rayDispatchConverter:p,shadowDispatchConverter:u,mediumDispatchConverter:c,zeroDispatchKernel:f,populatePixelIndicesKernel:h}=this,d=new By;this.getSize(d),this._updatePixelQueue(d.x,d.y);let m=d.x*d.y;for(this._applyBudget(m),yield*this.compileKernels([r,i,s,n,l,a,p,u,c,f,h]),f.target=this.queueCounters,this.dispatch(f,[Ns]),h.rayDataStorage=this.rayDataStorage,h.pixelQueue=this.pixelQueue,h.frameBudget=Math.min(this.rayCount,m),h.targetDimensions.copy(d),this.dispatch(h,h.getDispatchSize(d.x,d.y,1)),this.seed++;;){let x=Math.min(this._getRequestedSlotCount(),m),v=this.lowResMode?this._getPreviewIterations():1,g=Math.max(v,Math.round(this.frameBudget/x));for(let y=0;y<g;y++){e.copyTextureToTexture(this.outputTarget,this.prevOutputTarget),[this.outputTarget,this.prevOutputTarget]=[this.prevOutputTarget,this.outputTarget],r.prevOutputTarget=this.prevOutputTarget,r.outputTarget=this.outputTarget,r.sampleCountTarget=this.sampleCountTarget,r.auxTarget=this.auxTarget??this._auxPlaceholder,r.accumulateAux=this.auxTarget?1:0,r.rayDataStorage=this.rayDataStorage,r.rayIntersectionsStorage=this.rayIntersectionsStorage,r.shadowRayIntersectionsStorage=this.shadowRayIntersectionsStorage,r.maxBounces=this.maxBounces,r.rayCount=Math.min(this.rayCount,m),this.dispatch(r,r.getDispatchSize(r.rayCount,1,1)),this._applyBudget(m);let{rayDataStorage:w,rayQueue:b,shadowRayQueue:T,queueCounters:_,rayIntersectionsStorage:S,shadowRayIntersectionsStorage:A,mediumQueue:I}=this,P=Math.min(this.rayCount,m);f.target=_,this.dispatch(f,[Ns]),f.target=I,this.dispatch(f,[Er]),i.rayDataStorage=w,i.rayQueue=b,i.shadowRayQueue=T,i.queueCounters=_,i.pixelQueue=this.pixelQueue,i.sampleCountTarget=this.sampleCountTarget,i.seed=this.seed,i.maxSamples=this.maxSamples,i.rayCount=P,i.maxTransparentBounces=t,i.maxBounces=this.maxBounces,i.targetDimensions.copy(d),this.dispatch(i,i.getDispatchSize(P,1,1)),p.queueCounters=_,this.dispatch(p,[1,1,1]),s.rayQueue=b,s.queueCounters=_,s.rayIntersectionsStorage=S,this.dispatch(s,p.outputDispatch),u.queueCounters=_,this.dispatch(u,[1,1,1]),n.shadowRayQueue=T,n.queueCounters=_,n.shadowRayIntersectionsStorage=A,this.dispatch(n,u.outputDispatch),a.rayDataStorage=w,a.mediumQueue=I,a.rayCount=P,this.dispatch(a,a.getDispatchSize(P,1,1)),c.queueCounters=I,this.dispatch(c,[1,1,1]),l.rayDataStorage=w,l.mediumQueue=I,l.rayIntersectionsStorage=S,l.rayCount=P,l.maxSubsurfaceBounces=this.maxSubsurfaceBounces,l.stepsPerPass=this.mediumStepsPerPass,this.dispatch(l,c.outputDispatch)}yield}}getSampleCountsAsync(){if(this._samplesPromise===null){let e=this._measureSampleCounts().finally(()=>{this._samplesPromise===e&&(this._samplesPromise=null)});this._samplesPromise=e}return this._samplesPromise}async _measureSampleCounts(){let{renderer:e,sampleCountTarget:t,sampleCountersStorage:r,primeSampleCountersKernel:i,tallySampleCountsKernel:s}=this;if(!e.initialized||this.lowResMode)return{min:0,max:0,avg:0};i.counters=r,e.compute(i.kernel,[1,1,1]),s.counters=r,s.sampleCountTarget=t,e.compute(s.kernel,s.getDispatchSize(t.width,t.height));let n=await e.getArrayBufferAsync(r),l=new Uint32Array(n),a=l[Rd];if(a===0)return{min:0,max:0,avg:0};let p=l[Bd]*Ed+l[Pd];return{min:l[Cd],max:l[Ad],avg:p/a}}};import{DataTexture as My,DataUtils as Dy,EquirectangularReflectionMapping as Ny,FloatType as Fy,HalfFloatType as ky,LinearFilter as Ly,LinearMipMapLinearFilter as Oy,RGBAFormat as Uy,RepeatWrapping as kd,ShaderMaterial as zy,WebGLRenderTarget as Hy}from"/vendor/three/0.187.0/three.webgpu.js";var Fd=`

	// TODO: possibly this should be renamed something related to material or path tracing logic

	#ifndef RAY_OFFSET
	#define RAY_OFFSET 1e-4
	#endif

	// adjust the hit point by the surface normal by a factor of some offset and the
	// maximum component-wise value of the current point to accommodate floating point
	// error as values increase.
	vec3 stepRayOrigin( vec3 rayOrigin, vec3 rayDirection, vec3 offset, float dist ) {

		vec3 point = rayOrigin + rayDirection * dist;
		vec3 absPoint = abs( point );
		float maxPoint = max( absPoint.x, max( absPoint.y, absPoint.z ) );
		return point + offset * ( maxPoint + 1.0 ) * RAY_OFFSET;

	}

	// https://github.com/KhronosGroup/glTF/blob/main/extensions/2.0/Khronos/KHR_materials_volume/README.md#attenuation
	vec3 transmissionAttenuation( float dist, vec3 attColor, float attDist ) {

		vec3 ot = - log( attColor ) / attDist;
		return exp( - ot * dist );

	}

	vec3 getHalfVector( vec3 wi, vec3 wo, float eta ) {

		// get the half vector - assuming if the light incident vector is on the other side
		// of the that it's transmissive.
		vec3 h;
		if ( wi.z > 0.0 ) {

			h = normalize( wi + wo );

		} else {

			// Scale by the ior ratio to retrieve the appropriate half vector
			// From Section 2.2 on computing the transmission half vector:
			// https://blog.selfshadow.com/publications/s2015-shading-course/burley/s2015_pbs_disney_bsdf_notes.pdf
			h = normalize( wi + wo * eta );

		}

		h *= sign( h.z );
		return h;

	}

	vec3 getHalfVector( vec3 a, vec3 b ) {

		return normalize( a + b );

	}

	// The discrepancy between interpolated surface normal and geometry normal can cause issues when a ray
	// is cast that is on the top side of the geometry normal plane but below the surface normal plane. If
	// we find a ray like that we ignore it to avoid artifacts.
	// This function returns if the direction is on the same side of both planes.
	bool isDirectionValid( vec3 direction, vec3 surfaceNormal, vec3 geometryNormal ) {

		bool aboveSurfaceNormal = dot( direction, surfaceNormal ) > 0.0;
		bool aboveGeometryNormal = dot( direction, geometryNormal ) > 0.0;
		return aboveSurfaceNormal == aboveGeometryNormal;

	}

	// ray sampling x and z are swapped to align with expected background view
	vec2 equirectDirectionToUv( vec3 direction ) {

		// from Spherical.setFromCartesianCoords
		vec2 uv = vec2( atan( direction.z, direction.x ), acos( direction.y ) );
		uv /= vec2( 2.0 * PI, PI );

		// apply adjustments to get values in range [0, 1] and y right side up
		uv.x += 0.5;
		uv.y = 1.0 - uv.y;
		return uv;

	}

	vec3 equirectUvToDirection( vec2 uv ) {

		// undo above adjustments
		uv.x -= 0.5;
		uv.y = 1.0 - uv.y;

		// from Vector3.setFromSphericalCoords
		float theta = uv.x * 2.0 * PI;
		float phi = uv.y * PI;

		float sinPhi = sin( phi );

		return vec3( sinPhi * cos( theta ), cos( phi ), sinPhi * sin( theta ) );

	}

	// power heuristic for multiple importance sampling
	float misHeuristic( float a, float b ) {

		float aa = a * a;
		float bb = b * b;
		return aa / ( aa + bb );

	}

	// tentFilter from Peter Shirley's 'Realistic Ray Tracing (2nd Edition)' book, pg. 60
	// erichlof/THREE.js-PathTracing-Renderer/
	float tentFilter( float x ) {

		return x < 0.5 ? sqrt( 2.0 * x ) - 1.0 : 1.0 - sqrt( 2.0 - ( 2.0 * x ) );

	}
`;var xu=class extends zy{constructor(){super({uniforms:{envMap:{value:null},flipEnvMap:{value:-1}},vertexShader:`
				varying vec2 vUv;
				void main() {

					vUv = uv;
					gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );

				}`,fragmentShader:`
				#define ENVMAP_TYPE_CUBE_UV

				uniform samplerCube envMap;
				uniform float flipEnvMap;
				varying vec2 vUv;

				#include <common>
				#include <cube_uv_reflection_fragment>

				${Fd}

				void main() {

					vec3 rayDirection = equirectUvToDirection( vUv );
					rayDirection.x *= flipEnvMap;
					gl_FragColor = textureCube( envMap, rayDirection );

				}`}),this.depthWrite=!1,this.depthTest=!1}},Ks=class{constructor(e){this._renderer=e,this._quad=new $t(new xu)}generate(e,t=null,r=null){if(!e.isCubeTexture)throw new Error("CubeToEquirectMaterial: Source can only be cube textures.");let i=e.images[0],s=this._renderer,n=this._quad;t===null&&(t=4*i.height),r===null&&(r=2*i.height);let l=new Hy(t,r,{type:Fy,colorSpace:i.colorSpace}),a=i.height,p=Math.log2(a)-2,u=1/a,c=1/(3*Math.max(Math.pow(2,p),112));n.material.defines.CUBEUV_MAX_MIP=`${p}.0`,n.material.defines.CUBEUV_TEXEL_WIDTH=c,n.material.defines.CUBEUV_TEXEL_HEIGHT=u,n.material.uniforms.envMap.value=e,n.material.uniforms.flipEnvMap.value=e.isRenderTargetTexture?1:-1,n.material.needsUpdate=!0;let f=s.getRenderTarget(),h=s.autoClear;s.autoClear=!0,s.setRenderTarget(l),n.render(s),s.setRenderTarget(f),s.autoClear=h;let d=new Uint16Array(t*r*4),m=new Float32Array(t*r*4);s.readRenderTargetPixels(l,0,0,t,r,m),l.dispose();for(let v=0,g=m.length;v<g;v++)d[v]=Dy.toHalfFloat(m[v]);let x=new My(d,t,r,Uy,ky);return x.minFilter=Oy,x.magFilter=Ly,x.wrapS=kd,x.wrapT=kd,x.mapping=Ny,x.needsUpdate=!0,x}dispose(){this._quad.dispose()}};import{BackSide as Vy,FrontSide as Gy,DoubleSide as qy,BufferAttribute as Xy,BufferGeometry as Yy,StorageBufferAttribute as Ud,StructTypeNode as Wd,Vector4 as jy,SkinnedMesh as Ky,RepeatWrapping as Qy,ClampToEdgeWrapping as Zy,MirroredRepeatWrapping as Jy,NearestFilter as ev}from"/vendor/three/0.187.0/three.webgpu.js";import{storage as tv,float as zd,bool as Hd,texture as rv,uniformArray as iv,uint as nv}from"/vendor/three/0.187.0/three.tsl.js";function Wy(o,e){return o.uuid<e.uuid?1:o.uuid>e.uuid?-1:0}function Ld(o){return`${o.source.uuid}:${o.colorSpace}`}function Od(o){let e=[];return o.traverseVisible(t=>{(t.isRectAreaLight||t.isSpotLight||t.isPointLight||t.isDirectionalLight||t.isSunLight)&&e.push(t)}),e.sort(Wy)}var kn=new jy,Qs=new Wd({matrixWorld:"mat4x4f",inverseMatrixWorld:"mat4x4f",visible:"uint",materialIndex:"uint",visibleToShadowRays:"uint",_alignment0:"uint",color:"vec4f"},"TransformStruct"),Zs=new Wd({indices:"vec4u",normal:"vec3f",didHit:"bool",barycoord:"vec3f",objectIndex:"uint",side:"float",dist:"float",skippedOcclusion:"float",occlusion:"float"},"PathTracerIntersectionResult"),Js=class extends Lo{constructor(e,t={}){super(e,{attributes:{position:"vec4f",normal:"vec4f",tangent:"vec4f",color:"vec4f",uv:"vec4f"},...t}),this.structs.transform=Qs,this.structs.material=Ho,this.storage.materials=null,this.materialsMap=new Map,this.materials=[],this.bvhMap=new Map,this.textureAtlas=new Ii}updateUvAttributesFromScene(){let{attributes:e}=this,t=new Set(["color"]);for(let r=0;r<8;r++){let i=r===0?"uv":"uv"+r;delete e[i],t.add(i)}this.getRootObject().traverse(r=>{if(r.geometry)for(let i in r.geometry.attributes)t.has(i)&&(e[i]="vec4f")})}updateUvSampleFunction(){let{structs:e,fns:t}=this,r=[],i=null;e.attributes.membersLayout.forEach(({name:s})=>{if(/^uv/.test(s)){let n=s==="uv"?0:Number(s.replace(/^uv/,""));r.push(`
					case ${n}u: {

						return vertexData.${s}.xy;

					}
				`),i===null&&(i=`vertexData.${s}.xy`)}}),t.getUvFromChannel=$`
			fn getUvFromChannel( vertexData: ${e.attributes}, packed: i32 ) -> vec2f {

				// the uv channel is packed into bits 23-25 of the "*Map" descriptor
				let channel = u32( ( packed >> 23 ) & 0x7 );
				switch ( channel ) {

					${r.join(`
`)}
					default: {

						return ${i??"vec2f( 0.0 )"};

					}

				}

			}
		`}updateColorSampleFn(){let{structs:e,fns:t}=this;!!e.attributes.membersLayout.find(({name:i})=>i==="color")?t.getColor=$`
				fn getColor( vertexData: ${e.attributes} ) -> vec4f {

					return vertexData.color;

				}
			`:t.getColor=$`
				fn getColor( vertexData: ${e.attributes} ) -> vec4f {

					return vec4f( 1.0 );

				}
			`}useTransparencyRaycastFn(){let{textureAtlas:e,storage:t,fns:r}=this,i=e.texture,s=iv(e.textureInfo,"uvec4").setName("bvh_textureInfo"),n=ss(s,rv(i));r.getSurfaceRecord=Uf(n,r.getUvFromChannel,r.getColor);let l=nv().toVar("bvh_materialIndex"),a=zd(1).toVar("bvh_rayScalar"),p=zd(1).toVar("bvh_baseOpacity"),u=Hd(!1).toVar("bvh_isShadowRay"),c=Hd(!1).toVar("bvh_skipShadowObject"),f={shapeStruct:D,resultStruct:Zs,boundsOrderFn:$`
				fn getBoundsOrder( ray: ${D}, splitAxis: u32, node: ${Ge} ) -> bool {

					return ray.direction[ splitAxis ] >= 0.0;

				}
			`,intersectsBoundsFn:$`
				fn rayIntersectsBounds( ray: ${D}, bounds: ${Pe}, result: ptr<function, ${Zs}> ) -> u32 {

					// early-out if our object is completely transparent, or a shadow ray is already occluded
					if ( ${p} == 0.0 || ( ${u} && result.didHit ) ) {

						return 0u;

					}

					let boundsMin = vec3( bounds.min[0], bounds.min[1], bounds.min[2] );
					let boundsMax = vec3( bounds.max[0], bounds.max[1], bounds.max[2] );

					let invDir = 1.0 / ray.direction;
					let tMinPlane = ( boundsMin - ray.origin ) * invDir;
					let tMaxPlane = ( boundsMax - ray.origin ) * invDir;

					let tMinHit = min( tMinPlane, tMaxPlane );
					let tMaxHit = max( tMinPlane, tMaxPlane );

					let t0 = max( max( tMinHit.x, tMinHit.y ), tMinHit.z );
					let t1 = min( min( tMaxHit.x, tMaxHit.y ), tMaxHit.z );

					let dist = max( t0, 0.0 );
					if ( t1 < dist ) {

						return 0u;

					} else if ( ray.maxDist > 0.0 && dist * ${a} >= ray.maxDist ) {

						return 0u;

					} else if ( result.didHit && dist * ${a} >= result.dist ) {

						return 0u;

					} else {

						return 1u;

					}

				}

			`,intersectRangeFn:$`
				fn intersectRange( ray: ${D}, offset: u32, count: u32, result: ptr<function, ${Zs}> ) -> bool {

					var didHit = false;
					for ( var ti = offset; ti < offset + count; ti = ti + 1u ) {

						let i0 = ${t.index}[ ti * 3u ];
						let i1 = ${t.index}[ ti * 3u + 1u ];
						let i2 = ${t.index}[ ti * 3u + 2u ];

						let a = ${t.attributes}[ i0 ].position.xyz;
						let b = ${t.attributes}[ i1 ].position.xyz;
						let c = ${t.attributes}[ i2 ].position.xyz;

						var triResult = ${ui}( ray, a, b, c, 0.0 );
						triResult.dist *= ${a};
						if ( triResult.didHit && ( ray.maxDist <= 0.0 || triResult.dist < ray.maxDist ) && ( ! result.didHit || triResult.dist < result.dist ) ) {

							let material = ${t.materials}[ ${l} ];

							// TODO: if material is a transmissive volume we may need to assume double-sidedness
							// shadow rays hit both sides
							if ( ! ${u} && material.side != 0 && triResult.side != material.side ) {

								continue;

							}

							var opacity = ${p};
							if ( material.transparent != 0 || material.alphaTest > 0.0 ) {

								// add support for vertex color opacity
								if ( material.vertexColors == 1 ) {

									let barycoord = triResult.barycoord;
									let a = ${r.getColor}( ${t.attributes}[ i0 ] );
									let b = ${r.getColor}( ${t.attributes}[ i1 ] );
									let c = ${r.getColor}( ${t.attributes}[ i2 ] );
									let col = barycoord.x * a + barycoord.y * b + barycoord.z * c;

									opacity *= col.a;

								}

								// account for alpha component of albedo map
								if ( material.map != - 1 ) {

									let barycoord = triResult.barycoord;
									let a = ${r.getUvFromChannel}( ${t.attributes}[ i0 ], material.map );
									let b = ${r.getUvFromChannel}( ${t.attributes}[ i1 ], material.map );
									let c = ${r.getUvFromChannel}( ${t.attributes}[ i2 ], material.map );
									let uv = barycoord.x * a + barycoord.y * b + barycoord.z * c;
									let uvPrime = material.mapTransform * vec3f( uv, 1 );

									opacity *= ${n}( uvPrime.xy, material.map, 0 ).a;

								}

								// account for green component of alpha map
								if ( material.alphaMap != - 1 ) {

									let barycoord = triResult.barycoord;
									let a = ${r.getUvFromChannel}( ${t.attributes}[ i0 ], material.alphaMap );
									let b = ${r.getUvFromChannel}( ${t.attributes}[ i1 ], material.alphaMap );
									let c = ${r.getUvFromChannel}( ${t.attributes}[ i2 ], material.alphaMap );
									let uv = barycoord.x * a + barycoord.y * b + barycoord.z * c;
									let uvPrime = material.alphaMapTransform * vec3f( uv, 1 );

									opacity *= ${n}( uvPrime.xy, material.alphaMap, 0 ).g;

								}

								// Opacity is resolved at surface processing but skip full transparent hits here.
								if ( material.transparent != 0 && opacity <= 0.0 ) {

									continue;

								}

								if ( opacity < material.alphaTest ) {

									continue;

								}

							}

							// shadow rays skip the objects not visible to them. Record the share of bsdf rays this face
							// would stop: all of them if it's opaque, its opacity if it's partially transparent
							if ( ${c} ) {

								if ( material.side == 0 || triResult.side == material.side ) {

									result.skippedOcclusion += ( 1.0 - result.skippedOcclusion ) * select( 1.0, opacity, material.transparent != 0 );

								}

								continue;

							}

							// shadow rays pass partially transparent surfaces, each blocking its opacity's share of the
							// light still getting through, just as bsdf rays pass them by chance
							if ( ${u} && material.transparent != 0 && opacity < 1.0 ) {

								result.occlusion += ( 1.0 - result.occlusion ) * opacity;
								continue;

							}

							result.didHit = true;
							result.dist = triResult.dist;
							result.normal = triResult.normal;
							result.side = triResult.side;
							result.barycoord = triResult.barycoord;
							result.indices = vec4u( i0, i1, i2, ti );

							didHit = true;

						}

					}

					return didHit;

				}
			`,transformShapeFn:$`
				fn transformRay( ray: ptr<function, ${D}>, objectIndex: u32 ) -> void {

					let toLocal = ${t.transforms}[ objectIndex ].inverseMatrixWorld;
					ray.origin = ( toLocal * vec4f( ray.origin, 1.0 ) ).xyz;
					ray.direction = ( toLocal * vec4f( ray.direction, 0.0 ) ).xyz;

					let len = length( ray.direction );
					ray.direction /= len;
					${a} = 1.0 / len;

					let object = ${t.transforms}[ objectIndex ];
					${l} = object.materialIndex;

					let material = ${t.materials}[ object.materialIndex ];
					if ( material.transparent == 1 ) {

						${p} = material.opacity * object.color.a;

					} else {

						${p} = 1.0;

					}

					${c} = ${u} && object.visibleToShadowRays == 0u;

				}
			`,transformResultFn:$`
				fn transformResult( hit: ptr<function, ${Zs}>, objectIndex: u32 ) -> void {

					let toLocal = ${t.transforms}[ objectIndex ].inverseMatrixWorld;
					hit.normal = normalize( ( transpose( toLocal ) * vec4f( hit.normal, 0.0 ) ).xyz );
					hit.objectIndex = objectIndex;

				}
			`,resetShapeFn:$`
				fn resetRayScalar( objectIndex: u32 ) -> void {

					${a} = 1.0;
					${p} = 1.0;

				}
			`};r.raycastFirstHit=this.getShapecastFn({...f,name:"raycastFirstHit",prefixFn:$`
				fn initFirstHitCull() -> void {

					${u} = false;

				}
			`}),r.raycastShadow=this.getShapecastFn({...f,name:"raycastShadow",prefixFn:$`
				fn initShadowCull() -> void {

					${u} = true;

				}
			`})}update(){let{structs:e}=this,t=new Ud(new Uint8Array,e.material.getLength());this.storage.materials=tv(t,e.material).toReadOnly().setName("bvh_materials"),this.updateUvAttributesFromScene(),this.updateMaterialsMap(),super.update(),this.updateUvSampleFunction(),this.updateColorSampleFn(),this.updateMaterials(),this.bvhMap.clear(),this.useTransparencyRaycastFn()}updateMaterialsMap(){let{materials:e,materialsMap:t}=this;t.clear(),e.length=0,this.getRootObject().traverse(i=>{i.material&&(Array.isArray(i.material)?i.material.forEach(s=>s&&r(s)):r(i.material))}),e.sort((i,s)=>i.uuid<s.uuid?1:-1).forEach((i,s)=>{t.set(i,s)});function r(i){t.has(i)||(e.push(i),t.set(i,-1))}}updateMaterials(){this.updateMaterialsMap();let{materials:e,storage:t,structs:r,bvh:i}=this,{materialData:s,textures:n}=this.writeMaterialsBuffer(e),l=t.materials.proxyNode,a=t.transforms.proxyNode,p=s.length/r.material.getLength();l.value.count<p&&(l.value.dispose(),l.value=new Ud(s,r.material.getLength())),l.value.array.set(s),l.value.needsUpdate=!0,this._getTransformMap(i).forEach(u=>{this.writeMaterialData(u,u.slot,a.value.array.buffer)}),a.value.needsUpdate=!0,this.textures=n}writeMaterialsBuffer(e){function t(f){switch(f){case Qy:return 0;case Zy:return 1;case Jy:return 2;default:return 0}}function r(f,h){if(h in f&&f[h]){let d=f[h],m=Ld(d);l.has(m)||(l.set(m,l.size),a.push(d));let x=l.get(m),v=d.channel&7,g=t(d.wrapS),y=t(d.wrapT);return(d.magFilter===ev?1:0)<<30|y<<28|g<<26|v<<23|x&8388607}else return-1}function i(f,h,d){return h in f?f[h]:d}function s(f,h,d,m){let x=f[h]&&f[h].isTexture?f[h]:null;if(x){x.matrixAutoUpdate&&x.updateMatrix();let v=x.matrix.elements;for(let g=0;g<3;g++)d[m+4*g+0]=v[3*g+0],d[m+4*g+1]=v[3*g+1],d[m+4*g+2]=v[3*g+2],d[m+4*g+3]=0}return 12}let n=0,l=new Map,a=[],p=Math.max(e.length,2),u=new Float32Array(p*this.structs.material.getLength()),c=new Int32Array(u.buffer);for(let f=0,h=e.length;f<h;f++){let d=e[f];u[n++]=d.color.r,u[n++]=d.color.g,u[n++]=d.color.b,c[n++]=r(d,"map"),u[n++]=i(d,"metalness",0),c[n++]=r(d,"metalnessMap"),u[n++]=i(d,"roughness",0),c[n++]=r(d,"roughnessMap"),u[n++]=i(d,"ior",1.5),u[n++]=i(d,"transmission",0),c[n++]=r(d,"transmissionMap"),u[n++]=i(d,"emissiveIntensity",0),"emissive"in d?(u[n++]=d.emissive.r,u[n++]=d.emissive.g,u[n++]=d.emissive.b):(u[n++]=0,u[n++]=0,u[n++]=0),c[n++]=r(d,"emissiveMap"),c[n++]=r(d,"normalMap"),n++,"normalScale"in d?(u[n++]=d.normalScale.x,u[n++]=d.normalScale.y):(u[n++]=1,u[n++]=1),u[n++]=i(d,"clearcoat",0),c[n++]=r(d,"clearcoatMap"),c[n++]=r(d,"clearcoatNormalMap"),n++,"clearcoatNormalScale"in d?(u[n++]=d.clearcoatNormalScale.x,u[n++]=d.clearcoatNormalScale.y):(u[n++]=1,u[n++]=1),u[n++]=i(d,"clearcoatRoughness",0),c[n++]=r(d,"clearcoatRoughnessMap"),c[n++]=r(d,"iridescenceMap"),c[n++]=r(d,"iridescenceThicknessMap"),u[n++]=i(d,"iridescence",0),u[n++]=i(d,"iridescenceIOR",1.3),u[n++]=i(d,"iridescenceThicknessMinimum",100),u[n++]=i(d,"iridescenceThicknessMaximum",400),u[n++]=i(d,"diffuseRoughness",0),u[n++]=i(d,"dispersion",0),"specularColor"in d?(u[n++]=d.specularColor.r,u[n++]=d.specularColor.g,u[n++]=d.specularColor.b):(u[n++]=1,u[n++]=1,u[n++]=1),c[n++]=r(d,"specularColorMap"),u[n++]=i(d,"specularIntensity",1),c[n++]=r(d,"specularIntensityMap");let m=i(d,"thickness",0)===0&&i(d,"attenuationDistance",1/0)===1/0;if(c[n++]=Number(m),n++,"attenuationColor"in d?(u[n++]=d.attenuationColor.r,u[n++]=d.attenuationColor.g,u[n++]=d.attenuationColor.b):(u[n++]=1,u[n++]=1,u[n++]=1),u[n++]=i(d,"attenuationDistance",1/0),c[n++]=r(d,"alphaMap"),c[n++]=Number(i(d,"castShadow",!0)),u[n++]=d.opacity,u[n++]=d.alphaTest,!m&&d.transmission>0)u[n++]=0;else switch(d.side){case Gy:u[n++]=1;break;case Vy:u[n++]=-1;break;case qy:u[n++]=0;break}c[n++]=Number(i(d,"matte",!1)),u[n++]=i(d,"sheen",0),n++,"sheenColor"in d?(u[n++]=d.sheenColor.r,u[n++]=d.sheenColor.g,u[n++]=d.sheenColor.b):(u[n++]=0,u[n++]=0,u[n++]=0),c[n++]=r(d,"sheenColorMap"),u[n++]=i(d,"sheenRoughness",0),c[n++]=r(d,"sheenRoughnessMap"),c[n++]=Number(d.vertexColors),c[n++]=Number(d.flatShading),c[n++]=Number(d.transparent),c[n++]=0,u[n++]=i(d,"anisotropy",0),u[n++]=i(d,"anisotropyRotation",0),c[n++]=r(d,"anisotropyMap"),n++,n++,n++,n+=s(d,"map",u,n),n+=s(d,"metalnessMap",u,n),n+=s(d,"roughnessMap",u,n),n+=s(d,"transmissionMap",u,n),n+=s(d,"emissiveMap",u,n),n+=s(d,"normalMap",u,n),n+=s(d,"clearcoatMap",u,n),n+=s(d,"clearcoatNormalMap",u,n),n+=s(d,"clearcoatRoughnessMap",u,n),n+=s(d,"sheenColorMap",u,n),n+=s(d,"sheenRoughnessMap",u,n),n+=s(d,"iridescenceMap",u,n),n+=s(d,"iridescenceThicknessMap",u,n),n+=s(d,"specularColorMap",u,n),n+=s(d,"specularIntensityMap",u,n),n+=s(d,"alphaMap",u,n),n+=s(d,"anisotropyMap",u,n);let x=i(d,"multiscatterColor",null);u[n++]=x?x.r:1,u[n++]=x?x.g:1,u[n++]=x?x.b:1,u[n++]=i(d,"scatterStrength",0),u[n++]=i(d,"scatterAnisotropy",0),n+=3}return{materialData:c,textures:a}}writeMaterialData(e,t,r){let{materialsMap:i}=this,{object:s,instanceId:n,root:l}=e,a=s.material;if(Array.isArray(a)){let{materialIndex:f}=s.geometry.groups[l];a=a[f]}let p=new Uint32Array(r),u=i.has(a)?i.get(a):-1;u===-1&&(p[t*Qs.getLength()+32]=0),p[t*Qs.getLength()+33]=u,s.isInstancedMesh&&s.instanceColor?(s.getColorAt(n,kn),kn.w=1):s.isBatchedMesh&&s._colorsTexture?s.getColorAt(n,kn):kn.setScalar(1);let c=new Float32Array(r);kn.toArray(c,t*Qs.getLength()+36)}writeTransformData(e,t,r,i){super.writeTransformData(e,t,r,i),this.writeMaterialData(e,r,i);let s=new Uint32Array(i);s[r*this.structs.transform.getLength()+34]=e.object.visibleToShadowRays===!1?0:1}getBVH(e,t,r){let{bvhMap:i}=this,s=super.getBVH(e,t,r);if(i.has(s)){let n=i.get(s);if(Object.assign(r,n.range),s!==n.bvh&&s instanceof St){let l=s.mesh,a=n.bvh.mesh;a.matrixWorld.copy(l.matrixWorld).decompose(a.position,a.quaternion,a.scale),s.refit(),s.getBoundingBox(a.boundingBox),s.geometry.computeBoundingBox()}return n.bvh}else if(s.indirect){let n=new Yy;n.attributes=s.geometry.attributes;let l;if(s.geometry.index)l=s.geometry.index.array.slice(r.start,r.count+r.start);else{let{start:p,count:u}=r;l=new Uint32Array(u);for(let c=0,f=r.count;c<f;c++)l[c]=p+c}n.index=new Xy(l,1),r.start=0;let a;if(s instanceof St){let p=s.mesh,u=new Ky(n);u.copy(p),u.matrixWorld.copy(p.matrixWorld).decompose(u.position,u.quaternion,u.scale),a=new St(u,{strategy:2,targetLeafSize:5})}else a=new Ot(n,{strategy:2,targetLeafSize:5});return i.set(s,{bvh:a,range:{...r}}),a}else return s}dispose(){let{storage:e}=this;for(let t in e)e[t].value?.dispose()}};import{MeshBasicNodeMaterial as ov,DataTexture as sv,NoBlending as av}from"/vendor/three/0.187.0/three.webgpu.js";import{uv as lv,varying as uv,texture as cv,uniform as pv,wgslFn as fv}from"/vendor/three/0.187.0/three.tsl.js";var ea=class extends ov{set texture(e){this._texNode.value=e}set layer(e){this._layerUniform.value=e}constructor(){super(),this.blending=av,this._texNode=cv(new sv),this._layerUniform=pv(0),this.colorNode=fv(`
			fn atlasDebug( tex: texture_2d_array<f32>, coord: vec2f, layer: f32 ) -> vec4f {

				// flip y so texel origin shows at the top-left
				let dims = vec2f( textureDimensions( tex, 0 ).xy );
				let texel = vec2i( vec2f( coord.x, 1.0 - coord.y ) * dims );
				return textureLoad( tex, texel, i32( layer ), 0 );

			}
		`)(this._texNode,uv(lv()),this._layerUniform)}};import{MeshBasicNodeMaterial as dv,NoBlending as hv,StorageTexture as mv}from"/vendor/three/0.187.0/three.webgpu.js";import{uv as gv,varying as xv,texture as yv,uniform as Vd}from"/vendor/three/0.187.0/three.tsl.js";var ta=class extends dv{set texture(e){this._texNode.value=e}set minCount(e){this._minUniform.value=e}set maxCount(e){this._maxUniform.value=e}constructor(){super(),this.blending=hv,this._texNode=yv(new mv(1,1)),this._minUniform=Vd(0),this._maxUniform=Vd(1);let e=$`
			fn sampleDensity( tex: texture_2d<u32>, coord: vec2f, minCount: f32, maxCount: f32 ) -> vec4f {

				let dims = vec2f( textureDimensions( tex, 0 ) );
				let texel = vec2i( coord * dims );

				// the high bits hold the ray state flags rather than part of the count
				let samples = textureLoad( tex, texel, 0 ).r & ${Gt}u;

				// stretched across the recorded range rather than from zero, otherwise a converged
				// image with counts bunched near the max reads as uniformly hot
				let range = max( maxCount - minCount, 1.0 );
				let t = clamp( ( f32( samples ) - minCount ) / range, 0.0, 1.0 );

				return vec4f( ${El}( t ), 1.0 );

			}
		`;this.colorNode=e(this._texNode,xv(gv()),this._minUniform,this._maxUniform)}};import{MeshBasicNodeMaterial as vv,NoBlending as bv}from"/vendor/three/0.187.0/three.webgpu.js";import{uv as wv,varying as _v,texture as Tv,uniform as Sv}from"/vendor/three/0.187.0/three.tsl.js";var ra=class extends vv{set texture(e){this._texNode.value=e}set showNormal(e){this._showNormalUniform.value=e?1:0}constructor(){super(),this.blending=bv,this._texNode=Tv(ct()),this._showNormalUniform=Sv(0,"uint");let e=$`
			fn auxDebug( tex: texture_2d_array<u32>, coord: vec2f, showNormal: u32 ) -> vec4f {

				// the rows are stored top down, so the quad's bottom up uv is flipped
				let texel = vec2u( vec2f( coord.x, 1.0 - coord.y ) * vec2f( textureDimensions( tex, 0 ) ) );
				var packed: vec3u;
				for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

					packed[ layer ] = textureLoad( tex, texel, layer, 0 ).r;

				}

				let albedo = ${$n}( packed );
				let normal = ${Cn}( packed );
				return vec4f( select( albedo, normal, showNormal == 1u ), 1.0 );

			}
		`;this.colorNode=e(this._texNode,_v(wv()),this._showNormalUniform)}};import{BufferAttribute as ia}from"/vendor/three/0.187.0/three.webgpu.js";function Gd(o,e){if(!o.index){let t=o.attributes.position.count,r=new Array(t);for(let i=0;i<t;i++)r[i]=i;o.setIndex(r)}if(!o.attributes.normal&&e&&e.includes("normal")&&o.computeVertexNormals(),!o.attributes.uv&&e&&e.includes("uv")){let t=o.attributes.position.count;o.setAttribute("uv",new ia(new Float32Array(t*2),2,!1))}if(!o.attributes.uv2&&e&&e.includes("uv2")){let t=o.attributes.position.count;o.setAttribute("uv2",new ia(new Float32Array(t*2),2,!1))}if(!o.attributes.tangent&&e&&e.includes("tangent"))if(o.attributes.uv&&o.attributes.normal)o.computeTangents();else{let t=o.attributes.position.count;o.setAttribute("tangent",new ia(new Float32Array(t*4),4,!1))}if(!o.attributes.color&&e&&e.includes("color")){let t=o.attributes.position.count,r=new Float32Array(t*4);r.fill(1),o.setAttribute("color",new ia(r,4))}}import{float as ah}from"/vendor/three/0.187.0/three.tsl.js";var na=class{init(){}getBsdfNode(){return $`

			fn bsdfSample( worldWo: vec3f, surf: SurfaceRecord ) -> ScatterRecord {

				var record: ScatterRecord;

				let wo = normalize( surf.normalInvBasis * worldWo );
				let wi = ${Cf}( wo, ${Ee}( ${Qo} ) );
				record.color = surf.color * max( wi.z, 0.0 );
				record.pdf = max( wi.z, 0.0 ) / PI;
				record.direction = normalize( surf.normalBasis * wi );

				return record;

			}

		`}getBsdfEvalPdfNode(){return $`

			fn bsdfEvalPdf( worldWo: vec3f, worldWi: vec3f, surf: SurfaceRecord ) -> ScatterRecord {

				var record: ScatterRecord;
				record.direction = worldWi;

				let wo = normalize( surf.normalInvBasis * worldWo );
				let wi = normalize( surf.normalInvBasis * worldWi );
				record.color = surf.color * max( wi.z, 0.0 );
				record.pdf = max( wi.z, 0.0 ) / PI;

				return record;

			}

		`}getData(){return{bsdfSample:this.getBsdfNode(),bsdfEvalPdf:this.getBsdfEvalPdfNode()}}};import{wgslFn as tt}from"/vendor/three/0.187.0/three.tsl.js";var Iv=tt(`

	fn eonDirectionalAlbedo( mu: f32, roughness: f32, A: f32 ) -> f32 {

		let muComp = 1.0 - mu;
		let gOverPi = muComp * (
			0.0571085289 + muComp * (
				0.491881867 + muComp * (
					- 0.332181442 + muComp * 0.0714429953
				)
			)
		);
		return A * ( 1.0 + roughness * gOverPi );

	}

`),qd=tt(`

	fn fonSingleScatter( NdotV: f32, NdotL: f32, VdotH: f32, roughness: f32, A: f32, rho: vec3f ) -> vec3f {

		let VdotL = 2.0 * VdotH * VdotH - 1.0;
		let s = VdotL - NdotV * NdotL;
		let sOverT = select( s, s / max( NdotV, NdotL ), s > 0.0 );

		return ( rho / PI ) * A * ( 1.0 + roughness * sOverT );

	}

`,[q]),eA=tt(`

	fn fonBrdf( NdotV: f32, NdotL: f32, VdotH: f32, surf: SurfaceRecord ) -> vec3f {

		let roughness = surf.diffuseRoughness;
		if ( roughness < 1e-5 ) {

			return surf.color / PI;

		}

		let rho = saturate( surf.color );
		let A = 1.0 / ( 1.0 + ( 0.5 - 2.0 / ( 3.0 * PI ) ) * roughness );

		return fonSingleScatter( NdotV, NdotL, VdotH, roughness, A, rho );

	}

`,[q,_e,qd]),Xd=tt(`

	fn eonBrdf( NdotV: f32, NdotL: f32, VdotH: f32, surf: SurfaceRecord ) -> vec3f {

		let roughness = surf.diffuseRoughness;
		if ( roughness < 1e-5 ) {

			return surf.color / PI;

		}

		let rho = saturate( surf.color );
		let A = 1.0 / ( 1.0 + ( 0.5 - 2.0 / ( 3.0 * PI ) ) * roughness );
		let singleScatter = fonSingleScatter( NdotV, NdotL, VdotH, roughness, A, rho );

		let averageDirectionalAlbedo = A * ( 1.0 + ( 2.0 / 3.0 - 28.0 / ( 15.0 * PI ) ) * roughness );
		let directionalAlbedoV = eonDirectionalAlbedo( NdotV, roughness, A );
		let directionalAlbedoL = eonDirectionalAlbedo( NdotL, roughness, A );

		let rhoMultiScatter = rho * rho * averageDirectionalAlbedo /
			max( vec3f( 1e-7 ), vec3f( 1.0 ) - rho * ( 1.0 - averageDirectionalAlbedo ) );
		let multiScatter = ( rhoMultiScatter / PI ) *
			max( 1e-7, 1.0 - directionalAlbedoV ) *
			max( 1e-7, 1.0 - directionalAlbedoL ) /
			max( 1e-7, 1.0 - averageDirectionalAlbedo );

		return singleScatter + multiScatter;

	}

`,[q,_e,Iv,qd]),Yd=tt(`

	fn eonLtcCoeffs( mu: f32, roughness: f32 ) -> vec4f {

		let a = 1.0 + roughness * (
			0.303392 + ( - 0.518982 + 0.111709 * mu ) * mu +
			( - 0.276266 + 0.335918 * mu ) * roughness
		);
		let b = roughness * (
			- 1.16407 + 1.15859 * mu + ( 0.150815 - 0.150105 * mu ) * roughness
		) / ( mu * mu * mu - 1.43545 );
		let c = 1.0 + roughness * ( 0.20013 + ( - 0.506373 + 0.261777 * mu ) * mu );
		let d = roughness * (
			0.540852 + ( - 1.01625 + 0.475392 * mu ) * mu
		) / ( - 1.0743 + ( 0.0725628 + mu ) * mu );

		return vec4f( a, b, c, d );

	}

`),jd=tt(`

	fn eonLtcBasis( wo: vec3f ) -> mat3x3f {

		let lenSqr = dot( wo.xy, wo.xy );
		let invLen = inverseSqrt( lenSqr );
		let x = select(
			vec3f( 1.0, 0.0, 0.0 ),
			vec3f( wo.x, wo.y, 0.0 ) * invLen,
			lenSqr > 0.0,
		);
		let y = vec3f( - x.y, x.x, 0.0 );

		return mat3x3f( x, y, vec3f( 0.0, 0.0, 1.0 ) );

	}

`),$v=tt(`

	fn eonCltcPdf( wo: vec3f, wi: vec3f, roughness: f32 ) -> f32 {

		let toLtc = transpose( eonLtcBasis( wo ) );
		let wiLtc = toLtc * wi;
		let coeffs = eonLtcCoeffs( saturate( wo.z ), roughness );
		let a = coeffs.x;
		let b = coeffs.y;
		let c = coeffs.z;
		let d = coeffs.w;
		let detM = c * ( a - b * d );
		let wh = vec3f(
			c * ( wiLtc.x - b * wiLtc.z ),
			( a - b * d ) * wiLtc.y,
			- c * ( d * wiLtc.x - a * wiLtc.z ),
		);
		let lenSqr = max( dot( wh, wh ), 1e-7 );
		let vz = inverseSqrt( d * d + 1.0 );
		let s = 0.5 * ( 1.0 + vz );
		let jacobian = detM * detM / ( lenSqr * lenSqr );

		return jacobian * max( wh.z, 0.0 ) / ( PI * s );

	}

`,[q,Yd,jd]),Cv=tt(`

	fn eonCltcDirection( wo: vec3f, roughness: f32, uv: vec2f ) -> vec3f {

		let coeffs = eonLtcCoeffs( saturate( wo.z ), roughness );
		let a = coeffs.x;
		let b = coeffs.y;
		let c = coeffs.z;
		let d = coeffs.w;
		let radius = sqrt( uv.x );
		let phi = 2.0 * PI * uv.y;
		let y = radius * sin( phi );
		let vz = inverseSqrt( d * d + 1.0 );
		let s = 0.5 * ( 1.0 + vz );
		let x = - mix( sqrt( max( 1.0 - y * y, 0.0 ) ), radius * cos( phi ), s );
		let wh = vec3f( x, y, sqrt( max( 1.0 - x * x - y * y, 0.0 ) ) );
		let wiUnnormalized = vec3f(
			a * wh.x + b * wh.z,
			c * wh.y,
			d * wh.x + wh.z,
		);
		let wi = normalize( eonLtcBasis( wo ) * wiUnnormalized );

		return wi;

	}

`,[q,Yd,jd]),Av=tt(`

	fn eonUniformHemisphereSample( uv: vec2f ) -> vec3f {

		let sinTheta = sqrt( max( 1.0 - uv.x * uv.x, 0.0 ) );
		let phi = 2.0 * PI * uv.y;
		return vec3f( sinTheta * cos( phi ), sinTheta * sin( phi ), uv.x );

	}

`,[q]),Kd=tt(`

	fn eonUniformProbability( wo: vec3f, roughness: f32 ) -> f32 {

		let mu = saturate( wo.z );
		return clamp( pow( roughness, 0.1 ), 0.0, 1.0 ) * (
			0.162925 + ( - 0.372058 + ( 0.538233 - 0.290822 * mu ) * mu ) * mu
		);

	}

`),Qd=tt(`

	fn eonDirection( wo: vec3f, roughness: f32, uv: vec2f ) -> vec3f {

		let uniformProbability = eonUniformProbability( wo, roughness );
		if ( uv.x <= uniformProbability && uniformProbability > 0.0 ) {

			return eonUniformHemisphereSample( vec2f( uv.x / uniformProbability, uv.y ) );

		}

		let cltcProbability = 1.0 - uniformProbability;
		let cltcUv = vec2f(
			( uv.x - uniformProbability ) / max( cltcProbability, 1e-7 ),
			uv.y,
		);
		return eonCltcDirection( wo, roughness, cltcUv );

	}

`,[Cv,Av,Kd]),Zd=tt(`

	fn eonPdf( wo: vec3f, wi: vec3f, roughness: f32 ) -> f32 {

		// The EON proposal is defined over the positive hemisphere.
		if ( wo.z <= 0.0 || wi.z <= 0.0 ) {

			return 0.0;

		}

		let uniformProbability = eonUniformProbability( wo, roughness );
		let cltcProbability = 1.0 - uniformProbability;
		let uniformPdf = 1.0 / ( 2.0 * PI );

		return uniformProbability * uniformPdf +
			cltcProbability * eonCltcPdf( wo, wi, roughness );

	}

`,[q,$v,Kd]);var Pv=$`

	fn velvetD( cosThetaH: f32, roughness: f32 ) -> f32 {

		var alpha = max( roughness, 0.07 );
		alpha = alpha * alpha;

		let invAlpha = 1.0 / alpha;
		let sqrCosThetaH = cosThetaH * cosThetaH;
		let sinThetaH = max( 1.0 - sqrCosThetaH, 0.001 );

		return ( 2.0 + invAlpha ) * pow( sinThetaH, 0.5 * invAlpha ) / ( 2.0 * PI );

	}

`,Ln=$`

	fn velvetParamsInterpolate( i: i32, oneMinusAlphaSquared: f32 ) -> f32 {

		let p0 = array<f32, 5>( 25.3245, 3.32435, 0.16801, - 1.27393, - 4.85967 );
		let p1 = array<f32, 5>( 21.5473, 3.82987, 0.19823, - 1.97760, - 4.32054 );

		return mix( p1[ i ], p0[ i ], oneMinusAlphaSquared );

	}

`,yu=$`

	fn velvetL( x: f32, alpha: f32 ) -> f32 {

		let oneMinusAlpha = 1.0 - alpha;
		let oneMinusAlphaSquared = oneMinusAlpha * oneMinusAlpha;

		let a = ${Ln}( 0, oneMinusAlphaSquared );
		let b = ${Ln}( 1, oneMinusAlphaSquared );
		let c = ${Ln}( 2, oneMinusAlphaSquared );
		let d = ${Ln}( 3, oneMinusAlphaSquared );
		let e = ${Ln}( 4, oneMinusAlphaSquared );

		return a / ( 1.0 + b * pow( abs( x ), c ) ) + d * x + e;

	}

`,Jd=$`

	fn velvetLambda( cosTheta: f32, alpha: f32 ) -> f32 {

		if ( abs( cosTheta ) < 0.5 ) {

			return exp( ${yu}( cosTheta, alpha ) );

		} else {

			return exp( 2.0 * ${yu}( 0.5, alpha ) - ${yu}( 1.0 - cosTheta, alpha ) );

		}

	}

`,Bv=$`

	fn velvetG( cosThetaO: f32, cosThetaI: f32, roughness: f32 ) -> f32 {

		var alpha = max( roughness, 0.07 );
		alpha = alpha * alpha;

		return 1.0 / ( 1.0 + ${Jd}( cosThetaO, alpha ) + ${Jd}( cosThetaI, alpha ) );

	}

`,eh=$`

	fn directionalAlbedoSheen( cosThetaIn: f32, alpha: f32 ) -> f32 {

		let cosTheta = saturate( cosThetaIn );
		let c = 1.0 - cosTheta;
		let c3 = c * c * c;

		return 0.65584461 * c3 + 1.0 / ( 4.16526551 + exp( -7.97291361 * sqrt( alpha ) + 6.33516894 ) );

	}

`,th=$`

	fn sheenAlbedoScaling( wo: vec3f, wi: vec3f, surf: ${_e} ) -> f32 {

		var alpha = max( surf.sheenRoughness, 0.07 );
		alpha = alpha * alpha;

		let maxSheenColor = max( max( surf.sheenColor.r, surf.sheenColor.g ), surf.sheenColor.b );

		let eWo = ${eh}( clamp( wo.z, 0.001, 1.0 ), alpha );
		let eWi = ${eh}( clamp( wi.z, 0.001, 1.0 ), alpha );

		return min( 1.0 - maxSheenColor * eWo, 1.0 - maxSheenColor * eWi );

	}

`,rh=$`

	fn sheenColor( wo: vec3f, wi: vec3f, wh: vec3f, surf: ${_e} ) -> vec3f {

		let cosThetaO = clamp( wo.z, 0.001, 1.0 );
		let cosThetaI = clamp( wi.z, 0.001, 1.0 );
		let cosThetaH = wh.z;

		let D = ${Pv}( cosThetaH, surf.sheenRoughness );
		let G = ${Bv}( cosThetaO, cosThetaI, surf.sheenRoughness );

		// See equation (1) in http://www.aconty.com/pdf/s2017_pbs_imageworks_sheen.pdf
		var color = surf.sheenColor;
		color *= D * G / ( 4.0 * abs( cosThetaO * cosThetaI ) );

		return color;

	}

`;import{Storage3DTexture as Rv,RGBAFormat as Ev,LinearFilter as ih,HalfFloatType as Mv,MathUtils as nh}from"/vendor/three/0.187.0/three.webgpu.js";import{storageTexture3D as Dv,globalId as Nv,uniform as oa,texture3D as Fv,sampler as kv}from"/vendor/three/0.187.0/three.tsl.js";var sa=32,aa=16,la=class extends Rv{constructor(){super(sa,sa,31),this.type=Mv,this.format=Ev,this.minFilter=ih,this.magFilter=ih,this.dielectricLayerCount=10,this.transmissiveLayerCount=10,this.minIoR=1,this.maxIoR=2.5,this.sampleConductorFn=pe("_sampleConductorFn",this),this.sampleDielectricFn=pe("_sampleDielectricFn",this),this.sampleTransmissiveFn=pe("_sampleTransmissiveFn",this)}generate(e){let{dielectricLayerCount:t,transmissiveLayerCount:r,minIoR:i,maxIoR:s}=this,n={outputTarget:Dv(this).toWriteOnly(),globalId:Nv,eta:oa(1),layer:oa(0,"uint"),includeFresnel:oa(0),includeRefraction:oa(0)},l=[sa/aa,sa/aa,1],a=new z(Gf(n.outputTarget)(n),{workgroupSize:[aa,aa,1]});n.layer.value=0,n.eta.value=1,n.includeFresnel.value=0,n.includeRefraction.value=0,e.compute(a.kernel,l);for(let m=0;m<t;m++)n.layer.value++,n.eta.value=1/nh.mapLinear(m,0,t-1,i,s),n.includeFresnel.value=1,e.compute(a.kernel,l);n.includeFresnel.value=1,n.includeRefraction.value=1;for(let m=0;m<r;m++){let x=nh.mapLinear(m,0,r-1,i,s);n.layer.value=1+t+m,n.eta.value=1/x,e.compute(a.kernel,l),n.layer.value=1+t+r+m,n.eta.value=x,e.compute(a.kernel,l)}a.dispose();let p=$`
			fn mapLinearClamped( v: f32, a0: f32, a1: f32, b0: f32, b1: f32 ) -> f32 {

				let mapped = saturate( ( v - a0 ) / ( a1 - a0 ) );
				return mix( b0, b1, mapped );

			}
		`,u=Fv(this).setName("turquinTexture"),c=kv(this).setName("turquinSampler"),f=1/(1+t+2*r);this._sampleConductorFn=$`
			fn sampleConductor( cosTheta0: f32, roughness: f32 ) -> f32 {

				let layer = 0.5;
				let uvw = vec3f( cosTheta0, roughness, layer * ${f} );
				return textureSampleLevel( ${u}, ${c}, uvw, 0 ).r;

			}
		`,this._sampleDielectricFn=$`
			fn sampleDielectric( cosTheta0: f32, roughness: f32, ior: f32 ) -> f32 {

				let blockStart = 1.0;
				let layer = ${p}(
					ior,
					f32( ${i} ), f32( ${s} ),
					0.5, f32( ${t} ) - 0.5,
				) + blockStart;

				let uvw = vec3f( cosTheta0, roughness, layer * ${f} );
				return textureSampleLevel( ${u}, ${c}, uvw, 0 ).r;

			}
		`;let h=1+t,d=1+t+r;this._sampleTransmissiveFn=$`
			fn sampleTransmissive( cosTheta0: f32, roughness: f32, ior: f32, entering: bool ) -> f32 {

				let blockStart = select(
					f32( ${d} ),
					f32( ${h} ),
					entering
				);
				let layer = ${p}(
					ior,
					f32( ${i} ), f32( ${s} ),
					0.5, f32( ${r} ) - 0.5,
				) + blockStart;

				let uvw = vec3f( cosTheta0, roughness, layer * ${f} );
				return textureSampleLevel( ${u}, ${c}, uvw, 0 ).r;

			}
		`}};var oh=ah(1.5),sh=ah(1e6),ua=class extends na{constructor(e={}){super();let{specularBrdf:t=jl,specularBtdf:r=Kl,diffuseBrdf:i=Xd,fresnelMix:s=zf,conductorFresnel:n=Wf,fresnelCoat:l=Vf,iridescentFresnel:a=Ql}=e;this.turquinTexture=new la,this.specularBrdf=t,this.specularBtdf=r,this.diffuseBrdf=i,this.fresnelMix=s,this.conductorFresnel=n,this.fresnelCoat=l,this.iridescentFresnel=a,this.transmissionFresnel=Hf(a)}init(e){this.turquinTexture.generate(e)}getBsdfNode(){this._bsdfEvalFunc=$`

			// The material is organized as one scoped block per lobe in cascade order - clearcoat,
			// sheen, transmission, specular, diffuse - each accumulating into a shared result and
			// guarding its own hemisphere.
			// TODO: energy only cascades downward through front faces, so the layers are always traversed
			// outside-in and cannot reflect energy back into the glass from below.
			fn bsdfEval( ctx: ${Nl}, surf: ${_e} ) -> vec3f {

				let NdotV = ctx.V.z;
				let NdotL = ctx.L.z;

				// Each lobe contributes into "result" within its own scope, evaluated in cascade.
				// "attenuation" carries the fraction of energy each layer passes through to
				// the layers beneath it. Every lobe guards its own hemisphere.
				var result = vec3f( 0.0 );
				var attenuation = vec3f( 1.0 );

				// clearcoat
				if ( NdotL > 0.0 && surf.clearcoat > 0.0 ) {

					// the clearcoat evaluates in its own frame
					let toClearcoatMat = surf.clearcoatInvBasis * surf.normalBasis;
					let Vc = normalize( toClearcoatMat * ctx.V );
					let Lc = normalize( toClearcoatMat * ctx.L );
					let Hc = normalize( toClearcoatMat * ctx.H );
					let NdotVc = Vc.z;

					let clearcoatAlpha = surf.clearcoatRoughness * surf.clearcoatRoughness;

					// reuse the same pattern for energy conservation used in the dielectric layer
					let clearcoatEnergySS = max( ${this.turquinTexture.sampleConductorFn}( NdotVc, surf.clearcoatRoughness ), 1e-5 );
					let clearcoatBoost = 1.0 + ${Ye}( 1.5 ) * ( 1.0 - clearcoatEnergySS ) / clearcoatEnergySS;

					let clearcoatSpecular = ${this.specularBrdf}( Vc, Lc, Hc, vec2( clearcoatAlpha ) ) * clearcoatBoost;
					let clearcoatFresnel = ${Pt}( abs( dot( Vc, Hc ) ), ${Ye}( 1.5 ) );

					// retrieve specular energy reflected by this layer
					let clearcoatFresnelEnergySS = ${this.turquinTexture.sampleDielectricFn}( NdotVc, surf.clearcoatRoughness, 1.5 ) * clearcoatBoost;

					result += surf.clearcoat * clearcoatFresnel * clearcoatSpecular;
					attenuation *= 1.0 - surf.clearcoat * clearcoatFresnelEnergySS;

				}

				// sheen
				if ( NdotL > 0.0 && surf.sheen > 0.0 ) {

					result += attenuation * surf.sheen * ${rh}( ctx.V, ctx.L, ctx.H, surf );
					attenuation *= mix( 1.0, ${th}( ctx.V, ctx.L, surf ), surf.sheen );

				}

				// transmission
				// handles specular reflection and transmission
				if ( surf.transmission > 0.0 ) {

					let matchedIor = ${xi}( surf.eta );

					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					// multiscatter compensation
					var glassBoost = 0.0;
					if ( matchedIor ) {

						glassBoost = 1.0;

					} else if ( surf.thinWall ) {

						// thin wall halves are reflection-shaped so each is compensated with the conductor albedo at its own roughness.
						glassBoost = 1.0 / max( ${this.turquinTexture.sampleConductorFn}( NdotV, surf.roughness ), 1e-5 );

					} else {

						// volumetric bsdf is scaled by its total reflected + refracted energy
						glassBoost = 1.0 / max( ${this.turquinTexture.sampleTransmissiveFn}( NdotV, surf.roughness, surf.ior, surf.frontFace ), 1e-5 );

					}

					let fresnelCosine = select( ctx.VdotH, NdotV, surf.thinWall );
					let fresnel = ${this.transmissionFresnel}( fresnelCosine, surf );

					if ( NdotL < 0.0 ) {

						var refraction = vec3f( 0.0 );
						if ( surf.thinWall && ! matchedIor ) {

							// evaluate the flipped reflection, compensated at the remapped roughness
							let wiMirror = vec3f( ctx.L.xy, - ctx.L.z );
							let thinWallAlpha = vec2f( ${Ts}( alphaB, surf.ior ) );
							let thinWallEnergySS = max( ${this.turquinTexture.sampleConductorFn}( NdotV, sqrt( thinWallAlpha.x ) ), 1e-5 );
							refraction = fresnel.transmittance * ${this.specularBrdf}( ctx.V, wiMirror, ctx.H, thinWallAlpha ) / thinWallEnergySS;

						} else if ( ! matchedIor ) {

							// The default BTDF contains the plain dielectric Fresnel complement. Replace it
							// with the colored thin-film-aware coefficient shared by sampling and the PDF.
							let dielectricTransmittance = 1.0 - ${$r}( abs( ctx.VdotH ), surf.eta );
							if ( dielectricTransmittance > EPSILON ) {

								refraction = ${this.specularBtdf}( ctx.V, ctx.L, ctx.H, alpha, surf.eta ) *
									fresnel.transmittance / dielectricTransmittance * glassBoost;

							}

						}

						// the refracted half is not attenuated by the layers above. A volume is entered and exited,
						// so each crossing takes the square root of the color, as in Cycles
						let transmissionTint = select( sqrt( surf.color ), surf.color, surf.thinWall );
						result += ( 1.0 - surf.metalness ) * surf.transmission * refraction * transmissionTint;

					} else {

						// the raw single scatter lobe scaled by the glass compensation boost rather
						// than the opaque dielectric boost
						let specular = ${this.specularBrdf}( ctx.V, ctx.L, ctx.H, alpha );
						let dielectricSpecular = specular * glassBoost;

						let reflection = dielectricSpecular * fresnel.reflectance;

						result += attenuation * ( 1.0 - surf.metalness ) * surf.transmission * reflection;

					}

				}

				// specular
				// metallic + dielectric + iridescence
				if ( NdotL > 0.0 ) {

					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					// Sample the single scatter energy for specular at the given roughness
					let energySS = max( ${this.turquinTexture.sampleConductorFn}( NdotV, surf.roughness ), 1e-5 );
					let specular = ${this.specularBrdf}( ctx.V, ctx.L, ctx.H, alpha );

					// metallic with the multiscatter comp
					let metallicBoost = 1.0 + surf.color * ( 1.0 - energySS ) / energySS;
					let metallicSpecular = specular * metallicBoost;
					var metallicReflectance = ${this.conductorFresnel}( ctx.VdotH, surf.color, vec3f( 1.0 ) );

					// dielectric with the multiscatter comp
					let dielectricBoost = 1.0 + surf.f0 * ( 1.0 - energySS ) / energySS;
					let dielectricSpecular = specular * dielectricBoost;

					// KHR_materials_specular: fold the specular color and intensity into the dielectric f0.
					// Schlick is used on both hit sides, matching Cycles - the opaque specular carries no
					// TIR so the energy removed from the base always matches the energy paid back
					let dielectricF0 = min( surf.f0 * surf.specularColor, vec3f( 1.0 ) );
					let dielectricFr = ${hi}( ctx.VdotH, dielectricF0, vec3f( 1.0 ) );
					var dielectricReflectance = surf.specularIntensity * dielectricFr;

					// iridescence
					var filmFresnelMax = 0.0;
					if ( surf.iridescence > 0.0 ) {

						// the media on either side of the film - air outside and the volume interior
						// as the base on front faces, swapped on back faces so TIR can take effect
						let outsideIor = select( surf.ior, 1.0, surf.frontFace );
						let filmBaseIor = select( 1.0, surf.ior, surf.frontFace );

						let metallicFilmFresnel = ${this.iridescentFresnel}( ctx.VdotH, surf.color, surf.iridescenceIor, outsideIor, surf.iridescenceThickness );
						metallicReflectance = mix( metallicReflectance, metallicFilmFresnel, surf.iridescence );

						let dielectricFilmFresnel = ${this.iridescentFresnel}( ctx.VdotH, vec3f( ${Ye}( filmBaseIor ) ), surf.iridescenceIor, outsideIor, surf.iridescenceThickness );
						dielectricReflectance = mix( dielectricReflectance, dielectricFilmFresnel, surf.iridescence );
						filmFresnelMax = max( max( dielectricFilmFresnel.r, dielectricFilmFresnel.g ), dielectricFilmFresnel.b );

					}

					let metallic = metallicSpecular * metallicReflectance;
					let dielectric = dielectricSpecular * dielectricReflectance;

					// The energy the specular interface takes from the layers below, at the lobe's specular color
					// and intensity. The table holds the albedo at the ior's f0, and the Schlick albedo is linear
					// in f0, so the tinted albedo slides from it toward the conductor albedo at f0 = 1
					let iorEnergySS = ${this.turquinTexture.sampleDielectricFn}( NdotV, surf.roughness, surf.ior );
					let tintedEnergySS = iorEnergySS + ( dielectricF0 - surf.f0 ) * ( energySS - iorEnergySS ) / ( 1.0 - surf.f0 );
					let fresnelEnergySS = surf.specularIntensity * tintedEnergySS * dielectricBoost;

					result += attenuation * mix( ( 1.0 - surf.transmission ) * dielectric, metallic, surf.metalness );
					attenuation *= ( 1.0 - surf.metalness ) * mix( 1.0 - fresnelEnergySS, vec3f( 1.0 - filmFresnelMax ), surf.iridescence );

				}

				// diffuse
				if ( NdotL > 0.0 ) {

					// the dielectric base mixes diffuse with transmission - the transmissive half
					// is carried by the glass lobe above
					let diffuseVdotH = sqrt( saturate( 0.5 * ( 1.0 + dot( ctx.V, ctx.L ) ) ) );
					let reflection = ${this.diffuseBrdf}( NdotV, NdotL, diffuseVdotH, surf );
					let diffuse = ( 1.0 - surf.transmission ) * reflection;

					result += attenuation * diffuse;

				}

				return result;

			}

		`;let e=this.getBsdfEvalPdfNode();return $`

			fn bsdfSample( worldWo: vec3f, surf: ${_e} ) -> ${ut} {

				var result: ${ut};
				result.color = vec3f( 0.0 );
				result.isTransmissive = false;
				result.direction = vec3f( 0.0 );
				result.pdf = 0.0;

				let wo = normalize( surf.normalInvBasis * worldWo );
				let woClearcoat = normalize( surf.clearcoatInvBasis * worldWo );

				// TODO: mirror bsdfEval's layer structure - the glass layer should own both its
				// reflection and refraction halves
				// lobe selection weights and cumulative bounds in cascade order:
				// clearcoat, specular, transmission, diffuse
				let weights = ${Vl}( wo, wo, woClearcoat, vec3( 0, 0, 1 ), ${oh}, surf );
				let cdfClearcoat = weights.clearcoat;
				let cdfSpecular = cdfClearcoat + weights.specular;
				let cdfTransmission = cdfSpecular + weights.transmission;
				let cdfTotal = cdfTransmission + weights.diffuse;

				// random samples for lobes
				let lobeSample = ${At}( ${xf} ) * cdfTotal;
				let directionUV = ${Ee}( ${Qo} );

				// output
				var wi: vec3f;
				var wh: vec3f;

				// TODO: see if we can clean up these flags so they're not necessary
				var isTransmissionLobe = false;
				var isDeltaTransmission = false;
				var deltaTransmittance = vec3f( 1.0 );
				var deltaProbability = 1.0;
				var isDead = false;

				if ( lobeSample <= cdfClearcoat ) {

					// clearcoat
					let clearcoatAlpha = surf.clearcoatRoughness * surf.clearcoatRoughness;
					let whCoat = ${Pr}( woClearcoat, vec2( clearcoatAlpha ), directionUV );
					let wiCoat = - normalize( reflect( woClearcoat, whCoat ) );

					wi = normalize( surf.normalInvBasis * surf.clearcoatBasis * wiCoat );
					wh = normalize( surf.normalInvBasis * surf.clearcoatBasis * whCoat );

					// reflected rays must leave above the geometry surface - flip rays that land
					// below it due to the shading normal. Rays below the shading hemisphere are
					// left as is since their loss is refunded by the energy compensation tables
					let faceNormal = normalize( surf.normalInvBasis * surf.faceNormal );
					let geomDotDir = dot( wi, faceNormal );
					if ( wi.z > 0.0 && geomDotDir < 0.0 ) {

						wi = normalize( wi - 2.0 * geomDotDir * faceNormal );

					}

				} else if ( lobeSample <= cdfSpecular ) {

					// specular
					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					wh = ${Pr}( wo, alpha, directionUV );
					wi = - normalize( reflect( wo, wh ) );

					// reflected rays must leave above the geometry surface - flip rays that land
					// below it due to the shading normal. Rays below the shading hemisphere are
					// left as is since their loss is refunded by the energy compensation tables
					let faceNormal = normalize( surf.normalInvBasis * surf.faceNormal );
					let geomDotDir = dot( wi, faceNormal );
					if ( wi.z > 0.0 && geomDotDir < 0.0 ) {

						wi = normalize( wi - 2.0 * geomDotDir * faceNormal );

					}

				} else if ( lobeSample <= cdfTransmission ) {

					// transmission
					isTransmissionLobe = true;

					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					// sample the half vector first and select reflection or refraction by the
					// facet fresnel, matching Cycles - total internal reflection drives the
					// fresnel to 1 so TIR facets always reflect with a matching pdf
					wh = ${Pr}( wo, alpha, directionUV );

					let fresnelCosine = select( dot( wo, wh ), wo.z, surf.thinWall );
					let fresnel = ${this.transmissionFresnel}( fresnelCosine, surf );

					let fresnelSample = ( lobeSample - cdfSpecular ) / ( cdfTransmission - cdfSpecular );
					let doReflect = fresnelSample < fresnel.reflectProbability;
					if ( doReflect ) {

						wi = - normalize( reflect( wo, wh ) );

					} else if ( ${xi}( surf.eta ) ) {

						// With no IOR boundary, rough transmission collapses to straight-through
						// delta transmission independent of the sampled microfacet.
						wi = - wo;
						wh = vec3f( 0.0, 0.0, 1.0 );
						isDeltaTransmission = true;
						deltaTransmittance = fresnel.transmittance;
						deltaProbability = 1.0 - fresnel.reflectProbability;

					} else if ( surf.thinWall ) {

						// model the double refraction as a single reflection flipped through the
						// surface at the remapped thin wall roughness
						let thinWallAlpha = vec2f( ${Ts}( alphaB, surf.ior ) );
						wh = ${Pr}( wo, thinWallAlpha, directionUV );
						wi = - normalize( reflect( wo, wh ) );
						wi = vec3f( wi.xy, - wi.z );

					} else {

						wi = refract( - wo, wh, surf.eta );

						// "refract" returns zero on total internal reflection, so reflect instead and let "isDead" drop it
						if ( dot( wi, wi ) == 0.0 ) {

							wi = - normalize( reflect( wo, wh ) );

						}

					}

					// the facet choice must agree with the resulting hemisphere - reflection above,
					// transmission below. Mismatched samples are dropped since their loss is
					// refunded by the energy compensation tables
					isDead = doReflect != ( wi.z > 0.0 );

				} else if ( lobeSample <= cdfTotal ) {

					// diffuse
					// all diffuse BRDF variants share the EON sampling proposal
					wi = ${Qd}( wo, surf.diffuseRoughness, directionUV );
					wh = normalize( wi + wo );

					// reflected rays must leave above the geometry surface - flip rays that land
					// below it due to the shading normal. Rays below the shading hemisphere are
					// left as is since their loss is refunded by the energy compensation tables
					let faceNormal = normalize( surf.normalInvBasis * surf.faceNormal );
					let geomDotDir = dot( wi, faceNormal );
					if ( wi.z > 0.0 && geomDotDir < 0.0 ) {

						wi = normalize( wi - 2.0 * geomDotDir * faceNormal );

					}

				}

				let worldWi = normalize( surf.normalBasis * wi );
				if ( isDeltaTransmission ) {

					// Cycles represents a singular event with a large matching BSDF and PDF. Their
					// ratio preserves throughput while forward MIS correctly gives the event full weight.
					let transmissionTint = select( sqrt( surf.color ), surf.color, surf.thinWall );
					result.color = ( 1.0 - surf.metalness ) * surf.transmission * transmissionTint *
						deltaTransmittance * ${sh};
					result.direction = worldWi;
					result.pdf = weights.transmission * deltaProbability * ${sh};
					result.isTransmissive = dot( worldWi, surf.faceNormal ) < 0.0;

				} else {

					// evaluate the shared eval / pdf function for the sampled direction so the sample
					// and light-sampling paths can never disagree on color or pdf
					result = ${e}( worldWo, worldWi, surf );

				}

				// mismatched glass facets and reflection lobe samples that land below the
				// hemisphere carry no energy - their loss is refunded by the compensation tables
				let invalid = isDead || ( ! isTransmissionLobe && wi.z <= 0.0 );
				result.color *= select( 1.0, 0.0, invalid );

				return result;

			}

		`}getBsdfEvalPdfNode(){if(this._bsdfEvalPdfNode)return this._bsdfEvalPdfNode;this._bsdfEvalFunc||this.getBsdfNode();let e=this._bsdfEvalFunc;return this._bsdfEvalPdfNode=$`

			fn bsdfEvalPdf( worldWo: vec3f, worldWi: vec3f, surf: ${_e} ) -> ${ut} {

				var result: ${ut};
				result.color = vec3f( 0.0 );
				result.isTransmissive = false;
				result.direction = worldWi;
				result.pdf = 0.0;

				let wo = normalize( surf.normalInvBasis * worldWo );
				let wi = normalize( surf.normalInvBasis * worldWi );
				let woClearcoat = normalize( surf.clearcoatInvBasis * worldWo );

				let isTransmission = wi.z < 0.0;
				if ( isTransmission && ${xi}( surf.eta ) ) {

					// Delta transmission has no finite solid-angle BSDF or PDF. It is handled
					// explicitly by bsdfSample, matching PBRT-v4's eta == 1 path.
					return result;

				}

				// reconstruct the half vector bsdfSample would have used for this direction -
				// reflections use the standard half vector while refractions use the generalized
				// form scaled by the ior ratio, oriented into the upper hemisphere
				var wh: vec3f;
				if ( ! isTransmission ) {

					wh = normalize( wo + wi );

				} else if ( surf.thinWall ) {

					// thin wall transmission is modeled as a reflection flipped through the surface
					wh = normalize( wo + vec3f( wi.xy, - wi.z ) );

				} else {

					wh = normalize( wi + wo * surf.eta );
					wh *= sign( wh.z );

				}

				let weights = ${Vl}( wo, wo, woClearcoat, vec3( 0, 0, 1 ), ${oh}, surf );

				// TODO: mirror bsdfEval's layer structure - the glass layer should own both its
				// reflection and refraction halves
				// pdf mixture - every lobe that can produce the direction contributes its share,
				// in the same cascade order and with the same terms as bsdfSample

				// clearcoat
				if ( weights.clearcoat > 0.0 ) {

					// the clearcoat lobe evaluates in its own frame
					let toClearcoatMat = surf.clearcoatInvBasis * surf.normalBasis;
					let wiClearcoat = normalize( toClearcoatMat * wi );
					if ( wiClearcoat.z > 0.0 ) {

						let whClearcoat = normalize( toClearcoatMat * wh );
						let clearcoatAlpha = surf.clearcoatRoughness * surf.clearcoatRoughness;
						result.pdf += weights.clearcoat * ${Br}( woClearcoat, whClearcoat, vec2( clearcoatAlpha ) );

					}

				}

				// specular
				if ( weights.specular > 0.0 && wi.z > 0.0 ) {

					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					result.pdf += weights.specular * ${Br}( wo, wh, alpha );

				}

				// transmission
				if ( weights.transmission > 0.0 ) {

					// anisotropic roughness along tangent, bitangent
					let alphaB = surf.roughness * surf.roughness;
					let alphaT = mix( alphaB, 1.0, surf.anisotropy * surf.anisotropy );
					let alpha = vec2( alphaT, alphaB );

					// the glass lobe selects reflection or refraction by the facet fresnel so
					// each side carries the corresponding share of the transmission pdf
					let fresnelCosine = select( dot( wo, wh ), wo.z, surf.thinWall );
					let fresnel = ${this.transmissionFresnel}( fresnelCosine, surf );

					if ( wi.z > 0.0 ) {

						result.pdf += weights.transmission * fresnel.reflectProbability * ${Br}( wo, wh, alpha );

					} else if ( surf.thinWall ) {

						// the flipped reflection shares the reflection pdf at the remapped roughness
						let thinWallAlpha = vec2f( ${Ts}( alphaB, surf.ior ) );
						result.pdf += weights.transmission * ( 1.0 - fresnel.reflectProbability ) * ${Br}( wo, wh, thinWallAlpha );

					} else {

						result.pdf += weights.transmission * ( 1.0 - fresnel.reflectProbability ) * ${ws}( wo, wi, wh, alpha, surf.eta );

					}

				}

				// diffuse
				if ( weights.diffuse > 0.0 ) {

					result.pdf += weights.diffuse * ${Zd}( wo, wi, surf.diffuseRoughness );

				}

				//

				// construct the scatter context
				var ctx: ${Nl};
				ctx.V = wo;
				ctx.L = wi;
				ctx.H = wh;
				ctx.VdotH = saturate( dot( wo, wh ) );

				// evaluate the bsdf for the direction
				result.color = ${e}( ctx, surf ) * abs( wi.z );

				// a direction crossing below the surface enters or leaves the volume
				result.isTransmissive = isTransmission && dot( worldWi, surf.faceNormal ) < 0.0;

				return result;

			}

		`,this._bsdfEvalPdfNode}};var pr=new ph,pa=new Vv,eb=250,tb=16,lh=1.15,uh=2,rb=4096,ch={strategy:2,targetLeafSize:5},ib=4,bu=0,wu=1,fa=2;async function nb(o,e,t){let r=p=>typeof SharedArrayBuffer<"u"&&p.buffer instanceof SharedArrayBuffer?p:p.slice(),{position:i}=e.attributes,{index:s}=e,n=i.array,l=s?s.array:null;i.array=r(n),s&&(s.array=r(l));let a=await o.generate(e,t);return i.array=n,s&&(l.set(s.array),s.array=l,s.needsUpdate=!0),a}var da=class{constructor(e,t){this.key=t,this.renderer=e,this.texture=null,this.hash=null;let r=new Ov(new Uint8Array([255,255,255,255]),1,1);r.minFilter=ca,r.magFilter=ca,this.colorTex=r}dispose(){this.colorTex.dispose(),this.texture?.dispose()}setFromScene(e){let{renderer:t,key:r,colorTex:i}=this,s=e[r],n="";if(s)s.isColor?(i.image.data[0]=s.r*255,i.image.data[1]=s.g*255,i.image.data[2]=s.b*255,i.image.data[3]=255,s=i,n=i.image.data.join(),i.needsUpdate=n!==this.hash):s.isCubeTexture?(n=null,s=new Ks(t).generate(s)):(n=s.uuid+"_"+s.version,n!==this.hash&&(s=s.clone(),s.minFilter=ca,s.magFilter=ca));else{let l=t.getClearAlpha();t.getClearColor(pa),i.image.data[0]=pa.r*255,i.image.data[1]=pa.g*255,i.image.data[2]=pa.b*255,i.image.data[3]=l*255,s=i,n=i.image.data.join(),i.needsUpdate=n!==this.hash}if(n===null)return this.texture?.dispose(),this.texture=s,this.hash=null,!0;{let l=n!==this.hash;return l&&(this.texture?.dispose(),this.texture=s,this.hash=n),l}}},_u=class extends Uv{get maxBounces(){return this._pathTracer.maxBounces}set maxBounces(e){this._pathTracer.maxBounces=e,this.reset()}get frameBudget(){return this._pathTracer.frameBudget}set frameBudget(e){this._pathTracer.frameBudget=e}get frameTime(){return this._pathTracer.timing.frameTime}get maxTransparentBounces(){return this._pathTracer.maxTransparentBounces}set maxTransparentBounces(e){this._pathTracer.maxTransparentBounces=e,this.reset()}get maxSubsurfaceBounces(){return this._pathTracer.maxSubsurfaceBounces}set maxSubsurfaceBounces(e){this._pathTracer.maxSubsurfaceBounces=e,this.reset()}get maxSamples(){return this._pathTracer.maxSamples}set maxSamples(e){this._pathTracer.maxSamples=e,this._denoiser?.reset()}get complete(){let e=this.maxSamples,t=this._denoiser;return e>0&&this._lastSampleCounts.min>=e&&(!t||t.complete)}async getSampleCountsAsync(){let e=this._pathTracer,t=this._lastSampleCounts,r=await e.getSampleCountsAsync();t.min=r.min,t.max=r.max,t.avg=r.avg;let i=this.getRenderTime()/1e3;return t.samplesPerSecond=i>0?t.avg/i:0,{...t}}get transmissiveBackground(){return this._transmissiveBackground}set transmissiveBackground(e){this._transmissiveBackground!==e&&(this._transmissiveBackground=e,this._pathTracer.setTransmissiveBackground(e))}get filterGlossyFactor(){return this._filterGlossyFactor}set filterGlossyFactor(e){this._filterGlossyFactor!==e&&(this._filterGlossyFactor=e,this._pathTracer.setFilterGlossy(e))}get multipleImportanceSampling(){return this._multipleImportanceSampling}set multipleImportanceSampling(e){this._multipleImportanceSampling!==e&&this.setMultipleImportanceSampling(e)}get clampDirect(){return this._clampDirect}set clampDirect(e){e=Math.max(0,e),this._clampDirect!==e&&(this._clampDirect=e,this._pathTracer.setClamping(e,this._clampIndirect))}get clampIndirect(){return this._clampIndirect}set clampIndirect(e){e=Math.max(0,e),this._clampIndirect!==e&&(this._clampIndirect=e,this._pathTracer.setClamping(this._clampDirect,e))}get target(){return this._pathTracer.outputTarget??null}get fadeState(){return this._fadeState}get lowResTarget(){return this._lowResTarget}get lowResMode(){return this._pathTracer.lowResMode}get compiling(){return this._pathTracer.compiling}get textureAtlas(){return this._bvhData.textureAtlas}useMegakernel(e){let t=this._pathTracer.maxSamples;this._pathTracer.dispose(),this._pathTracer=e?new Ds(this._renderer):new Fn(this._renderer),this._pathTracer.maxSamples=t,this._timingCount=0,this._pathTracer.setBVHData(this._bvhData),this._pathTracer.setMaterial(this.material),this._pathTracer.setRandom(this.random),this._pathTracer.setMultipleImportanceSampling(this.multipleImportanceSampling),this._pathTracer.setTransmissiveBackground(this._transmissiveBackground),this._pathTracer.setFilterGlossy(this._filterGlossyFactor),this._pathTracer.setClamping(this._clampDirect,this._clampIndirect),this._pathTracer.setAccumulateAux(!!(this._denoiser&&this._denoiser.useAuxiliaryBuffers)),this.setCamera(this.camera),this._pathTracer.setEnvironment(this._environmentCache.texture),this._pathTracer.setBackground(this._backgroundCache.texture),this.updateEnvironment(),this.updateLights()}constructor(e){super(),this._renderer=e,this._timer=new qv,this._environmentCache=new da(e,"environment"),this._backgroundCache=new da(e,"background"),this._resetTime=-1,this._fadeState=0,this._lastSampleCounts={min:0,max:0,avg:0,samplesPerSecond:0},this._size=new ph,this._blitQuad=new $t(new Uo),this._timingCount=0,this._frameTimeTotal=0,this._frameTimeCount=0,this._warnedTimestamps=!1,this._fullFrameBudget=null,this._lowResTarget=new Xv(1,1),this._lowResTarget.type=Gv,this._lowResTarget.generateMipmaps=!1,this._pathTracer=new Fn(e),this._denoiser=null,this._upscaler=null,this._onDenoiserProgress=t=>this.dispatchEvent({type:"progress",stage:"denoising",progress:t.progress}),this._stage=fa,this._sceneRequest=0,this._pendingBVHs=new WeakMap,this.minSamples=1,this.renderDelay=16,this.fadeDuration=250,this.dynamicLowRes=!0,this.lowResScale=.1,this.adaptiveDynamicRes=!0,this.adaptiveFrameBudget=!0,this.targetFrameTime=16,this.renderScale=1,this.synchronizeRenderSize=!0,this.renderToCanvas=!0,this.asyncCompile=!1,this.generateMissingAttributes=!0,this.commonAttributes=["normal","tangent"],this.stableNoise=!0,this.pause=!1,this.filterGlossyFactor=1,this._clampDirect=0,this._clampIndirect=10,this.multipleImportanceSampling=!0,this.transmissiveBackground=bi,this.random=qf,this.material=new ua,this._invViewProjectionMatrix=Zv(new jv),this._cameraRayFnHandle=null,this._BVHWorker=null,this._lastCompleteCheck=0,this._wasComplete=!1,this.setMaterial(this.material),this.setRandom(this.random),this.setScene(new Hv,new Wv)}setMultipleImportanceSampling(e){this._multipleImportanceSampling=e,this._pathTracer.setMultipleImportanceSampling(e),this.reset()}setDenoiser(e){this._denoiser?.reset(),this._denoiser?.removeEventListener("progress",this._onDenoiserProgress),this._denoiser=e,e&&(e.init(this._renderer),e.addEventListener("progress",this._onDenoiserProgress)),this._pathTracer.setAccumulateAux(!!(e&&e.useAuxiliaryBuffers)),this.reset()}setUpscaler(e){this._upscaler=e,e&&e.init(this._renderer)}setScene(e,t){e.updateMatrixWorld(!0),t.updateMatrixWorld(),e.traverse(i=>{this.generateMissingAttributes&&i.geometry?.isBufferGeometry&&Gd(i.geometry,this.commonAttributes),i.isSkinnedMesh?(i.boundsTree?i.boundsTree.refit():i.boundsTree=new St(i,{strategy:2,targetLeafSize:5,indirect:!0}),i.boundingBox===null&&(i.boundingBox=new Lv),i.boundsTree.getBoundingBox(i.boundingBox)):i.isMesh&&(i.geometry.boundsTree||(i.geometry.boundsTree=new Ot(i.geometry,ch)))});let r=new Js(e);r.update(),r.textureAtlas.setTextures(this._renderer,r.textures),this._bvhData&&(this._bvhData.dispose(),this._bvhData.textureAtlas.dispose()),this.scene=e,this._bvhData=r,this._pathTracer.setBVHData(r),this.setCamera(t),this.updateEnvironment(),this.updateLights()}setBVHWorker(e){this._BVHWorker=e}async setSceneAsync(e,t){let r=this._BVHWorker;if(!r)throw new Error('WebGPUPathTracer: "setBVHWorker" must be called before "setSceneAsync" can be called.');this._sceneRequest++;let i=this._sceneRequest,s=this._pendingBVHs,n=new Set,l=new Set;e.traverse(h=>{if(h.isMesh&&!h.isSkinnedMesh&&!h.geometry.boundsTree){let{geometry:d}=h;if(d.attributes.position.isInterleavedBufferAttribute||d.index&&d.index.isInterleavedBufferAttribute)return;s.has(d)?l.add(s.get(d)):n.add(d)}});let a=Array.from(n),p=h=>(h.index?h.index.count:h.attributes.position.count)/3,u=a.reduce((h,d)=>h+p(d),0),c=0,f=[];for(let h=0,d=Math.min(ib,a.length);h<d;h++)f.push(new r);this._setStage(bu),this._denoiser?.reset();try{await Promise.all([...l,...f.map(async h=>{for(;a.length>0;){let d=a.shift(),m=p(d),x=0,v=y=>{c+=(y-x)*m,x=y,i===this._sceneRequest&&this.dispatchEvent({type:"progress",stage:"building",progress:c/u})},g=nb(h,d,{...ch,onProgress:v});s.set(d,g);try{d.boundsTree=await g,v(1)}finally{s.delete(d)}}})])}finally{f.forEach(h=>h.dispose()),i===this._sceneRequest&&this._setStage(wu)}i===this._sceneRequest&&this.setScene(e,t)}getMaterial(){return this.material}setMaterial(e){this.material=e,this._pathTracer.setMaterial(e),this.reset()}setRandom(e){this.random=e,this._pathTracer.setRandom(e),this.reset()}setCamera(e){if(this.camera=e,e.getCameraRayFn)this._cameraRayFnHandle=e.getCameraRayFn();else{let t=this._invViewProjectionMatrix;this._cameraRayFnHandle={update:()=>(e.coordinateSystem=Kv,e.updateMatrixWorld(),(e.isOrthographicCamera||e.isPerspectiveCamera)&&e.updateProjectionMatrix(),t.value.multiplyMatrices(e.matrixWorld,e.projectionMatrixInverse),!1),fn:$`
					fn getCameraRay( uv: vec2f, resolution: vec2f, ray: ptr<function, ${D}> ) -> bool {

						let ndc = uv * 2.0 - vec2f( 1.0 );
						*ray = ${sr}( ndc, ${t} );
						return true;

					}
				`}}this._bvhData.fns.getCameraRay=this._cameraRayFnHandle.fn,this._cameraRayFnHandle.update(),this._pathTracer.rebuild(),this.reset()}updateMaterials(){let{_bvhData:e,_renderer:t}=this;e.updateMaterials(),e.textureAtlas.setTextures(t,e.textures),this.reset()}updateTransforms(){this.scene.updateMatrixWorld(!0),this._bvhData.updateTransforms(),this.reset()}updateCamera(){let{_cameraRayFnHandle:e,_pathTracer:t}=this;e.update()&&t.rebuild(),this.reset()}updateEnvironment(){let{_pathTracer:e,scene:t,_environmentCache:r,_backgroundCache:i}=this;r.setFromScene(t)&&e.setEnvironment(r.texture),i.setFromScene(t)&&e.setBackground(i.texture),e.setEnvironmentParams(t.environmentIntensity,t.environmentRotation),e.setBackgroundParams(t.backgroundIntensity,t.backgroundRotation,t.backgroundBlurriness),this.reset()}updateLights(){let{_pathTracer:e,scene:t}=this,r=Od(t);e.setLights(r),this.reset()}setSize(e,t){(this._size.x!==e||this._size.y!==t)&&(this._size.set(e,t),this.reset())}reset(){this._pathTracer.reset(),this._denoiser?.reset(),this._resetTime=-1,this._fadeState=0,this._timer.update(),this._lastSampleCounts={min:0,max:0,avg:0,samplesPerSecond:0},this._lastCompleteCheck=0,this._wasComplete=!1,this.stableNoise&&this._pathTracer.resetSeed(),this._fullFrameBudget!==null&&(this.frameBudget=this._fullFrameBudget,this._fullFrameBudget=null)}_setStage(e){this._stage!==e&&(this._stage=e,e===bu?this.dispatchEvent({type:"progress",stage:"building",progress:0}):e===wu?this.dispatchEvent({type:"progress",stage:"compiling",progress:null}):this._dispatchRenderProgress({min:0,max:0,avg:0,samplesPerSecond:0}))}_measureSamples(){let e=this._lastSampleCounts;this.getSampleCountsAsync().then(t=>{if(e===this._lastSampleCounts&&this._stage===fa){let{maxSamples:r}=this;this._fullFrameBudget===null&&r>0&&t.max>=r&&(this._fullFrameBudget=this.frameBudget),this._dispatchRenderProgress(t)}})}_dispatchRenderProgress(e){let{maxSamples:t}=this,r=t>0?Math.min(e.avg/t,1):null;this.dispatchEvent({type:"progress",stage:"rendering",progress:r,samples:e})}_readTiming(){let{timing:e}=this._pathTracer,{trackTimestamp:t}=this._renderer.backend;if(this.adaptiveFrameBudget&&!t&&!this._warnedTimestamps&&(console.warn('WebGPUPathTracer: "adaptiveFrameBudget" needs timestamp queries. Create the renderer with "trackTimestamp: true" on a device that supports them.'),this._warnedTimestamps=!0),e.count===this._timingCount)return;this._timingCount=e.count;let{frameBudget:r}=e;if(!this.adaptiveFrameBudget||r!==this.frameBudget){this._frameTimeTotal=0,this._frameTimeCount=0;return}if(this._frameTimeTotal+=e.frameTime,this._frameTimeCount++,this._frameTimeCount<tb)return;let{targetFrameTime:i}=this,s=this._frameTimeTotal/this._frameTimeCount;this._frameTimeTotal=0,this._frameTimeCount=0;let n=s/i;if(n<lh&&n>1/lh)return;let l=r*i/s,a=zv.clamp(l,r/uh,r*uh);this.frameBudget=Math.max(Math.round(a),rb)}renderSample(){let e=this._renderer,t=this._size,r=this._blitQuad,i=this._pathTracer,s=this._lowResTarget,n=this._timer,{renderDelay:l,dynamicLowRes:a,synchronizeRenderSize:p,renderScale:u,adaptiveDynamicRes:c,minSamples:f,maxSamples:h}=this;if(n.update(),!this._renderer._initialized)return;this.material.initialized||(this.material.init(e),this.material.initialized=!0);let d=1e3*n.getDelta(),m=this._resetTime===-1;m&&(this._resetTime=0,d=0),this._resetTime+=d;let x=e.getRenderTarget(),v=e.autoClear;if(p){e.getDrawingBufferSize(pr);let C=Math.floor(u*pr.x),B=Math.floor(u*pr.y);this.setSize(C,B)}let{width:g,height:y}=t,w=this._resetTime<l||m&&a&&f!==0;if(w){let C=Math.min(Math.sqrt(i.getPreviewPixelBudget()/(g*y)),1);c&&(this.lowResScale=C);let B=Math.min(this.lowResScale,C);g=Math.max(1,Math.floor(B*g)),y=Math.max(1,Math.floor(B*y))}i.getSize(pr),!w&&i.lowResMode&&a&&(s.setSize(pr.x,pr.y),e.copyTextureToTexture(i.outputTarget,s)),(pr.x!==g||pr.y!==y)&&i.setSize(g,y);let b=h>0&&this._lastSampleCounts.min>=h;if(!this.pause&&!b&&this._stage>bu&&(!w||w&&a)&&(i.lowResMode=w,i.asyncCompile=this.asyncCompile,i.update(),this._readTiming(),this._setStage(i.compiling?wu:fa)),this._stage<fa){this._resetTime=-1;return}let T=this._denoiser,_=this._upscaler,S=!!T&&!T.complete&&!T.running&&h>0;w||(!b&&this._fadeState<1&&f>0?this._measureSamples():!b&&this._resetTime-this._lastCompleteCheck>=eb&&(this._lastCompleteCheck=this._resetTime,this._measureSamples()),this._lastSampleCounts.avg>=f&&(this._fadeState+=d/this.fadeDuration,this._fadeState=Math.min(1,this._fadeState)||1));let A=w&&a?1:this._fadeState,I=i.outputTarget;T&&!w&&(S&&this._lastSampleCounts.min>=h&&T.update(i.outputTarget,i.auxTarget),I=T.texture??I),this.renderToCanvas&&(_&&!w&&(I=_.upscale(I,this.camera),e.setRenderTarget(x)),e.autoClear=a?!0:A===1,r.material.transition=a?A:1,r.material.opacity=a?1:A,r.material.fromTexture=s,r.material.texture=I,r.render(e)),e.autoClear=v,e.setRenderTarget(x);let P=this.complete;P&&!this._wasComplete&&this.dispatchEvent({type:"complete"}),this._wasComplete=P}renderDebugBounds(e={}){let{displayTLAS:t=!0,displayBLAS:r=!0,stopAtSurface:i=!1,saturationCount:s=64}=e,n=this._renderer,l=this.camera;if(!n._initialized)return;l.updateMatrixWorld(),(this._debugBoundsQuad===void 0||this._debugBoundsData!==this._bvhData)&&this._buildDebugBoundsQuad();let a=this._debugBoundsUniforms;a.cameraToModelMatrix.value.copy(l.matrixWorld),a.inverseProjectionMatrix.value.copy(l.projectionMatrixInverse),a.displayTLAS.value=t?1:0,a.displayBLAS.value=r?1:0,a.stopAtSurface.value=i?1:0,a.saturationCount.value=s;let p=n.getRenderTarget(),u=n.autoClear,c=n.toneMapping;n.setRenderTarget(null),n.autoClear=!0,n.toneMapping=vu,this._debugBoundsQuad.render(n),n.setRenderTarget(p),n.autoClear=u,n.toneMapping=c}_buildDebugBoundsQuad(){let e=this._bvhData,t=af(e),r=new Yv;r.colorNode=t(Jv(Qv())),this._debugBoundsQuad===void 0?this._debugBoundsQuad=new $t(r):(this._debugBoundsQuad.material.dispose(),this._debugBoundsQuad.material=r),this._debugBoundsUniforms=t.uniforms,this._debugBoundsData=e}renderTextureAtlas(e=0){let t=this._renderer;this._atlasDebugQuad||(this._atlasDebugQuad=new $t(new ea));let r=this._atlasDebugQuad;r.material.texture=this.textureAtlas.texture,r.material.layer=e,t.setRenderTarget(null),r.render(t)}renderSampleDensity(){let e=this._renderer;if(!e._initialized)return;this.getSampleCountsAsync(),this._sampleDensityQuad||(this._sampleDensityQuad=new $t(new ta));let t=e.toneMapping;e.toneMapping=vu;let r=this._sampleDensityQuad;r.material.texture=this._pathTracer.sampleCountTarget,r.material.minCount=this._lastSampleCounts.min,r.material.maxCount=this._lastSampleCounts.max,e.setRenderTarget(null),r.render(e),e.toneMapping=t}renderAuxBuffer(e="albedo"){let t=this._renderer,r=this._pathTracer;if(!t._initialized||!r.auxTarget)return;this._auxDebugQuad||(this._auxDebugQuad=new $t(new ra));let i=t.toneMapping;t.toneMapping=vu;let s=this._auxDebugQuad;s.material.texture=r.auxTarget,s.material.showNormal=e==="normal",t.setRenderTarget(null),s.render(t),t.toneMapping=i}dispose(){this._pathTracer.dispose(),this._denoiser?.dispose(),this._upscaler?.dispose(),this._bvhData.dispose(),this._bvhData.textureAtlas.dispose(),this._environmentCache.dispose(),this._backgroundCache.dispose(),this._lowResTarget.dispose(),this._blitQuad.dispose(),this._blitQuad.material.dispose(),this._atlasDebugQuad?.dispose(),this._atlasDebugQuad?.material.dispose(),this._sampleDensityQuad?.dispose(),this._sampleDensityQuad?.material.dispose(),this._auxDebugQuad?.dispose(),this._auxDebugQuad?.material.dispose(),this._debugBoundsQuad!==void 0&&(this._debugBoundsQuad.dispose(),this._debugBoundsQuad.material.dispose())}getRenderTime(){return this._resetTime}};import{EventDispatcher as sb,ExternalTexture as ab}from"/vendor/three/0.187.0/three.webgpu.js";import{textureStore as Tu,globalId as ob}from"/vendor/three/0.187.0/three.tsl.js";var ha=class extends z{constructor(){let e={auxTarget:Tu(ct()).toReadOnly(),albedoTarget:Tu(pi()).toWriteOnly(),normalTarget:Tu(pi()).toWriteOnly(),globalId:ob},t=$`
			fn compute( globalId: vec3u ) -> void {

				let dims = textureDimensions( ${e.auxTarget} );
				if ( globalId.x >= dims.x || globalId.y >= dims.y ) {

					return;

				}

				var packed: vec3u;
				for ( var layer = 0u; layer < ${Re}u; layer ++ ) {

					packed[ layer ] = textureLoad( ${e.auxTarget}, globalId.xy, layer ).r;

				}

				textureStore( ${e.albedoTarget}, globalId.xy, vec4f( ${$n}( packed ), 1.0 ) );
				textureStore( ${e.normalTarget}, globalId.xy, vec4f( ${Cn}( packed ), 1.0 ) );

			}
		`;super(t(e)),this.defineUniformAccessors(e)}};var Su=class extends sb{get texture(){return this._texture}get complete(){return this._complete}get running(){return this._running}constructor(e={}){super();let{initUNetFromURL:t,auxWeightsUrl:r,colorWeightsUrl:i,useAuxiliaryBuffers:s=!0,maxTileSize:n=null,dynamicTile:l=null}=e;if(!t)throw new Error('OIDNDenoiser: "initUNetFromURL" from "oidn-webgpu" must be provided.');this.initUNetFromURL=t,this.auxWeightsUrl=r,this.colorWeightsUrl=i,this.useAuxiliaryBuffers=s,this.maxTileSize=n,this.dynamicTile=l,this.renderer=null,this._texture=null,this._running=!1,this._complete=!1,this._abort=null,this._requestId=0,this._failed=!1,this._unets={aux:null,color:null},this._resultPipeline=null,this._rawTexture=null,this._unpackAuxKernel=new ha().setWorkgroupSize(8,8,1),this._albedoTarget=pi("Denoiser Albedo"),this._normalTarget=pi("Denoiser Normal")}init(e){this.renderer=e}update(e,t=null){if(this._complete||this._running||this._failed)return this._texture;let r=null,i=null;return this.useAuxiliaryBuffers&&t&&(this._unpackAux(t),r=this._albedoTarget,i=this._normalTarget),this.denoise(e,r,i).catch(s=>{this._failed=!0,this.dispatchEvent({type:"error",error:s})}),this._texture}async denoise(e,t=null,r=null){if(this._complete||this._running)return;this._running=!0;let i=!!(t&&r),s=++this._requestId,n;try{n=await this._initUNet(i)}catch(f){throw this._running=!1,f}if(s!==this._requestId||!this._running)return;let{renderer:l}=this,a=l.backend;l.initTexture(e);let{width:p,height:u}=e,c={color:{data:a.get(e).texture,width:p,height:u},scheduling:"animation-frame",progress:(f,h,d,m,x)=>{this._copyOutputToTexture(f,p,u),this.dispatchEvent({type:"progress",progress:(m+1)/x})},done:f=>{this._running=!1,this._complete=!0,this._abort=null,this._disposeAuxImages()}};i&&(l.initTexture(t),l.initTexture(r),c.albedo={data:a.get(t).texture,width:p,height:u},c.normal={data:a.get(r).texture,width:p,height:u}),this._abort=n.tileExecute(c)}reset(){this._abort?.(),this._abort=null,this._texture?.dispose(),this._texture=null,this._rawTexture?.destroy(),this._rawTexture=null,this._disposeAuxImages(),this._running=!1,this._complete=!1,this._failed=!1}dispose(){this.reset(),this._unpackAuxKernel.dispose();for(let e in this._unets)this._unets[e]?.then(t=>t.dispose()),this._unets[e]=null}_unpackAux(e){let{renderer:t,_unpackAuxKernel:r,_albedoTarget:i,_normalTarget:s}=this,{width:n,height:l}=e.image;i.setSize(n,l),s.setSize(n,l),r.auxTarget=e,r.albedoTarget=i,r.normalTarget=s,t.compute(r.kernel,r.getDispatchSize(n,l))}_disposeAuxImages(){this._albedoTarget.dispose(),this._normalTarget.dispose()}_initUNet(e){let t=e?"aux":"color";return this._unets[t]||(this._unets[t]=(async()=>{let r=this.renderer.backend.device,i=e?this.auxWeightsUrl:this.colorWeightsUrl;if(!i)throw new Error(`OIDNDenoiser: no ${e?"auxWeightsUrl":"colorWeightsUrl"} was provided.`);let s={aux:e,hdr:!0};return this.maxTileSize!==null&&(s.maxTileSize=this.maxTileSize),this.dynamicTile!==null&&(s.dynamicTile=this.dynamicTile),this.initUNetFromURL(i,{device:r},s)})()),this._unets[t]}_copyOutputToTexture(e,t,r){let i=this.renderer.backend.device;if(!this._resultPipeline){let a=i.createShaderModule({code:`
					@group( 0 ) @binding( 0 ) var<storage, read> src : array<vec4f>;
					@group( 0 ) @binding( 1 ) var dst : texture_storage_2d<rgba16float, write>;

					@compute @workgroup_size( 8, 8 )
					fn main( @builtin( global_invocation_id ) gid : vec3u ) {

						let dims = textureDimensions( dst );
						if ( gid.x >= dims.x || gid.y >= dims.y ) {

							return;

						}

						textureStore( dst, gid.xy, src[ gid.y * dims.x + gid.x ] );

					}
				`});this._resultPipeline=i.createComputePipeline({layout:"auto",compute:{module:a,entryPoint:"main"}})}(!this._rawTexture||this._rawTexture.width!==t||this._rawTexture.height!==r)&&(this._rawTexture?.destroy(),this._rawTexture=i.createTexture({size:[t,r],format:"rgba16float",usage:GPUTextureUsage.STORAGE_BINDING|GPUTextureUsage.TEXTURE_BINDING}),this._texture?.dispose(),this._texture=new ab(this._rawTexture),this._texture.image={width:t,height:r});let s=i.createBindGroup({layout:this._resultPipeline.getBindGroupLayout(0),entries:[{binding:0,resource:{buffer:e.data}},{binding:1,resource:this._rawTexture.createView()}]}),n=i.createCommandEncoder(),l=n.beginComputePass();l.setPipeline(this._resultPipeline),l.setBindGroup(0,s),l.dispatchWorkgroups(Math.ceil(t/8),Math.ceil(r/8)),l.end(),i.queue.submit([n.finish()])}};import{Matrix4 as ub}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as cb,PI as fh}from"/vendor/three/0.187.0/three.tsl.js";import{Camera as lb}from"/vendor/three/0.187.0/three.webgpu.js";var ma=class extends lb{constructor(){super(),this.isEquirectCamera=!0}};ma.prototype.getCameraRayFn=function(){let e=cb(new ub);return{fn:$`
		fn getCameraRay( uv: vec2f, resolution: vec2f, ray: ptr<function, ${D}> ) -> bool {

			// screen uv to spherical direction, matching three.js' equirect orientation
			let theta = ( uv.x - 0.5 ) * 2.0 * ${fh};
			let phi = ( 1.0 - uv.y ) * ${fh};
			let sinPhi = sin( phi );
			let direction = vec3f( sinPhi * cos( theta ), cos( phi ), sinPhi * sin( theta ) );

			// equirect ignores the projection - orient by the camera world matrix and place the origin.
			// there is no projection far plane, so the trace distance is unbounded
			ray.origin = ( ${e} * vec4f( 0.0, 0.0, 0.0, 1.0 ) ).xyz;
			ray.direction = ( ${e} * vec4f( direction, 0.0 ) ).xyz;
			ray.maxDist = 0.0;
			return true;

		}
	`,update:()=>(e.value.copy(this.matrixWorld),!1)}};import{Matrix4 as dh,WebGPUCoordinateSystem as fb}from"/vendor/three/0.187.0/three.webgpu.js";import{uniform as Fr,PI as hh}from"/vendor/three/0.187.0/three.tsl.js";import{PerspectiveCamera as pb}from"/vendor/three/0.187.0/three.webgpu.js";var ga=class extends pb{set bokehSize(e){this.fStop=this.getFocalLength()/e}get bokehSize(){return this.getFocalLength()/this.fStop}constructor(...e){super(...e),this.fStop=1.4,this.apertureBlades=0,this.apertureRotation=0,this.focusDistance=25,this.anamorphicRatio=1}copy(e,t){return super.copy(e,t),this.fStop=e.fStop,this.apertureBlades=e.apertureBlades,this.apertureRotation=e.apertureRotation,this.focusDistance=e.focusDistance,this.anamorphicRatio=e.anamorphicRatio,this}};var db=$`
	fn sampleCircle( uv: vec2f ) -> vec2f {

		let angle = 2.0 * ${hh} * uv.x;
		let radius = sqrt( uv.y );
		return vec2f( cos( angle ), sin( angle ) ) * radius;

	}
`,hb=$`
	fn sampleTriangle( a: vec2f, b: vec2f, c: vec2f, rIn: vec2f ) -> vec2f {

		let e1 = a - b;
		let e2 = c - b;

		var r = rIn;
		if ( r.x + r.y > 1.0 ) {

			r = vec2f( 1.0 ) - r;

		}

		return e1 * r.x + e2 * r.y;

	}
`,mb=$`
	fn sampleRegularPolygon( sidesIn: i32, uvw: vec3f ) -> vec2f {

		let sides = max( sidesIn, 3 );
		let anglePerSegment = 2.0 * ${hh} / f32( sides );
		let segment = floor( f32( sides ) * uvw.x );

		let angle1 = anglePerSegment * segment;
		let angle2 = angle1 + anglePerSegment;
		let a = vec2f( sin( angle1 ), cos( angle1 ) );
		let b = vec2f( 0.0, 0.0 );
		let c = vec2f( sin( angle2 ), cos( angle2 ) );

		return ${hb}( a, b, c, uvw.yz );

	}
`,gb=$`
	fn sampleAperture( blades: i32, uvw: vec3f ) -> vec2f {

		if ( blades == 0 ) {

			return ${db}( uvw.xy );

		} else {

			return ${mb}( blades, uvw );

		}

	}
`,xb=$`
	fn rotateVector( v: vec2f, t: f32 ) -> vec2f {

		let vc = cos( t );
		let vs = sin( t );
		return vec2f( v.x * vc - v.y * vs, v.x * vs + v.y * vc );

	}
`;ga.prototype.getCameraRayFn=function(){let e=Fr(new dh),t=Fr(new dh),r=Fr(0),i=Fr(0),s=Fr(0,"int"),n=Fr(0),l=Fr(1);return{fn:$`
		fn getCameraRay( uv: vec2f, resolution: vec2f, ray: ptr<function, ${D}> ) -> bool {

			// base ray
			let ndc = uv * 2.0 - vec2f( 1.0 );
			*ray = ${sr}( ndc, ${e} );

			// camera state
			let forward = normalize( ( ${t} * vec4f( 0.0, 0.0, - 1.0, 0.0 ) ).xyz );
			let cameraOrigin = ( ${t} * vec4f( 0.0, 0.0, 0.0, 1.0 ) ).xyz;

			// depth of field
			// measure focus distance from the camera origin along the optical axis so the focal
			// surface is a flat plane perpendicular to the camera forward vector rather than a sphere
			let focalPoint = cameraOrigin + ray.direction * ( ${r} / dot( ray.direction, forward ) );
			let nearDistance = dot( ray.origin - cameraOrigin, forward );

			// sample the aperture shape
			let shapeUVW = ${di}( ${yf} );
			var apertureSample = ${gb}( ${s}, shapeUVW );
			apertureSample *= ${i} * 0.5 * 1e-3;

			// rotate + squash the sample for anamorphic apertures
			apertureSample =
				${xb}( apertureSample, ${n} )
				* vec2f( ${l}, 1.0 / ${l} );

			// aim from the aperture through the focal point, starting on the near plane like the base ray
			ray.origin = cameraOrigin + ( ${t} * vec4f( apertureSample, 0.0, 0.0 ) ).xyz;
			ray.direction = normalize( focalPoint - ray.origin );
			ray.origin += ray.direction * ( nearDistance / dot( ray.direction, forward ) );

			return true;

		}
	`,update:()=>(this.coordinateSystem=fb,this.updateMatrixWorld(),this.updateProjectionMatrix(),e.value.multiplyMatrices(this.matrixWorld,this.projectionMatrixInverse),t.value.copy(this.matrixWorld),r.value=this.focusDistance,i.value=this.bokehSize,s.value=this.apertureBlades,n.value=this.apertureRotation,l.value=this.anamorphicRatio,!1)}};import{ArrayCamera as yb,Matrix4 as vb,Vector4 as bb,WebGPUCoordinateSystem as wb}from"/vendor/three/0.187.0/three.webgpu.js";import{uniformArray as mh,int as _b}from"/vendor/three/0.187.0/three.tsl.js";yb.prototype.getCameraRayFn=function(){let e={fn:null,update:null},t=_b(0),r=mh([],"mat4"),i=mh([],"vec4");return e.fn=$`
		fn getCameraRay( uv: vec2f, resolution: vec2f, ray: ptr<function, ${D}> ) -> bool {

			let pixel = uv * resolution;

			for ( var i = 0u; i < ${t}u; i ++ ) {

				let viewport = ${i}[ i ];
				let viewportMax = viewport.xy + viewport.zw;
				if ( all( pixel >= viewport.xy ) && all( pixel < viewportMax ) ) {

					let cameraUv = ( pixel - viewport.xy ) / viewport.zw;
					let ndc = cameraUv * 2.0 - vec2f( 1.0 );
					*ray = ${sr}( ndc, ${r}[ i ] );
					return true;

				}

			}

			return false;

		}
	`,e.update=()=>{let{cameras:s}=this,n=t.node.value!==s.length;for(t.node.value=s.length;i.array.length<s.length;)i.array.push(new bb),r.array.push(new vb);r.array.length=s.length,i.array.length=s.length;for(let l=0;l<s.length;l++){let a=s[l],p=a.viewport;a.coordinateSystem=wb,a.updateMatrixWorld(),a.updateProjectionMatrix(),r.array[l].multiplyMatrices(a.matrixWorld,a.projectionMatrixInverse),i.array[l].copy(p)}return n},e};var Iu=class{dims=[];paddedDims=[];layout="x";dataType="Float32";getByteSize(){let e=1;for(let t of this.paddedDims)e*=t;return this.dataType==="Float32"?e*=4:this.dataType==="Float16"&&(e*=2),e}},$u=class{desc;data;constructor(e,t){this.desc=e,this.data=t}},Cu=class{_view;offset=0;constructor(e){this._view=e}read(e){let t=this._view,r=this.offset;switch(this.offset+=e,e){case 1:return t.getUint8(r);case 2:return t.getUint16(r,!0);case 4:return t.getUint32(r,!0);case 8:return Number(t.getBigUint64(r,!0));default:throw new Error("unsupported read size")}}};function gh(o){let e=new Uint8Array(o),t=new Cu(new DataView(o));if(t.read(2)!==16855)throw new Error("invalid or corrupted weights blob");let i=t.read(1),s=t.read(1);if(i!==2)throw new Error("unsupported weights blob version");let n=t.read(8);t.offset=n;let l=t.read(4),a=new Map;for(let p=0;p<l;++p){let u=new Iu,c=t.read(2),f=new TextDecoder().decode(e.subarray(t.offset,t.offset+c));t.offset+=c;let h=t.read(1);for(let g=0;g<h;++g)u.dims.push(t.read(4));u.paddedDims=[...u.dims],new TextDecoder().decode(e.subarray(t.offset,t.offset+h))==="oihw"&&(u.layout="oihw"),t.offset+=h;let m=String.fromCharCode(t.read(1));if(m==="f")u.dataType="Float32";else if(m==="h")u.dataType="Float16";else throw new Error("invalid tensor data type");let x=t.read(8),v=e.slice(x,x+u.getByteSize());a.set(f,new $u(u,v))}return a}function Tb(o,e){return o.channels===e.channels}var xa=8,kr=class{autoUpdateOutputBuffer=!0;_label;_device;_outputBuffers={};_pipeline;_bindGroups=[];_needsUpdatePipeline=!0;_needsResizeBuffer=!0;_inputs=[];_outputs=[];_uniforms=[];_uniformBuffers={};_width=10;_height=10;_execWidth;_execHeight;_csCode="";_csMain;_csDefine;_groupOffsets={inputs:0,uniforms:1,outputs:2};constructor(e,t,r){this._label=e,this._device=t,this._csMain=r.csMain,this._csDefine=r.csDefine,this._inputs=r.inputs,this._outputs=r.outputs,this._uniforms=r.uniforms,this.autoUpdateOutputBuffer=r.autoUpdateOutputBuffer??!0,r.uniforms.forEach(i=>{this._uniformBuffers[i.label]=t.createBuffer({label:this._label,size:i.data.byteLength,usage:GPUBufferUsage.UNIFORM|GPUBufferUsage.COPY_DST}),this._device.queue.writeBuffer(this._uniformBuffers[i.label],0,i.data)})}setCSCode({csDefine:e,csMain:t}){this._csDefine=e,this._csMain=t,this._needsUpdatePipeline=!0}setSize(e,t){e=Math.ceil(e),t=Math.ceil(t);let r=e!==this._width||t!==this._height;this._width=e,this._height=t,r&&(this._needsResizeBuffer=!0,this._needsUpdatePipeline=!0)}setExecuteSize(e,t){e=Math.ceil(e),t=Math.ceil(t),this._execWidth=e,this._execHeight=t}setOutputParams(e){this.autoUpdateOutputBuffer&&this._updateOutputBuffers(e),this._needsUpdatePipeline=!0}setOutputBuffers(e){this._outputBuffers=Object.keys(e).reduce((t,r)=>(t[r]={buffer:e[r],params:{channels:4}},t),{})}setUniform(e,t){let r=this._uniformBuffers[e];this._device.queue.writeBuffer(r,0,t)}getOutput(e){return this._needsResizeBuffer&&this.autoUpdateOutputBuffer&&(this._resizeOutputBuffers(),this._needsResizeBuffer=!1),this._outputBuffers[e].buffer}dispose(e=!0){Object.keys(this._uniformBuffers).forEach(t=>{this._uniformBuffers[t].destroy()}),e&&Object.keys(this._outputBuffers).forEach(t=>{this._outputBuffers[t].buffer.destroy()})}_createBuffer(e){let t=this._width*this._height*4*4;return this._device.createBuffer({label:this._label,size:Math.max(t,80),usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_DST|GPUBufferUsage.COPY_SRC})}_resizeOutputBuffers(){let e=this._outputBuffers;for(let t in e){let{buffer:r,params:i}=e[t];r.destroy(),e[t].buffer=this._createBuffer(i)}}_updateOutputBuffers(e){let t=this._outputBuffers;for(let r in e){let i=e[r];if(!Tb(i,t[r]?.params||{})){t[r]?.buffer.destroy();let s=this._createBuffer(i);t[r]={buffer:s,params:i}}}}_updatePipeline(e,t){if(!this._needsUpdatePipeline)return;this._needsUpdatePipeline=!1;let r=this._device,i=this._getFullCs(e,t);i!==this._csCode&&(this._csCode=i,this._pipeline=r.createComputePipeline({label:this._label,layout:"auto",compute:{module:r.createShaderModule({label:this._label,code:i}),entryPoint:"main"}}),this._updateBindGroups())}_getFullCs(e,t){let r=this._inputs,i=this._uniforms,s=0,n=this._groupOffsets={inputs:0,uniforms:0,outputs:0};return r.length>0&&s++,i.length>0&&(n.uniforms=s,s++),n.outputs=s,`
${r.sort().map((a,p)=>{let u=`@group(${n.inputs}) @binding(${p}) `,c=`in_${a}`;return t[a]==="texture"?`${u} var ${c}: texture_2d<f32>;`:`${u} var<storage, read> ${c}: array<vec${e[a].channels}f>;`}).join(`
`)}
${this._uniforms.map((a,p)=>`@group(${n.uniforms}) @binding(${p}) var<uniform> ${a.label}: ${a.type};`).join(`
`)}

${this._outputs.map((a,p)=>`@group(${n.outputs}) @binding(${p}) var<storage, read_write> out_${a}: array<vec${this._outputBuffers[a].params.channels}f>;`).join(`
`)}
${this._csDefine??""}
@compute @workgroup_size(${xa}, ${xa}, 1)
fn main(@builtin(global_invocation_id) globalId: vec3u) {
${this._csMain}
}
`}_updateBindGroups(){let e=[],t=this._device,r=this._groupOffsets;this._uniforms.length>0&&(e[r.uniforms]=t.createBindGroup({label:this._label,layout:this._pipeline.getBindGroupLayout(r.uniforms),entries:this._uniforms.map((i,s)=>({binding:s,resource:{buffer:this._uniformBuffers[i.label]}}))})),this._bindGroups=e}createPass(e,t){this._needsResizeBuffer&&this.autoUpdateOutputBuffer&&(this._resizeOutputBuffers(),this._needsResizeBuffer=!1);let r=this._inputs.reduce((n,l)=>(n[l]=t[l].buffer?"buffer":"texture",n),{});this._updatePipeline(t,r);let i=this._groupOffsets;this._inputs.length>0&&(this._bindGroups[i.inputs]=this._device.createBindGroup({label:this._label,layout:this._pipeline.getBindGroupLayout(i.inputs),entries:this._inputs.map((n,l)=>({binding:l,resource:t[n].buffer?{buffer:t[n].buffer}:t[n].texture.createView()}))})),this._bindGroups[i.outputs]=this._device.createBindGroup({label:this._label,layout:this._pipeline.getBindGroupLayout(i.outputs),entries:this._outputs.map((n,l)=>({binding:l,resource:{buffer:this._outputBuffers[n].buffer}}))});let s=e.beginComputePass();s.setPipeline(this._pipeline),this._bindGroups.forEach((n,l)=>{s.setBindGroup(l,n)}),s.dispatchWorkgroups(Math.ceil((this._execWidth??this._width)/xa),Math.ceil((this._execHeight??this._height)/xa),1),s.end()}};var Di=34028234663852886e22;function Ni(o,e,t){return Number.isNaN(o)?0:Math.min(Math.max(o,e),t)}var Au=`
const FLT_MAX = 3.4028234663852886e38;
fn sanitize(v: vec3f, lo: f32, hi: f32) -> vec3f {
  let bits = bitcast<vec3u>(v);
  let magnitude = bits & vec3u(0x7fffffffu);
  let negative = (bits & vec3u(0x80000000u)) != vec3u(0u);
  let infinite = select(vec3f(hi), vec3f(lo), negative);
  let clamped = select(clamp(v, vec3f(lo), vec3f(hi)), infinite, magnitude == vec3u(0x7f800000u));
  return select(clamped, vec3f(0.0), magnitude > vec3u(0x7f800000u));
}
`;var Pu=1412.83765,Bu=1.64593172,Ru=.431384981,Eu=-.00294139609,Mu=.192653254,Du=.00626026094,Nu=.998620152,xh=15794576e-13,yh=.0322087631,vh=.00223151711,bh=.370974749,wh=65504,_h=$h(wh),Th=1/_h,Sh=_h,ya=Math.log(wh+1),Ih=1/ya;function $h(o){return o<=xh?Pu*o:o<=yh?Bu*Math.pow(o,Ru)+Eu:Mu*Math.log(o+Du)+Nu}function Sb(o){return o<=vh?o/Pu:o<=bh?Math.pow((o-Eu)/Bu,1/Ru):Math.exp((o-Nu)/Mu)-Du}function Ib(o,e){return e==="log"?Math.log(o+1)*Ih:$h(o)*Th}function $b(o,e){return e==="log"?Math.exp(o*ya)-1:Sb(o*Sh)}function Fu({data:o,channels:e,inputScale:t,transfer:r="pu"}){let i=new Float32Array(o);for(let s=0;s<i.length;s+=e)for(let n=0;n<3;n++)i[s+n]=Ib(Ni(i[s+n]*t,0,Di),r);return i}function ku({data:o,channels:e,inputScale:t,transfer:r="pu"}){let i=new Float32Array(o),s=1/t;for(let n=0;n<i.length;n+=e)for(let l=0;l<3;l++)i[n+l]=$b(Ni(i[n+l],0,Di),r)*s;return i}function Lu({data:o,channels:e}){let t=0;for(let n=0;n<o.length;n+=e){let l=Ni(o[n],0,Di),a=Ni(o[n+1],0,Di),p=Ni(o[n+2],0,Di),u=.212671*l+.71516*a+.072169*p;t+=Math.log2(u+1e-4)}let r=o.length/e,i=t/r;return .18/Math.pow(2,i)}var Ch=`
const a = ${Pu};
const b = ${Bu};
const c = ${Ru};
const d = ${Eu};
const e = ${Mu};
const f = ${Du};
const g = ${Nu};
const y0 =${xh};
const y1 =${yh};
const x0 =${vh};
const x1 =${bh};

const normScale = ${Th};
const rcpNormScale = ${Sh};
`;function Ah(o){return`
${Ch}
const logXMax = ${ya};
const logNormScale = ${Ih};
${o==="log"?"fn HDRForward(y: f32) -> f32 { return log(y + 1.0) * logNormScale; }":`fn HDRForward(y: f32) -> f32 {
  if (y <= y0) { return a * y * normScale; }
  else if (y <= y1) { return (b * pow(y, c) + d) * normScale; }
  else { return (e * log(y + f) + g) * normScale; }
}`}`}function Ph(o){return`
${Ch}
const logXMax = ${ya};
${o==="log"?"fn HDRInverse(x: f32) -> f32 { return exp(x * logXMax) - 1.0; }":`fn HDRInverse(x: f32) -> f32 {
  let y = x * rcpNormScale;
  if (y <= x0) { return y / a; }
  else if (y <= x1) { return pow((y - d) / b, 1 / c); }
  else { return exp((y - g) / e) - f; }
}`}
`}var Fi=class{x;y;width;height;constructor(e,t,r,i){this.x=e,this.y=t,this.width=r,this.height=i}},va=class{_device;_isHDR;_hdrTransfer;_inputPassAux;_inputPassColor;_outputPass;_copyPass;_isInputTexture;constructor(e,t,r="pu"){this._device=e,this._isHDR=t,this._hdrTransfer=r;let i=[{label:"inputScale",type:"f32",data:new Float32Array([1])},{label:"inputSize",type:"vec2i",data:new Int32Array(2)},{label:"outputSize",type:"vec2i",data:new Int32Array(2)},{label:"inputOffset",type:"vec2i",data:new Int32Array(2)}];this._inputPassAux=new kr("inputPassAux",this._device,{inputs:["color","albedo","normal"],outputs:["color","albedo","normal"],uniforms:i,csDefine:"",csMain:""}),this._inputPassColor=new kr("inputPassColor",this._device,{inputs:["color"],outputs:["color"],uniforms:i,csDefine:"",csMain:""}),this._outputPass=new kr("outputPass",this._device,{inputs:["color","raw"],outputs:["color"],uniforms:[{label:"inputScale",type:"f32",data:new Float32Array([1])},{label:"inputSize",type:"vec2i",data:new Int32Array(2)},{label:"outputSize",type:"vec2i",data:new Int32Array(2)},{label:"imageSize",type:"vec2i",data:new Int32Array(2)},{label:"inputOffset",type:"vec2i",data:new Int32Array(2)},{label:"outputOffset",type:"vec2i",data:new Int32Array(2)}],csDefine:"",csMain:""}),this._copyPass=new kr("copyPass",this._device,{inputs:["color"],outputs:["color"],autoUpdateOutputBuffer:!1,uniforms:[{label:"size",type:"vec2i",data:new Int32Array(2)}],csMain:`
let outIdx = i32(globalId.x + globalId.y * u32(size.x));
out_color[outIdx] = textureLoad(in_color, globalId.xy, 0);
`}),this._inputPassAux.setOutputParams({color:{channels:3},albedo:{channels:3},normal:{channels:3}}),this._inputPassColor.setOutputParams({color:{channels:3}}),this._outputPass.setOutputParams({color:{channels:4}})}_updatePasses(e,t=!1){if(this._isInputTexture!=null&&this._isInputTexture===e)return;this._isInputTexture=e;let r=this._isHDR,i=Ah(this._hdrTransfer)+Au;function s(l){return e?`textureLoad(in_${l}, vec2u(inputPosition), 0)`:`in_${l}[inIdx]`}let n=`
let x = i32(globalId.x);
let y = i32(globalId.y);
let inputPosition = clamp(
  vec2i(x, y) + inputOffset,
  vec2i(0),
  inputSize - vec2i(1)
);
let inIdx = inputPosition.y * inputSize.x + inputPosition.x;
let col = ${s("color")};

let outIdx = y * outputSize.x + x;

if (${t}) {
  // Denoise the inversed alpha. Or the anti aliased edge will be too dark after denoised
  out_color[outIdx] = sanitize(vec3f(1.0 - col.a), 0.0, 1.0);
}
else if (${r}) {
  let scaled = sanitize(col.rgb * inputScale, 0.0, FLT_MAX);
  out_color[outIdx] = vec3f(HDRForward(scaled.r), HDRForward(scaled.g), HDRForward(scaled.b));
}
else {
  out_color[outIdx] = sanitize(col.rgb, 0.0, 1.0);
}
`;this._inputPassAux.setCSCode({csDefine:i,csMain:`
${n}
let alb = ${s("albedo")};
let nor = ${s("normal")};
// Normals arrive [0, 1]-encoded, so OIDN's [-1, 1] clamp becomes [0, 1].
out_normal[outIdx] = sanitize(nor.rgb, 0.0, 1.0);
out_albedo[outIdx] = sanitize(alb.rgb, 0.0, 1.0);
  `}),this._inputPassColor.setCSCode({csDefine:i,csMain:`
${n}
`}),this._outputPass.setCSCode({csDefine:Ph(this._hdrTransfer)+Au,csMain:`
let x = i32(globalId.x);
let y = i32(globalId.y);
if (x >= outputSize.x || y >= outputSize.y) {
  return;
}
let inIdx = (y + inputOffset.y) * inputSize.x + x + inputOffset.x;
let outIdx = (y + outputOffset.y) * imageSize.x + x + outputOffset.x;
let col = in_color[inIdx];
let raw = ${e?"textureLoad(in_raw, globalId.xy + vec2u(outputOffset), 0)":"in_raw[outIdx]"};

// The network output may contain negative values or NaNs.
let denoised = sanitize(col.rgb, 0.0, ${r&&!t?"FLT_MAX":"1.0"});
if (${t}) {
  out_color[outIdx] = vec4f(raw.rgb, 1.0 - denoised.r);
}
else if (${r}) {
  out_color[outIdx] = vec4f(
    vec3f(HDRInverse(denoised.r), HDRInverse(denoised.g), HDRInverse(denoised.b)) / inputScale,
    // Pick the alpha
    raw.a
  );
}
else {
  out_color[outIdx] = vec4f(denoised, raw.a);
}
`})}setImageSize(e,t){this._inputPassAux.setUniform("inputSize",new Int32Array([e,t])),this._inputPassColor.setUniform("inputSize",new Int32Array([e,t])),this._outputPass.setUniform("imageSize",new Int32Array([e,t])),this._outputPass.setSize(e,t),this._copyPass.setSize(e,t),this._copyPass.setUniform("size",new Int32Array([e,t]))}setInputTile(e){let t=new Int32Array([e.width,e.height]);[this._inputPassAux,this._inputPassColor].forEach(r=>{r.setUniform("inputOffset",new Int32Array([e.x,e.y])),r.setUniform("outputSize",t),r.setSize(t[0],t[1])}),this._outputPass.setUniform("inputSize",t)}setOutputTile(e,t){let r=this._outputPass,i=new Int32Array([e.width,e.height]),s=e.x-t.x,n=e.y-t.y;r.setUniform("outputSize",i),r.setUniform("inputOffset",new Int32Array([s,n])),r.setUniform("outputOffset",new Int32Array([e.x,e.y])),r.setExecuteSize(i[0],i[1])}forward(e,t,r,i){let s=e instanceof GPUTexture;this._updatePasses(s,i);let n=this._inputPassAux,l=this._inputPassColor,a=this._device.createCommandEncoder();function p(u){return u instanceof GPUTexture?{texture:u,channels:4}:{buffer:u,channels:4}}return t&&r?n.createPass(a,{color:p(e),albedo:p(t),normal:p(r)}):l.createPass(a,{color:p(e)}),this._device.queue.submit([a.finish()]),t&&r?{color:n.getOutput("color"),albedo:n.getOutput("albedo"),normal:n.getOutput("normal")}:{color:l.getOutput("color")}}inverse(e,t){let i=this._device.createCommandEncoder(),s=this._outputPass;return s.createPass(i,{color:{buffer:e,channels:4},raw:t instanceof GPUBuffer?{buffer:t,channels:4}:{texture:t,channels:4}}),this._device.queue.submit([i.finish()]),s.getOutput("color")}copyInputDataToOutput(e){let t=this._device.createCommandEncoder(),i=this._outputPass.getOutput("color"),s=this._copyPass;e instanceof GPUTexture?(s.setOutputBuffers({color:i}),s.createPass(t,{color:{texture:e,channels:4}})):t.copyBufferToBuffer(e,0,i,0,i.size),this._device.queue.submit([t.finish()])}dispose(){this._outputPass.dispose(),this._inputPassAux.dispose(),this._inputPassColor.dispose(),this._copyPass.dispose(!1)}};function ki(o,e){return Math.ceil(o/e)*e}function Rh(o,e){return Math.floor(o/e)*e}function Bh(o,e,t){let r=Math.round(t*o/e),i=Math.round((t+1)*o/e);return{start:r,end:i}}function ba(o,e,t,r=0){let i=e-o,s=Math.max(r,ki(i,16)),n=o-Math.floor((s-i)/2);return n=wa(n,0,Math.max(0,t-s)),{start:n,end:n+s}}function Ta(o,e,t,r){if(!Number.isInteger(o)||o<=0||!Number.isInteger(e)||e<=0)throw new Error("Tile grid dimensions must be positive integers");if(!Number.isFinite(t)||t<=0)throw new Error("Maximum tile size must be positive");if(!Number.isFinite(r)||r<0)throw new Error("Tile overlap must be non-negative");let i=Math.max(16,Rh(t,16)),s=ki(r,16),n=Math.max(1,Math.ceil(o/i)),l=Math.max(1,Math.ceil(e/i)),a=[],p=0,u=0;for(let d=0;d<l;d++){let m=Bh(e,l,d);for(let x=0;x<n;x++){let v=Bh(o,n,x),g={start:x===0?v.start:Math.max(0,v.start-s),end:x===n-1?v.end:Math.min(o,v.end+s)},y={start:d===0?m.start:Math.max(0,m.start-s),end:d===l-1?m.end:Math.min(e,m.end+s)},w=ba(g.start,g.end,o),b=ba(y.start,y.end,e),T={x:v.start,y:m.start,width:v.end-v.start,height:m.end-m.start},_={x:w.start,y:b.start,width:w.end-w.start,height:b.end-b.start};p=Math.max(p,T.width),u=Math.max(u,T.height),a.push({column:x,row:d,input:_,output:T})}}let c=()=>new Set(a.map(({input:d})=>`${d.width}x${d.height}`));if(c().size>2){let d=Math.max(...a.map(({input:g})=>g.width)),m=Math.max(...a.map(({input:g})=>g.height)),x=a.reduce((g,{input:y})=>g+d*y.height,0),v=a.reduce((g,{input:y})=>g+y.width*m,0);if(x<=v)for(let g of a){let y=ba(g.input.x,g.input.x+g.input.width,o,d);g.input.x=y.start,g.input.width=y.end-y.start}else for(let g of a){let y=ba(g.input.y,g.input.y+g.input.height,e,m);g.input.y=y.start,g.input.height=y.end-y.start}}let f=c(),h=a.reduce((d,{input:m})=>d+m.width*m.height,0);return{columns:n,rows:l,overlap:s,maxOutputWidth:p,maxOutputHeight:u,inputPixelCount:h,inputShapeCount:f.size,tiles:a}}function wa(o,e,t){return Math.min(Math.max(o,e),t)}function Cb(o,e){let t=[...o].sort((r,i)=>r-i);return t[Math.ceil(t.length*e)-1]}var _a=class{enabled;maxTileSize;minTileSize;targetTileTimeMs;_tileSize;_adjustmentStep;_smoothedTileTimeMs;_completeExecutionsSinceChange=0;constructor(e,t=!0){let r=typeof t=="object"?t:{};this.enabled=t!==!1,this.maxTileSize=Math.max(16,Rh(e,16)),this.minTileSize=wa(ki(r.minTileSize??256,16),16,this.maxTileSize),this.targetTileTimeMs=Math.max(1,r.targetTileTimeMs??16),this._adjustmentStep=Math.max(16,ki(r.adjustmentStep??16,16)),this._tileSize=this.enabled?wa(ki(r.initialTileSize??432,16),this.minTileSize,this.maxTileSize):this.maxTileSize}get tileSize(){return this._tileSize}observe(e){if(!this.enabled||e.length===0)return!1;let t=e.filter(n=>Number.isFinite(n)&&n>=0);if(t.length===0)return!1;let r=t.length>=3?t.slice(1):t,i=Cb(r,.75);if(this._smoothedTileTimeMs=this._smoothedTileTimeMs===void 0?i:this._smoothedTileTimeMs*.65+i*.35,this._completeExecutionsSinceChange++,this._completeExecutionsSinceChange<2)return!1;let s=this._tileSize;return this._smoothedTileTimeMs>this.targetTileTimeMs*1.25?s-=this._adjustmentStep:this._smoothedTileTimeMs<this.targetTileTimeMs*.65&&(s+=this._adjustmentStep),s=wa(ki(s,16),this.minTileSize,this.maxTileSize),s===this._tileSize?!1:(this._tileSize=s,this._smoothedTileTimeMs=void 0,this._completeExecutionsSinceChange=0,!0)}};function H(o,e){return{op:"conv2d",id:o,input:e,weight:`${o}.weight`,bias:`${o}.bias`,activation:"relu",padding:"same"}}function fr(o,e){return{op:"maxPool2d",id:o,input:e,size:2,stride:2,padding:"same"}}function dr(o,e){return{op:"upsample2d",id:o,input:e,scale:2,mode:"nearest"}}function hr(o,e,t){return{op:"concat",id:o,inputs:[e,t],axis:"channels"}}var Dh={schemaVersion:1,id:"oidn-unet-small-v1",family:"oidn-unet-small",input:"input",output:"dec_conv0",receptiveField:174,nodes:[H("enc_conv0","input"),H("enc_conv1","enc_conv0"),fr("pool1","enc_conv1"),H("enc_conv2","pool1"),fr("pool2","enc_conv2"),H("enc_conv3","pool2"),fr("pool3","enc_conv3"),H("enc_conv4","pool3"),fr("pool4","enc_conv4"),H("enc_conv5a","pool4"),H("enc_conv5b","enc_conv5a"),dr("up4","enc_conv5b"),hr("concat4","up4","pool3"),H("dec_conv4a","concat4"),H("dec_conv4b","dec_conv4a"),dr("up3","dec_conv4b"),hr("concat3","up3","pool2"),H("dec_conv3a","concat3"),H("dec_conv3b","dec_conv3a"),dr("up2","dec_conv3b"),hr("concat2","up2","pool1"),H("dec_conv2a","concat2"),H("dec_conv2b","dec_conv2a"),dr("up1","dec_conv2b"),hr("concat1","up1","input"),H("dec_conv1a","concat1"),H("dec_conv1b","dec_conv1a"),H("dec_conv0","dec_conv1b")]},Nh={schemaVersion:1,id:"oidn-unet-large-v1",family:"oidn-unet-large",input:"input",output:"dec_conv1c",receptiveField:202,nodes:[H("enc_conv1a","input"),H("enc_conv1b","enc_conv1a"),fr("pool1","enc_conv1b"),H("enc_conv2a","pool1"),H("enc_conv2b","enc_conv2a"),fr("pool2","enc_conv2b"),H("enc_conv3a","pool2"),H("enc_conv3b","enc_conv3a"),fr("pool3","enc_conv3b"),H("enc_conv4a","pool3"),H("enc_conv4b","enc_conv4a"),fr("pool4","enc_conv4b"),H("enc_conv5a","pool4"),H("enc_conv5b","enc_conv5a"),dr("up4","enc_conv5b"),hr("concat4","up4","pool3"),H("dec_conv4a","concat4"),H("dec_conv4b","dec_conv4a"),dr("up3","dec_conv4b"),hr("concat3","up3","pool2"),H("dec_conv3a","concat3"),H("dec_conv3b","dec_conv3a"),dr("up2","dec_conv3b"),hr("concat2","up2","pool1"),H("dec_conv2a","concat2"),H("dec_conv2b","dec_conv2a"),dr("up1","dec_conv2b"),hr("concat1","up1","input"),H("dec_conv1a","concat1"),H("dec_conv1b","dec_conv1a"),H("dec_conv1c","dec_conv1b")]},Ab=[Dh,Nh];function Fh(o){let e=new Set;for(let t of o.nodes)t.op==="conv2d"&&(e.add(t.weight),e.add(t.bias));return e}function Eh(o){return o.desc.getByteSize()}function kh(o){return[...o].sort().join(", ")}function Sa(o,e=Ab){let t=e.filter(r=>{let i=Fh(r);return[...i].some(s=>!o.has(s))?!1:r.allowAdditionalTensors===!0||[...o.keys()].every(s=>i.has(s))});if(t.length===1)return t[0];throw t.length>1?new Error(`Ambiguous OIDN model topology: ${t.map(r=>r.id).join(", ")}`):new Error(`Unsupported OIDN model topology. TZA tensors: ${kh([...o.keys()])}`)}function Mh(o,e,t){let r=o.get(e);if(!r)throw new Error(`Model ${t} is missing tensor ${e}`);if(r.data.byteLength!==Eh(r))throw new Error(`Tensor ${e} has ${r.data.byteLength} bytes, expected ${Eh(r)}`);return r}function Ou(o,e=Sa(o)){if(e.schemaVersion!==1)throw new Error(`Unsupported model descriptor schema ${e.schemaVersion}`);let t=Fh(e);if(!e.allowAdditionalTensors){let c=[...o.keys()].filter(f=>!t.has(f));if(c.length>0)throw new Error(`Model ${e.id} has unexpected tensors: ${kh(c)}`)}let r=new Map,i=new Map,s=new Map,n=new Set([e.input]),l,a,p=(c,f)=>{let h=r.get(c);if(h===void 0)throw new Error(`Model ${e.id} node ${f} reads unknown or forward value ${c}`);return h};for(let c of e.nodes){if(n.has(c.id))throw new Error(`Model ${e.id} produces duplicate value ${c.id}`);if(c.op==="conv2d"){let f=Mh(o,c.weight,e.id),h=Mh(o,c.bias,e.id),d=f.desc.dims;if(f.desc.layout!=="oihw"||d.length!==4)throw new Error(`Tensor ${c.weight} must use OIHW layout`);if(d[2]!==3||d[3]!==3)throw new Error(`Tensor ${c.weight} must use a 3x3 kernel`);if(h.desc.layout!=="x"||h.desc.dims.length!==1)throw new Error(`Tensor ${c.bias} must be a one-dimensional bias`);if(h.desc.dims[0]!==d[0])throw new Error(`Tensor ${c.bias} has ${h.desc.dims[0]} channels, expected ${d[0]}`);if(f.desc.dataType!==h.desc.dataType)throw new Error(`Weight and bias dtype differ for ${c.id}`);if(a&&a!==f.desc.dataType)throw new Error(`Mixed tensor dtypes are not supported by model ${e.id}`);a=f.desc.dataType,c.input===e.input&&l===void 0&&(l=d[1],r.set(e.input,l));let m=p(c.input,c.id);if(m!==d[1])throw new Error(`Tensor ${c.weight} expects ${d[1]} input channels, but ${c.input} provides ${m}`);r.set(c.id,d[0]),i.set(c.id,{weight:f,bias:h,inputChannels:d[1],outputChannels:d[0],kernelHeight:d[2],kernelWidth:d[3]}),s.set(c.id,{inputChannels:d[1],outputChannels:d[0]})}else if(c.op==="concat"){if(c.inputs.length<2)throw new Error(`Concat ${c.id} requires at least two inputs`);let f=c.inputs.reduce((h,d)=>h+p(d,c.id),0);r.set(c.id,f)}else r.set(c.id,p(c.input,c.id));n.add(c.id)}if(l===void 0||a===void 0)throw new Error(`Model ${e.id} has no convolution reading its input`);let u=r.get(e.output);if(u===void 0)throw new Error(`Model ${e.id} output ${e.output} is not produced`);if(u!==3)throw new Error(`Model ${e.id} must produce 3 channels, got ${u}`);return{spec:e,inputChannels:l,outputChannels:u,tensorDataType:a,channelsByValue:r,convChannels:s,convTensors:i}}function Pb(o){return o.op==="concat"?o.inputs:[o.input]}function Bb(o){let e=new Map;for(let t of o.nodes)for(let r of Pb(t)){let i=e.get(r)??[];i.push(t),e.set(r,i)}return e}function Uu(o,e,t){let r=o.get(e);if(!(r===void 0||r.length!==1||r[0].op!==t))return r[0]}function Li(o,e={}){let t=o.spec,r=Bb(t),i=new Map(t.nodes.map(u=>[u.id,u])),s=new Set,n=new Map,l=0,a=0;if(e.fuseConvPool!==!1)for(let u of t.nodes){if(u.op!=="conv2d"||u.activation!=="relu")continue;let c=Uu(r,u.id,"maxPool2d");!c||c.op!=="maxPool2d"||c.size!==2||c.stride!==2||(s.add(u.id),n.set(c.id,{op:"fusedConvReluMaxPool2d",id:c.id,input:u.input,conv:u,pool:c}),l++)}if(e.fuseUpsampleConcatConv!==!1)for(let u of t.nodes){if(u.op!=="conv2d")continue;let c=i.get(u.input);if(c?.op!=="concat"||c.inputs.length!==2||Uu(r,c.id,"conv2d")?.id!==u.id)continue;let f=o.channelsByValue.get(c.inputs[0]);if(f===void 0||f%4!==0)continue;let h=c.inputs.map(m=>{let x=i.get(m);return x?.op==="upsample2d"&&x.scale===2&&x.mode==="nearest"&&Uu(r,x.id,"concat")?.id===c.id?{value:x.input,upsample:x}:{value:m}});if(h.filter(m=>m.upsample).length===1){s.add(c.id);for(let m of c.inputs){let x=i.get(m);x?.op==="upsample2d"&&s.add(x.id)}n.set(u.id,{op:"fusedUpsampleConcatConv2d",id:u.id,inputs:h,conv:u}),a++}}let p=[];for(let u of t.nodes){let c=n.get(u.id);c?p.push(c):s.has(u.id)||p.push(u)}return{spec:t,nodes:p,fusions:{convPool:l,upsampleConcatConv:a}}}function Rb(o){return o.op==="concat"?o.inputs:o.op==="fusedUpsampleConcatConv2d"?o.inputs.map(e=>e.value):[o.input]}function Lh(o,e){return o.width===e.width&&o.height===e.height}function zu(o,e,t,r){if(!Number.isInteger(e)||e<=0||!Number.isInteger(t)||t<=0)throw new Error(`Invalid model input size ${e}x${t}`);let i=Li(o,r),s={width:e,height:t,channels:o.inputChannels},n=new Map([[o.spec.input,s]]),l=[],a=(c,f)=>{let h=n.get(c);if(!h)throw new Error(`Planned node ${f} reads missing value ${c}`);return h};for(let c of i.nodes){let f;if(c.op==="conv2d"){let h=a(c.input,c.id);f={width:h.width,height:h.height,channels:o.convChannels.get(c.id).outputChannels}}else if(c.op==="maxPool2d"){let h=a(c.input,c.id);f={width:Math.ceil(h.width/2),height:Math.ceil(h.height/2),channels:h.channels}}else if(c.op==="upsample2d"){let h=a(c.input,c.id);f={width:h.width*2,height:h.height*2,channels:h.channels}}else if(c.op==="concat"){let h=c.inputs.map(d=>a(d,c.id));if(h.some(d=>!Lh(d,h[0])))throw new Error(`Concat ${c.id} has mismatched spatial shapes`);f={width:h[0].width,height:h[0].height,channels:h.reduce((d,m)=>d+m.channels,0)}}else if(c.op==="fusedConvReluMaxPool2d"){let h=a(c.input,c.id);f={width:Math.ceil(h.width/2),height:Math.ceil(h.height/2),channels:o.convChannels.get(c.conv.id).outputChannels}}else{let h=c.inputs.map(d=>{let m=a(d.value,c.id);return d.upsample?{...m,width:m.width*2,height:m.height*2}:m});if(h.some(d=>!Lh(d,h[0])))throw new Error(`Fused decoder ${c.id} has mismatched spatial shapes`);f={width:h[0].width,height:h[0].height,channels:o.convChannels.get(c.conv.id).outputChannels}}n.set(c.id,f),l.push(f)}let p=new Map;i.nodes.forEach((c,f)=>{for(let h of Rb(c))p.set(h,f)}),p.set(o.spec.output,i.nodes.length);let u=i.nodes.map((c,f)=>({node:c,outputShape:l[f],lastUse:p.get(c.id)??f}));return{...i,inputShape:s,valueShapes:n,plannedNodes:u}}var Ie={MAP_READ:1,MAP_WRITE:2,COPY_SRC:4,COPY_DST:8,INDEX:16,VERTEX:32,UNIFORM:64,STORAGE:128,INDIRECT:256,QUERY_RESOLVE:512},Hu={READ:1,WRITE:2};function Wu(o,e,t=!1){let r=o==="fp16"?2:4,i=100*e,s=t?9*e*4:0;return(i+s)*4*r}function Eb(o){return o==="fp16"?"vec4<f16>":"vec4<f32>"}function Mb(o){return o==="fp16"?`enable f16;
`:""}function Db(o,e,t){let r=i=>`${t}[weightBase + ${i}u]`;return o==="fp16"?`
        let inputValue = vec4<f16>(${e});
        var partial = vec4<f16>(0.0h);
        partial = fma(${r(0)}, vec4<f16>(inputValue.x), partial);
        partial = fma(${r(1)}, vec4<f16>(inputValue.y), partial);
        partial = fma(${r(2)}, vec4<f16>(inputValue.z), partial);
        partial = fma(${r(3)}, vec4<f16>(inputValue.w), partial);
        acc += vec4<f32>(partial);
`:`
        let inputValue = vec4<f32>(${e});
        acc = fma(vec4<f32>(${r(0)}), vec4<f32>(inputValue.x), acc);
        acc = fma(vec4<f32>(${r(1)}), vec4<f32>(inputValue.y), acc);
        acc = fma(vec4<f32>(${r(2)}), vec4<f32>(inputValue.z), acc);
        acc = fma(vec4<f32>(${r(3)}), vec4<f32>(inputValue.w), acc);
`}function Oh(o,e,t,r=!1){if(!Number.isInteger(t)||t<1)throw new Error(`Final RGB shader requires positive input blocks, got ${t}`);let i=Eb(o),s="vec4<f32>",n=100*t,l=9*t*4,a=e==="relu"?"max(acc, vec4<f32>(0.0))":"acc",p=Db(o,"inputTile[patchBase + inputBlock]",r?"weightTile":"weights");return`${Mb(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}

@group(0) @binding(0) var<storage, read> inputData: array<${i}>;
@group(0) @binding(1) var<storage, read> weights: array<${i}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${s}>;
@group(0) @binding(4) var<uniform> params: Params;

var<workgroup> inputTile: array<${i}, ${n}>;
${r?`var<workgroup> weightTile: array<${i}, ${l}>;`:""}

@compute @workgroup_size(8, 8, 1)
fn main(
  @builtin(local_invocation_id) localId: vec3<u32>,
  @builtin(global_invocation_id) gid: vec3<u32>,
  @builtin(workgroup_id) workgroupId: vec3<u32>
) {
  let localLinear = localId.y * 8u + localId.x;
  for (
    var loadIndex = localLinear;
    loadIndex < ${n}u;
    loadIndex += 64u
  ) {
    let tilePixel = loadIndex / ${t}u;
    let inputBlock = loadIndex % ${t}u;
    let tileX = tilePixel % 10u;
    let tileY = tilePixel / 10u;
    let inputX = i32(workgroupId.x * 8u + tileX) - 1;
    let inputY = i32(workgroupId.y * 8u + tileY) - 1;
    var value = ${i}(0.0);
    if (
      inputX >= 0 && inputX < i32(params.inputWidth) &&
      inputY >= 0 && inputY < i32(params.inputHeight)
    ) {
      let inputIndex =
        (u32(inputY) * params.inputWidth + u32(inputX)) *
        ${t}u + inputBlock;
      value = inputData[inputIndex];
    }
    inputTile[loadIndex] = value;
  }

${r?`  for (
    var loadIndex = localLinear;
    loadIndex < ${l}u;
    loadIndex += 64u
  ) {
    weightTile[loadIndex] = weights[loadIndex];
  }

`:""}  // Out-of-range invocations must reach this barrier before returning.
  workgroupBarrier();

  let outputInBounds =
    gid.x < params.outputWidth &&
    gid.y < params.outputHeight &&
    gid.z < params.outputBlocks;
  if (!outputInBounds) {
    return;
  }

  var acc = bias[gid.z];
  for (var ky = 0u; ky < 3u; ky++) {
    let inputY = i32(gid.y) + i32(ky) - 1;
    if (inputY < 0 || inputY >= i32(params.inputHeight)) {
      continue;
    }
    for (var kx = 0u; kx < 3u; kx++) {
      let inputX = i32(gid.x) + i32(kx) - 1;
      if (inputX < 0 || inputX >= i32(params.inputWidth)) {
        continue;
      }
      let patchBase =
        ((localId.y + ky) * 10u + localId.x + kx) *
        ${t}u;
      for (var inputBlock = 0u; inputBlock < ${t}u; inputBlock++) {
        let weightBase =
          ((ky * 3u + kx) * ${t}u + inputBlock) * 4u;
        ${p}
      }
    }
  }

  let outputIndex =
    (gid.y * params.outputWidth + gid.x) * 1u + gid.z;
  outputData[outputIndex] = ${a};
}
`}var Oi=class{_stats=new Map;track(e,t){let r=this._stats.get(e);return r||(r={created:0,destroyed:0,live:0,peakLive:0,resources:new Set},this._stats.set(e,r)),r.resources.has(t)||(r.resources.add(t),r.created++,r.live++,r.peakLive=Math.max(r.peakLive,r.live)),t}release(e,t,r){if(!t)return!1;let i=this._stats.get(e);if(!i?.resources.delete(t))return!1;try{r()}finally{i.destroyed++,i.live--}return!0}snapshot(e=0){let t=0,r=0,i=0,s=0,n={};for(let[l,a]of this._stats){let p={created:a.created,destroyed:a.destroyed,live:a.live,peakLive:a.peakLive};n[l]=p,t+=p.live,r+=p.created,i+=p.destroyed,s+=p.peakLive}return{live:t,created:r,destroyed:i,peakLive:s,pending:e,byKind:n}}};var Uh=new WeakMap;function Nb(o){let e=Uh.get(o);return e||(e=new Map,Uh.set(o,e)),e}var ye=8,Vh=8,fe=8,rt=fe+2;function it(o){let[e,t]=o.workgroupSize;return{workgroupX:e,workgroupY:t,rowsPerThread:o.rowsPerThread,tileM:t*o.rowsPerThread,tileNBlocks:e,tileKBlocks:Vh}}function Yt(o){let e=it(o),t=o.sharedLayout==="padded"||o.sharedLayout==="padded-input",r=o.sharedLayout==="padded"||o.sharedLayout==="padded-weights";return{input:e.tileM*e.tileKBlocks+(t?e.workgroupY:0),weights:e.tileKBlocks*e.tileNBlocks*(r?5:4)}}function Wi(o,e){return e.sharedLayout==="padded"||e.sharedLayout==="padded-input"?`(${o}) + (${o}) / ${e.rowsPerThread*Vh}u`:o}function Gu(o,e){return e.sharedLayout==="padded"||e.sharedLayout==="padded-weights"?`(${o}) + (${o}) / 4u`:o}function qu(o,e){return Math.ceil(o/e)*e}function Q(o){return Math.ceil(o/4)}function Fb(o,e){return o.width*o.height*Q(o.channels)*4*e}var On;function Gh(o){let e=o&32768?-1:1,t=o>>>10&31,r=o&1023;return t===0?e*r*2**-24:t===31?r===0?e*(1/0):NaN:e*(1+r/1024)*2**(t-15)}var zh=new DataView(new ArrayBuffer(4));function qh(o){zh.setFloat32(0,o);let e=zh.getUint32(0),t=e>>>16&32768,r=e>>>23&255,i=e&8388607;if(r===255)return t|31744|(i?512:0);let s=r-127+15;if(s>=31)return t|31744;if(s<=0){if(s<-10)return t;let a=i|8388608,p=14-s,u=a>>>p,c=a&(1<<p)-1,f=1<<p-1,h=c>f||c===f&&u&1?u+1:u;return t|h}let n=s<<10|i>>>13,l=i&8191;return t|(l>4096||l===4096&&n&1?n+1:n)}function kb(){if(!On){On=new Float32Array(65536);for(let o=0;o<On.length;o++)On[o]=Gh(o)}return On}function Ca(o){if(o.desc.dataType==="Float32")return new Float32Array(o.data.buffer,o.data.byteOffset,o.data.byteLength/4);let e=new Uint16Array(o.data.buffer,o.data.byteOffset,o.data.byteLength/2),t=new Float32Array(e.length);if(e.length<4096)for(let r=0;r<e.length;r++)t[r]=Gh(e[r]);else{let r=kb();for(let i=0;i<e.length;i++)t[i]=r[e[i]]}return t}function zn(o,e,t,r){let i=qu(t.byteLength,4),s=o.createBuffer({label:e,size:i,usage:r,mappedAtCreation:!0});return new Uint8Array(s.getMappedRange()).set(t),s.unmap(),s}function Hh(o,e,t){let r=new Uint32Array(qu(t.length,4));return r.set(t),zn(o,e,new Uint8Array(r.buffer,r.byteOffset,r.byteLength),Ie.UNIFORM)}function Lb(o,e,t,r,i){let s=Q(t.inputChannels),n=Q(t.outputChannels),l=n*t.kernelHeight*t.kernelWidth*s*4*4,a=r==="fp16"&&t.weight.desc.dataType==="Float16",p=r==="fp16"?new Uint16Array(l):void 0,u=p?void 0:new Float32Array(l),c=a?new Uint16Array(t.weight.data.buffer,t.weight.data.byteOffset,t.weight.data.byteLength/2):void 0,f=c?void 0:Ca(t.weight);for(let m=0;m<n;m++)for(let x=0;x<t.kernelHeight;x++)for(let v=0;v<t.kernelWidth;v++)for(let g=0;g<s;g++)for(let y=0;y<4;y++){let w=m*4+y;for(let b=0;b<4;b++){let T=g*4+b,S=(i==="k-major"?(((x*t.kernelWidth+v)*s+g)*n+m)*16:(((m*t.kernelHeight+x)*t.kernelWidth+v)*s+g)*16)+b*4+y;if(w<t.outputChannels&&T<t.inputChannels){let A=((w*t.inputChannels+T)*t.kernelHeight+x)*t.kernelWidth+v;p?p[S]=c?c[A]:qh(f[A]):u[S]=f[A]}}}let h=new Float32Array(n*4);h.set(Ca(t.bias));let d=zn(o,`oidn/${e}/weights/${r}/${i}`,p?new Uint8Array(p.buffer):new Uint8Array(u.buffer),Ie.STORAGE);try{return{weights:d,weightLayout:i,bias:zn(o,`oidn/${e}/bias`,new Uint8Array(h.buffer),Ie.STORAGE)}}catch(m){throw d.destroy(),m}}function le(o){return o==="fp16"?"vec4<f16>":"vec4<f32>"}function ht(o){return o==="fp16"?`enable f16;
`:""}function Ue(o,e){return e==="fp16"?`vec4<f16>(${o})`:o}function nt(o,e){return e==="relu"?`max(${o}, vec4<f32>(0.0))`:o}function Hn(o,e,t){return t==="fp32"?`
let inputValue = vec4<f32>(${o});
let weightBase = ${e};
acc = fma(vec4<f32>(weights[weightBase]), vec4<f32>(inputValue.x), acc);
acc = fma(vec4<f32>(weights[weightBase + 1u]), vec4<f32>(inputValue.y), acc);
acc = fma(vec4<f32>(weights[weightBase + 2u]), vec4<f32>(inputValue.z), acc);
acc = fma(vec4<f32>(weights[weightBase + 3u]), vec4<f32>(inputValue.w), acc);
`:`
let inputValue = vec4<f16>(${o});
let weightBase = ${e};
var partial = vec4<f16>(0.0h);
partial = fma(weights[weightBase], vec4<f16>(inputValue.x), partial);
partial = fma(weights[weightBase + 1u], vec4<f16>(inputValue.y), partial);
partial = fma(weights[weightBase + 2u], vec4<f16>(inputValue.z), partial);
partial = fma(weights[weightBase + 3u], vec4<f16>(inputValue.w), partial);
acc += vec4<f32>(partial);
`}function Ob(o,e,t){let r=t==="fp16"?"vec4<f16>":"vec4<f32>",i=t==="fp16"?`var partial = vec4<f16>(0.0h);
partial = fma(subgroupBroadcast(weights[weightBase], 0u), ${r}(inputValue.x), partial);
partial = fma(subgroupBroadcast(weights[weightBase + 1u], 0u), ${r}(inputValue.y), partial);
partial = fma(subgroupBroadcast(weights[weightBase + 2u], 0u), ${r}(inputValue.z), partial);
partial = fma(subgroupBroadcast(weights[weightBase + 3u], 0u), ${r}(inputValue.w), partial);
acc += vec4<f32>(partial);`:`acc = fma(subgroupBroadcast(weights[weightBase], 0u), vec4<f32>(inputValue.x), acc);
acc = fma(subgroupBroadcast(weights[weightBase + 1u], 0u), vec4<f32>(inputValue.y), acc);
acc = fma(subgroupBroadcast(weights[weightBase + 2u], 0u), vec4<f32>(inputValue.z), acc);
acc = fma(subgroupBroadcast(weights[weightBase + 3u], 0u), vec4<f32>(inputValue.w), acc);`;return`
let inputValue = ${r}(${o});
let weightBase = ${e};
${i}
`}function Ui(o,e,t){return o==="fp16"&&(e.loadMode==="packed-all"||t&&e.loadMode==="packed-weights")}function Lr(o,e){return e?`bitcast<vec4<f16>>(${o})`:o}function Xu(o,e){let{rowsPerThread:t,tileNBlocks:r,tileKBlocks:i}=it(e);if(!e.unrollRows||e.accumulationOrder==="row-major")return Ub(o,e);let s=`(tileK * ${r}u + localId.x) * ${e.sharedLayout==="padded"||e.sharedLayout==="padded-weights"?5:4}u`,n=Array.from({length:t},(a,p)=>p),l=le(o);return`
    ${o==="fp16"?n.map(a=>`var partial${a} = vec4<f16>(0.0h);`).join(`
    `):""}
    for (var tileK = 0u; tileK < ${i}u; tileK++) {
      let weightBase = ${s};
      let w0 = weightTile[weightBase];
      let w1 = weightTile[weightBase + 1u];
      let w2 = weightTile[weightBase + 2u];
      let w3 = weightTile[weightBase + 3u];
      ${n.map(a=>{let p=Wi(`(localId.y * ${t}u + ${a}u) * ${i}u + tileK`,e),u=o==="fp16"?`partial${a}`:`acc[${a}]`;return`let v${a} = inputTile[${p}];
      `+["x","y","z","w"].map((c,f)=>`${u} = fma(w${f}, ${l}(v${a}.${c}), ${u});`).join(`
      `)}).join(`
      `)}
    }
    ${o==="fp16"?n.map(a=>`acc[${a}] += vec4<f32>(partial${a});`).join(`
    `):""}
`}function Ub(o,e){let{rowsPerThread:t,tileNBlocks:r,tileKBlocks:i}=it(e),s=Wi(`tileSpatial * ${i}u + tileK`,e),n=`(tileK * ${r}u + localId.x) * ${e.sharedLayout==="padded"||e.sharedLayout==="padded-weights"?5:4}u`;if(e.accumulationOrder==="row-major"){let l=le(o),a=o==="fp16"?"partial":"acc[row]";return`
    for (var row = 0u; row < ${t}u; row++) {
      ${o==="fp16"?"var partial = vec4<f16>(0.0h);":""}
      let tileSpatial = localId.y * ${t}u + row;
      for (var tileK = 0u; tileK < ${i}u; tileK++) {
        let weightBase = ${n};
        let inputValue = inputTile[${s}];
        ${a} = fma(weightTile[weightBase], ${l}(inputValue.x), ${a});
        ${a} = fma(weightTile[weightBase + 1u], ${l}(inputValue.y), ${a});
        ${a} = fma(weightTile[weightBase + 2u], ${l}(inputValue.z), ${a});
        ${a} = fma(weightTile[weightBase + 3u], ${l}(inputValue.w), ${a});
      }
      ${o==="fp16"?"acc[row] += vec4<f32>(partial);":""}
    }
`}return o==="fp16"?`
    var partial: array<vec4<f16>, ${t}>;
    for (var row = 0u; row < ${t}u; row++) {
      partial[row] = vec4<f16>(0.0h);
    }
    for (var tileK = 0u; tileK < ${i}u; tileK++) {
      let weightBase =
        ${n};
      for (var row = 0u; row < ${t}u; row++) {
        let tileSpatial =
          localId.y * ${t}u + row;
        let inputValue =
          inputTile[${s}];
        partial[row] = fma(
          weightTile[weightBase],
          vec4<f16>(inputValue.x),
          partial[row]
        );
        partial[row] = fma(
          weightTile[weightBase + 1u],
          vec4<f16>(inputValue.y),
          partial[row]
        );
        partial[row] = fma(
          weightTile[weightBase + 2u],
          vec4<f16>(inputValue.z),
          partial[row]
        );
        partial[row] = fma(
          weightTile[weightBase + 3u],
          vec4<f16>(inputValue.w),
          partial[row]
        );
      }
    }
    for (var row = 0u; row < ${t}u; row++) {
      acc[row] += vec4<f32>(partial[row]);
    }
`:`
    for (var tileK = 0u; tileK < ${i}u; tileK++) {
      let weightBase =
        ${n};
      for (var row = 0u; row < ${t}u; row++) {
        let tileSpatial =
          localId.y * ${t}u + row;
        let inputValue =
          inputTile[${s}];
        acc[row] = fma(
          weightTile[weightBase],
          vec4<f32>(inputValue.x),
          acc[row]
        );
        acc[row] = fma(
          weightTile[weightBase + 1u],
          vec4<f32>(inputValue.y),
          acc[row]
        );
        acc[row] = fma(
          weightTile[weightBase + 2u],
          vec4<f32>(inputValue.z),
          acc[row]
        );
        acc[row] = fma(
          weightTile[weightBase + 3u],
          vec4<f32>(inputValue.w),
          acc[row]
        );
      }
    }
`}function zb(o,e,t,r,i){let s=le(o),n=le(o),l=le(e),a=Ue(nt("acc",t),e);return`${ht(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}

@group(0) @binding(0) var<storage, read> inputData: array<${s}>;
@group(0) @binding(1) var<storage, read> weights: array<${n}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${l}>;
@group(0) @binding(4) var<uniform> params: Params;

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.outputWidth || gid.y >= params.outputHeight || gid.z >= ${i}u) {
    return;
  }
  var acc = bias[gid.z];
  for (var ky = 0u; ky < 3u; ky++) {
    let inputY = i32(gid.y) + i32(ky) - 1;
    if (inputY < 0 || inputY >= i32(params.inputHeight)) { continue; }
    for (var kx = 0u; kx < 3u; kx++) {
      let inputX = i32(gid.x) + i32(kx) - 1;
      if (inputX < 0 || inputX >= i32(params.inputWidth)) { continue; }
      let pixelBase = (u32(inputY) * params.inputWidth + u32(inputX)) * ${r}u;
      for (var inputBlock = 0u; inputBlock < ${r}u; inputBlock++) {
        ${Hn("inputData[pixelBase + inputBlock]",`((((gid.z * 3u + ky) * 3u + kx) * ${r}u + inputBlock) * 4u)`,o)}
      }
    }
  }
  let outputIndex = (gid.y * params.outputWidth + gid.x) * ${i}u + gid.z;
  outputData[outputIndex] = ${a};
}
`}function Hb(o,e,t,r,i){let s=le(o),n=le(o),l=le(e),a=Ue(nt("acc",t),e);return`${ht(o)}
enable subgroups;
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${s}>;
@group(0) @binding(1) var<storage, read> weights: array<${n}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${l}>;
@group(0) @binding(4) var<uniform> params: Params;

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  let outputInBounds =
    gid.x < params.outputWidth && gid.y < params.outputHeight;
  var acc = bias[gid.z];
  for (var ky = 0u; ky < 3u; ky++) {
    let inputY = i32(gid.y) + i32(ky) - 1;
    let clampedY = u32(clamp(inputY, 0, i32(params.inputHeight) - 1));
    for (var kx = 0u; kx < 3u; kx++) {
      let inputX = i32(gid.x) + i32(kx) - 1;
      let clampedX = u32(clamp(inputX, 0, i32(params.inputWidth) - 1));
      let inputInBounds =
        outputInBounds && inputX >= 0 && inputY >= 0 &&
        inputX < i32(params.inputWidth) && inputY < i32(params.inputHeight);
      let pixelBase =
        (clampedY * params.inputWidth + clampedX) * ${r}u;
      for (var inputBlock = 0u; inputBlock < ${r}u; inputBlock++) {
        ${Ob(`select(${s}(0.0), inputData[pixelBase + inputBlock], inputInBounds)`,`((((gid.z * 3u + ky) * 3u + kx) * ${r}u + inputBlock) * 4u)`,o)}
      }
    }
  }
  if (outputInBounds) {
    let outputIndex =
      (gid.y * params.outputWidth + gid.x) * ${i}u + gid.z;
    outputData[outputIndex] = ${a};
  }
}
`}function Wb(o,e,t,r,i){let s=le(o),n=le(e),l=Ue(nt("acc",t),e),a=rt*rt*r,p=fe*fe;return`${ht(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${s}>;
@group(0) @binding(1) var<storage, read> weights: array<${s}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${n}>;
@group(0) @binding(4) var<uniform> params: Params;

var<workgroup> inputPatch: array<${s}, ${a}>;

@compute @workgroup_size(${fe}, ${fe}, 1)
fn main(
  @builtin(local_invocation_id) localId: vec3<u32>,
  @builtin(workgroup_id) workgroupId: vec3<u32>
) {
  let localLinear =
    localId.y * ${fe}u + localId.x;
  for (
    var loadIndex = localLinear;
    loadIndex < ${a}u;
    loadIndex += ${p}u
  ) {
    let patchPixel = loadIndex / ${r}u;
    let inputBlock = loadIndex % ${r}u;
    let patchX = patchPixel % ${rt}u;
    let patchY = patchPixel / ${rt}u;
    let inputX =
      i32(workgroupId.x * ${fe}u + patchX) - 1;
    let inputY =
      i32(workgroupId.y * ${fe}u + patchY) - 1;
    var value = ${s}(0.0);
    if (
      inputX >= 0 && inputX < i32(params.inputWidth) &&
      inputY >= 0 && inputY < i32(params.inputHeight)
    ) {
      let inputIndex =
        (u32(inputY) * params.inputWidth + u32(inputX)) *
        ${r}u + inputBlock;
      value = inputData[inputIndex];
    }
    inputPatch[loadIndex] = value;
  }
  workgroupBarrier();

  let outputX =
    workgroupId.x * ${fe}u + localId.x;
  let outputY =
    workgroupId.y * ${fe}u + localId.y;
  let outputBlock = workgroupId.z;
  if (
    outputX >= params.outputWidth || outputY >= params.outputHeight ||
    outputBlock >= ${i}u
  ) {
    return;
  }

  var acc = bias[outputBlock];
  for (var ky = 0u; ky < 3u; ky++) {
    for (var kx = 0u; kx < 3u; kx++) {
      let patchBase =
        ((localId.y + ky) * ${rt}u + localId.x + kx) *
        ${r}u;
      for (var inputBlock = 0u; inputBlock < ${r}u; inputBlock++) {
        ${Hn("inputPatch[patchBase + inputBlock]",`((((outputBlock * 3u + ky) * 3u + kx) * ${r}u + inputBlock) * 4u)`,o)}
      }
    }
  }
  let outputIndex =
    (outputY * params.outputWidth + outputX) * ${i}u + outputBlock;
  outputData[outputIndex] = ${l};
}
`}function Xh(o,e,t,r){let{workgroupX:i,workgroupY:s,tileM:n,tileKBlocks:l}=it(t),a=e?"params.outputWidth":"params.inputWidth",p=e?"params.outputHeight":"params.inputHeight",u=t.addressMode==="base-offset",c=r?r.blocks.map((h,d)=>`cachedBase${d}[load] = (${d===r.upsampled?"y / 2u":"y"} * params.source${d}Width + ${d===r.upsampled?"x / 2u":"x"}) * ${h}u;`).join(`
    `):`cachedBase0[load] = (y * params.inputWidth + x) * ${o}u;`,f=n*l/(i*s);return`
  ${u?`var cachedBase0: array<u32, ${f}>;
  ${r?`var cachedBase1: array<u32, ${f}>;`:""}`:`var cachedX: array<u32, ${f}>;
  var cachedY: array<u32, ${f}>;`}
  var cachedMask: array<u32, ${f}>;
  for (var load = 0u; load < ${f}u; load++) {
    let loadIndex = localLinear + load * ${i*s}u;
    let spatial = workgroupId.x * ${n}u + loadIndex / ${l}u;
    let x = spatial % params.outputWidth;
    let y = spatial / params.outputWidth;
    ${u?c:`cachedX[load] = x;
    cachedY[load] = y;`}
    let columns =
      select(0u, 0x049u, x > 0u && x - 1u < ${a}) |
      select(0u, 0x092u, x < ${a}) |
      select(0u, 0x124u, x + 1u < ${a});
    let rows =
      select(0u, 0x007u, y > 0u && y - 1u < ${p}) |
      select(0u, 0x038u, y < ${p}) |
      select(0u, 0x1c0u, y + 1u < ${p});
    cachedMask[load] = select(0u, columns & rows, spatial < spatialCount)${u&&r?" | ((x & 1u) << 9u) | ((y & 1u) << 10u)":""};
  }
  var channelBlock = (localLinear % ${l}u) % ${o}u;
  var filterPosition = (localLinear % ${l}u) / ${o}u;
`}function Yh(o,e){let{tileKBlocks:t}=it(e);return`
    channelBlock += ${t%o}u;
    let carry = channelBlock >= ${o}u;
    channelBlock -= select(0u, ${o}u, carry);
    filterPosition += ${Math.floor(t/o)}u + select(0u, 1u, carry);
`}function $a(o,e,t){let{workgroupX:r,workgroupY:i,tileM:s,tileKBlocks:n}=it(t);return`
    let inputBlock = channelBlock;
    let kernelY = filterPosition / 3u;
    let kernelX = filterPosition % 3u;
    for (var load = 0u; load < ${s*n/(r*i)}u; load++) {
      let loadIndex = localLinear + load * ${r*i}u;
      var value = ${o}(0.0);
      if (filterPosition < 9u && (cachedMask[load] & (1u << filterPosition)) != 0u) {
        ${t.addressMode==="base-offset"?"":`let inputX = i32(cachedX[load]) + i32(kernelX) - 1;
        let inputY = i32(cachedY[load]) + i32(kernelY) - 1;`}
        ${e}
      }
      inputTile[${Wi("loadIndex",t)}] = value;
    }
`}function Vb(o,e,t,r,i,s){let{workgroupX:n,workgroupY:l,rowsPerThread:a,tileM:p,tileNBlocks:u,tileKBlocks:c}=it(s),{addressMode:f,weightLayout:h}=s,d=le(o),m=le(e),x=Ui(o,s,!1),v=Ui(o,s,!0),g=x?"vec2<u32>":d,y=v?"vec2<u32>":d,w=Ue(nt("acc",t),e),b=n*l,T=p*c,_=c*u*4;return`${ht(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${g}>;
@group(0) @binding(1) var<storage, read> weights: array<${y}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${m}>;
@group(0) @binding(4) var<uniform> params: Params;

var<workgroup> inputTile: array<${d}, ${Yt(s).input}>;
var<workgroup> weightTile: array<${d}, ${Yt(s).weights}>;

@compute @workgroup_size(${n}, ${l}, 1)
fn main(
  @builtin(local_invocation_id) localId: vec3<u32>,
  @builtin(workgroup_id) workgroupId: vec3<u32>
) {
  let spatialBase =
    workgroupId.x * ${p}u +
    localId.y * ${a}u;
  let outputBlock =
    workgroupId.y * ${u}u + localId.x;
  let spatialCount = params.outputWidth * params.outputHeight;
  var acc: array<vec4<f32>, ${a}>;
  if (outputBlock < ${i}u) {
    for (var row = 0u; row < ${a}u; row++) {
      acc[row] = bias[outputBlock];
    }
  }

  let localLinear =
    localId.y * ${n}u + localId.x;
  ${f!=="analytic"?Xh(r,!1,s):""}
  let totalK = ${r*9}u;
  for (var kBase = 0u; kBase < totalK; kBase += ${c}u) {
    ${f!=="analytic"?$a(d,`
        ${f==="base-offset"?`let offset = (i32(kernelY) - 1) * i32(params.inputWidth) + i32(kernelX) - 1;
        let inputIndex = cachedBase0[load] + u32(offset * ${r}) + inputBlock;`:`let inputIndex = (u32(inputY) * params.inputWidth + u32(inputX)) *
          ${r}u + inputBlock;`}
        value = ${Lr("inputData[inputIndex]",x)};
    `,s):`
    for (
      var loadIndex = localLinear;
      loadIndex < ${T}u;
      loadIndex += ${b}u
    ) {
      let tileSpatial = loadIndex / ${c}u;
      let tileK = loadIndex % ${c}u;
      let inputSpatialIndex =
        workgroupId.x * ${p}u + tileSpatial;
      let kIndex = kBase + tileK;
      var value = ${d}(0.0);
      if (inputSpatialIndex < spatialCount && kIndex < totalK) {
        let outputY = inputSpatialIndex / params.outputWidth;
        let outputX = inputSpatialIndex % params.outputWidth;
        let inputBlock = kIndex % ${r}u;
        let kernelIndex = kIndex / ${r}u;
        let kernelY = kernelIndex / 3u;
        let kernelX = kernelIndex % 3u;
        let inputY = i32(outputY) + i32(kernelY) - 1;
        let inputX = i32(outputX) + i32(kernelX) - 1;
        if (
          inputY >= 0 && inputY < i32(params.inputHeight) &&
          inputX >= 0 && inputX < i32(params.inputWidth)
        ) {
          let inputIndex =
            (u32(inputY) * params.inputWidth + u32(inputX)) *
            ${r}u + inputBlock;
          value = ${Lr("inputData[inputIndex]",x)};
        }
      }
      inputTile[${Wi("loadIndex",s)}] = value;
    }
    `}

    for (
      var loadIndex = localLinear;
      loadIndex < ${_}u;
      loadIndex += ${b}u
    ) {
      let tileK = loadIndex / ${u*4}u;
      let outputRemainder = loadIndex % ${u*4}u;
      let tileOutputBlock = outputRemainder / 4u;
      let outputLane = outputRemainder % 4u;
      let loadedOutputBlock =
        workgroupId.y * ${u}u + tileOutputBlock;
      let kIndex = kBase + tileK;
      var value = ${d}(0.0);
      if (loadedOutputBlock < ${i}u && kIndex < totalK) {
        ${h==="k-major"?`
        let weightIndex = (kIndex * ${i}u + loadedOutputBlock) * 4u + outputLane;
        `:f!=="analytic"?`
        let weightIndex = (loadedOutputBlock * ${r*9}u + kIndex) * 4u + outputLane;
        `:`
        let inputBlock = kIndex % ${r}u;
        let kernelIndex = kIndex / ${r}u;
        let kernelY = kernelIndex / 3u;
        let kernelX = kernelIndex % 3u;
        let weightIndex =
          ((((loadedOutputBlock * 3u + kernelY) * 3u + kernelX) *
            ${r}u + inputBlock) * 4u + outputLane);
        `}
        value = ${Lr("weights[weightIndex]",v)};
      }
      weightTile[${Gu("loadIndex",s)}] = value;
    }

    workgroupBarrier();
    ${Xu(o,s)}
    workgroupBarrier();
    ${f!=="analytic"?Yh(r,s):""}
  }

  if (outputBlock < ${i}u) {
    for (var row = 0u; row < ${a}u; row++) {
      let spatialIndex = spatialBase + row;
      if (spatialIndex < spatialCount) {
        let outputIndex = spatialIndex * ${i}u + outputBlock;
        outputData[outputIndex] = ${w.replaceAll("acc","acc[row]")};
      }
    }
  }
}
`}function Gb(o,e,t,r){let i=le(o),s=nt("acc",e);return`${ht(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${i}>;
@group(0) @binding(1) var<storage, read> weights: array<${i}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${i}>;
@group(0) @binding(4) var<uniform> params: Params;

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.outputWidth || gid.y >= params.outputHeight || gid.z >= ${r}u) {
    return;
  }
  var pooled = vec4<f32>(-3.402823466e+38);
  for (var py = 0u; py < 2u; py++) {
    let centerY = gid.y * 2u + py;
    if (centerY >= params.inputHeight) { continue; }
    for (var px = 0u; px < 2u; px++) {
      let centerX = gid.x * 2u + px;
      if (centerX >= params.inputWidth) { continue; }
      var acc = bias[gid.z];
      for (var ky = 0u; ky < 3u; ky++) {
        let inputY = i32(centerY) + i32(ky) - 1;
        if (inputY < 0 || inputY >= i32(params.inputHeight)) { continue; }
        for (var kx = 0u; kx < 3u; kx++) {
          let inputX = i32(centerX) + i32(kx) - 1;
          if (inputX < 0 || inputX >= i32(params.inputWidth)) { continue; }
          let pixelBase = (u32(inputY) * params.inputWidth + u32(inputX)) * ${t}u;
          for (var inputBlock = 0u; inputBlock < ${t}u; inputBlock++) {
            ${Hn("inputData[pixelBase + inputBlock]",`((((gid.z * 3u + ky) * 3u + kx) * ${t}u + inputBlock) * 4u)`,o)}
          }
        }
      }
      pooled = max(pooled, ${s});
    }
  }
  let outputIndex = (gid.y * params.outputWidth + gid.x) * ${r}u + gid.z;
  outputData[outputIndex] = ${Ue("pooled",o)};
}
`}function qb(o,e,t){let r=le(o);return`${ht(o)}
struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${r}>;
@group(0) @binding(1) var<storage, read_write> outputData: array<${r}>;
@group(0) @binding(2) var<uniform> params: Params;

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(
  @builtin(global_invocation_id) gid: vec3<u32>${t?`,
  @builtin(num_workgroups) groupCount: vec3<u32>`:""}
) {
  ${t?`let linearX = gid.x + gid.z * groupCount.x * ${ye}u;
  let outputBlock = linearX % ${e}u;
  let outputX = linearX / ${e}u;`:`let outputBlock = gid.z;
  let outputX = gid.x;`}
  if (
    outputX >= params.outputWidth ||
    gid.y >= params.outputHeight ||
    outputBlock >= ${e}u
  ) {
    return;
  }
  var pooled = vec4<f32>(-3.402823466e+38);
  for (var py = 0u; py < 2u; py++) {
    let inputY = gid.y * 2u + py;
    if (inputY >= params.inputHeight) { continue; }
    for (var px = 0u; px < 2u; px++) {
      let inputX = outputX * 2u + px;
      if (inputX >= params.inputWidth) { continue; }
      let inputIndex =
        (inputY * params.inputWidth + inputX) * ${e}u + outputBlock;
      pooled = max(pooled, vec4<f32>(inputData[inputIndex]));
    }
  }
  let outputIndex =
    (gid.y * params.outputWidth + outputX) * ${e}u + outputBlock;
  outputData[outputIndex] = ${Ue("pooled",o)};
}
`}function Xb(o,e,t,r,i,s,n){let{workgroupX:l,workgroupY:a,rowsPerThread:p,tileM:u,tileNBlocks:c,tileKBlocks:f}=it(n),{addressMode:h,weightLayout:d}=n,m=le(o),x=le(e),v=Ui(o,n,!1),g=Ui(o,n,!0),y=v?"vec2<u32>":m,w=g?"vec2<u32>":m,b=r[0]+r[1],T=Ue(nt("acc[row]",t),e),_=l*a,S=u*f,A=f*c*4,I=(P,C)=>`
          {
            let sourceBlock = ${C};
            ${h==="base-offset"?`let dx = ${P===i?"(i32((cachedMask[load] >> 9u) & 1u) + i32(kernelX) - 1) >> 1u":"i32(kernelX) - 1"};
            let dy = ${P===i?"(i32((cachedMask[load] >> 10u) & 1u) + i32(kernelY) - 1) >> 1u":"i32(kernelY) - 1"};
            let offset = (dy * i32(params.source${P}Width) + dx) * ${r[P]};
            let sourceIndex = cachedBase${P}[load] + u32(offset) + sourceBlock;`:`let sourceX = ${P===i?"u32(inputX) / 2u":"u32(inputX)"};
            let sourceY = ${P===i?"u32(inputY) / 2u":"u32(inputY)"};
            let sourceIndex =
              (sourceY * params.source${P}Width + sourceX) *
              ${r[P]}u + sourceBlock;`}
            value = ${Lr(`input${P}[sourceIndex]`,v)};
          }`;return`${ht(o)}
struct Params {
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
  inputBlocks: u32,
  source0Width: u32,
  source0Height: u32,
  source1Width: u32,
  source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${y}>;
@group(0) @binding(1) var<storage, read> input1: array<${y}>;
@group(0) @binding(2) var<storage, read> weights: array<${w}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${x}>;
@group(0) @binding(5) var<uniform> params: Params;

var<workgroup> inputTile: array<${m}, ${Yt(n).input}>;
var<workgroup> weightTile: array<${m}, ${Yt(n).weights}>;

@compute @workgroup_size(${l}, ${a}, 1)
fn main(
  @builtin(local_invocation_id) localId: vec3<u32>,
  @builtin(workgroup_id) workgroupId: vec3<u32>
) {
  let spatialBase =
    workgroupId.x * ${u}u +
    localId.y * ${p}u;
  let outputBlock =
    workgroupId.y * ${c}u + localId.x;
  let spatialCount = params.outputWidth * params.outputHeight;
  var acc: array<vec4<f32>, ${p}>;
  if (outputBlock < ${s}u) {
    for (var row = 0u; row < ${p}u; row++) {
      acc[row] = bias[outputBlock];
    }
  }

  let localLinear =
    localId.y * ${l}u + localId.x;
  ${h!=="analytic"?Xh(b,!0,n,{blocks:r,upsampled:i}):""}
  let totalK = ${b*9}u;
  for (var kBase = 0u; kBase < totalK; kBase += ${f}u) {
    ${h!=="analytic"?n.decoderLoad==="source-first"?`
    if (channelBlock < ${r[0]}u) {
      ${$a(m,I(0,"inputBlock"),n)}
    } else {
      ${$a(m,I(1,`inputBlock - ${r[0]}u`),n)}
    }
    `:$a(m,`
        if (inputBlock < ${r[0]}u) {
          ${I(0,"inputBlock")}
        } else {
          ${I(1,`inputBlock - ${r[0]}u`)}
        }
    `,n):`
    for (
      var loadIndex = localLinear;
      loadIndex < ${S}u;
      loadIndex += ${_}u
    ) {
      let tileSpatial = loadIndex / ${f}u;
      let tileK = loadIndex % ${f}u;
      let outputSpatialIndex =
        workgroupId.x * ${u}u + tileSpatial;
      let kIndex = kBase + tileK;
      var value = ${m}(0.0);
      if (outputSpatialIndex < spatialCount && kIndex < totalK) {
        let outputY = outputSpatialIndex / params.outputWidth;
        let outputX = outputSpatialIndex % params.outputWidth;
        let inputBlock = kIndex % ${b}u;
        let kernelIndex = kIndex / ${b}u;
        let kernelY = kernelIndex / 3u;
        let kernelX = kernelIndex % 3u;
        let inputY = i32(outputY) + i32(kernelY) - 1;
        let inputX = i32(outputX) + i32(kernelX) - 1;
        if (
          inputY >= 0 && inputY < i32(params.outputHeight) &&
          inputX >= 0 && inputX < i32(params.outputWidth)
        ) {
          if (inputBlock < ${r[0]}u) {
            ${I(0,"inputBlock")}
          } else {
            ${I(1,`inputBlock - ${r[0]}u`)}
          }
        }
      }
      inputTile[${Wi("loadIndex",n)}] = value;
    }
    `}

    for (
      var loadIndex = localLinear;
      loadIndex < ${A}u;
      loadIndex += ${_}u
    ) {
      let tileK = loadIndex / ${c*4}u;
      let outputRemainder = loadIndex % ${c*4}u;
      let tileOutputBlock = outputRemainder / 4u;
      let outputLane = outputRemainder % 4u;
      let loadedOutputBlock =
        workgroupId.y * ${c}u + tileOutputBlock;
      let kIndex = kBase + tileK;
      var value = ${m}(0.0);
      if (loadedOutputBlock < ${s}u && kIndex < totalK) {
        ${d==="k-major"?`
        let weightIndex = (kIndex * ${s}u + loadedOutputBlock) * 4u + outputLane;
        `:h!=="analytic"?`
        let weightIndex = (loadedOutputBlock * ${b*9}u + kIndex) * 4u + outputLane;
        `:`
        let inputBlock = kIndex % ${b}u;
        let kernelIndex = kIndex / ${b}u;
        let kernelY = kernelIndex / 3u;
        let kernelX = kernelIndex % 3u;
        let weightIndex =
          ((((loadedOutputBlock * 3u + kernelY) * 3u + kernelX) *
            ${b}u + inputBlock) * 4u + outputLane);
        `}
        value = ${Lr("weights[weightIndex]",g)};
      }
      weightTile[${Gu("loadIndex",n)}] = value;
    }

    workgroupBarrier();
    ${Xu(o,n)}
    workgroupBarrier();
    ${h!=="analytic"?Yh(b,n):""}
  }

  if (outputBlock < ${s}u) {
    for (var row = 0u; row < ${p}u; row++) {
      let spatialIndex = spatialBase + row;
      if (spatialIndex < spatialCount) {
        let outputIndex = spatialIndex * ${s}u + outputBlock;
        outputData[outputIndex] = ${T};
      }
    }
  }
}
`}function Yb(o,e,t,r,i,s,n){let l=it(n),{workgroupX:a,workgroupY:p,rowsPerThread:u,tileM:c,tileNBlocks:f,tileKBlocks:h}=l,d=a*p,m=c*h/d,x=le(o),v=Ui(o,n,!1),g=Ui(o,n,!0),y=r[i],w=1-i,b=r[w],T=(S,A)=>{let I=r[S],P=A?4:9,C=A?`phase * ${4*y*s*4}u`:`${Un*y*s*4}u`,B=A?`(i32(tap / 2u) + i32(phaseY) - 1) * i32(params.source${S}Width) + i32(tap % 2u) + i32(phaseX) - 1`:`(i32(tap / 3u) - 1) * i32(params.source${S}Width) + i32(tap % 3u) - 1`;return`
  for (var kBase = 0u; kBase < ${P*I}u; kBase += ${h}u) {
    let inputK = kBase + localLinear % ${h}u;
    let tap = inputK / ${I}u;
    let channel = inputK % ${I}u;
    for (var load = 0u; load < ${m}u; load++) {
      let i = localLinear + load * ${d}u;
      var value = ${x}(0.0);
      if (tap < ${P}u && (mask${S}[load] & (1u << tap)) != 0u) {
        let offset = ${B};
        let index = u32(base${S}[load] + offset) * ${I}u + channel;
        value = ${Lr(`input${S}[index]`,v)};
      }
      inputTile[${Wi("i",n)}] = value;
    }
    for (var i = localLinear; i < ${h*f*4}u; i += ${d}u) {
      let k = kBase + i / ${f*4}u;
      let outputBlock = workgroupId.y * ${f}u + (i % ${f*4}u) / 4u;
      var value = ${x}(0.0);
      if (k < ${P*I}u && outputBlock < ${s}u) {
        let index = ${C} + (k * ${s}u + outputBlock) * 4u + i % 4u;
        value = ${Lr("weights[index]",g)};
      }
      weightTile[${Gu("i",n)}] = value;
    }
    workgroupBarrier();
    ${Xu(o,n)}
    workgroupBarrier();
  }`},_=(S,A)=>{let I=A?4:9,P=A?2:3;return`
    let x${S} = i32(${A?"x":"x * 2u + phaseX"});
    let y${S} = i32(${A?"y":"y * 2u + phaseY"});
    base${S}[load] = y${S} * i32(params.source${S}Width) + x${S};
    var bits${S} = 0u;
    for (var tap = 0u; tap < ${I}u; tap++) {
      let sx = x${S} + i32(tap % ${P}u) ${A?"+ i32(phaseX)":""} - 1;
      let sy = y${S} + i32(tap / ${P}u) ${A?"+ i32(phaseY)":""} - 1;
      if (valid && sx >= 0 && sy >= 0 && sx < i32(params.source${S}Width) && sy < i32(params.source${S}Height)) {
        bits${S} |= 1u << tap;
      }
    }
    mask${S}[load] = bits${S};`};return`${ht(o)}
struct Params {
  outputWidth: u32, outputHeight: u32, outputBlocks: u32, inputBlocks: u32,
  source0Width: u32, source0Height: u32, source1Width: u32, source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${v?"vec2<u32>":x}>;
@group(0) @binding(1) var<storage, read> input1: array<${v?"vec2<u32>":x}>;
@group(0) @binding(2) var<storage, read> weights: array<${g?"vec2<u32>":x}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${le(e)}>;
@group(0) @binding(5) var<uniform> params: Params;
var<workgroup> inputTile: array<${x}, ${Yt(n).input}>;
var<workgroup> weightTile: array<${x}, ${Yt(n).weights}>;

@compute @workgroup_size(${a}, ${p}, 1)
fn main(@builtin(local_invocation_id) localId: vec3<u32>, @builtin(workgroup_id) workgroupId: vec3<u32>) {
  let phase = workgroupId.z;
  let phaseX = phase & 1u;
  let phaseY = phase >> 1u;
  let phaseWidth = (params.outputWidth + 1u) / 2u;
  let localLinear = localId.y * ${a}u + localId.x;
  let outputBlock = workgroupId.y * ${f}u + localId.x;
  var acc: array<vec4<f32>, ${u}>;
  if (outputBlock < ${s}u) {
    for (var row = 0u; row < ${u}u; row++) { acc[row] = bias[outputBlock]; }
  }
  var base0: array<i32, ${m}>;
  var base1: array<i32, ${m}>;
  var mask0: array<u32, ${m}>;
  var mask1: array<u32, ${m}>;
  for (var load = 0u; load < ${m}u; load++) {
    let spatial = workgroupId.x * ${c}u + (localLinear + load * ${d}u) / ${h}u;
    let x = spatial % phaseWidth;
    let y = spatial / phaseWidth;
    let valid = x * 2u + phaseX < params.outputWidth && y * 2u + phaseY < params.outputHeight;
    ${_(i,!0)}
    ${_(w,!1)}
  }
  ${T(i,!0)}
  ${T(w,!1)}
  if (outputBlock < ${s}u) {
    for (var row = 0u; row < ${u}u; row++) {
      let spatial = workgroupId.x * ${c}u + localId.y * ${u}u + row;
      let x = (spatial % phaseWidth) * 2u + phaseX;
      let y = (spatial / phaseWidth) * 2u + phaseY;
      if (x < params.outputWidth && y < params.outputHeight) {
        outputData[(y * params.outputWidth + x) * ${s}u + outputBlock] = ${Ue(nt("acc[row]",t),e)};
      }
    }
  }
}
`}function jb(o,e,t,r,i,s){let n=le(o),l=le(e),a=r[0]+r[1],p=(c,f)=>{let h=c===i;return`
      {
        let sourceX = ${h?"u32(inputX) / 2u":"u32(inputX)"};
        let sourceY = ${h?"u32(inputY) / 2u":"u32(inputY)"};
        let sourcePixelBase = (sourceY * params.source${c}Width + sourceX) * ${r[c]}u;
        for (var sourceBlock = 0u; sourceBlock < ${r[c]}u; sourceBlock++) {
          let inputBlock = ${f}u + sourceBlock;
          ${Hn(`input${c}[sourcePixelBase + sourceBlock]`,`((((gid.z * 3u + ky) * 3u + kx) * ${a}u + inputBlock) * 4u)`,o)}
        }
      }
`},u=Ue(nt("acc",t),e);return`${ht(o)}
struct Params {
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
  inputBlocks: u32,
  source0Width: u32,
  source0Height: u32,
  source1Width: u32,
  source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${n}>;
@group(0) @binding(1) var<storage, read> input1: array<${n}>;
@group(0) @binding(2) var<storage, read> weights: array<${n}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${l}>;
@group(0) @binding(5) var<uniform> params: Params;

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.outputWidth || gid.y >= params.outputHeight || gid.z >= ${s}u) {
    return;
  }
  var acc = bias[gid.z];
  for (var ky = 0u; ky < 3u; ky++) {
    let inputY = i32(gid.y) + i32(ky) - 1;
    if (inputY < 0 || inputY >= i32(params.outputHeight)) { continue; }
    for (var kx = 0u; kx < 3u; kx++) {
      let inputX = i32(gid.x) + i32(kx) - 1;
      if (inputX < 0 || inputX >= i32(params.outputWidth)) { continue; }
      ${p(0,0)}
      ${p(1,r[0])}
    }
  }
  let outputIndex = (gid.y * params.outputWidth + gid.x) * ${s}u + gid.z;
  outputData[outputIndex] = ${u};
}
`}function Kb(o,e,t,r,i,s){let n=le(o),l=le(e),a=r[0]+r[1],p=rt*rt*a,u=fe*fe,c=Ue(nt("acc",t),e),f=(h,d)=>`
      let sourceBlock = ${d};
      let sourceIndex =
        (${h===i?"u32(inputY) / 2u":"u32(inputY)"} * params.source${h}Width + ${h===i?"u32(inputX) / 2u":"u32(inputX)"}) *
        ${r[h]}u + sourceBlock;
      value = input${h}[sourceIndex];`;return`${ht(o)}
struct Params {
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
  inputBlocks: u32,
  source0Width: u32,
  source0Height: u32,
  source1Width: u32,
  source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${n}>;
@group(0) @binding(1) var<storage, read> input1: array<${n}>;
@group(0) @binding(2) var<storage, read> weights: array<${n}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${l}>;
@group(0) @binding(5) var<uniform> params: Params;

var<workgroup> inputPatch: array<${n}, ${p}>;

@compute @workgroup_size(${fe}, ${fe}, 1)
fn main(
  @builtin(local_invocation_id) localId: vec3<u32>,
  @builtin(workgroup_id) workgroupId: vec3<u32>
) {
  let localLinear =
    localId.y * ${fe}u + localId.x;
  for (
    var loadIndex = localLinear;
    loadIndex < ${p}u;
    loadIndex += ${u}u
  ) {
    let patchPixel = loadIndex / ${a}u;
    let inputBlock = loadIndex % ${a}u;
    let patchX = patchPixel % ${rt}u;
    let patchY = patchPixel / ${rt}u;
    let inputX =
      i32(workgroupId.x * ${fe}u + patchX) - 1;
    let inputY =
      i32(workgroupId.y * ${fe}u + patchY) - 1;
    var value = ${n}(0.0);
    if (
      inputX >= 0 && inputX < i32(params.outputWidth) &&
      inputY >= 0 && inputY < i32(params.outputHeight)
    ) {
      if (inputBlock < ${r[0]}u) {
        ${f(0,"inputBlock")}
      } else {
        ${f(1,`inputBlock - ${r[0]}u`)}
      }
    }
    inputPatch[loadIndex] = value;
  }
  workgroupBarrier();

  let outputX =
    workgroupId.x * ${fe}u + localId.x;
  let outputY =
    workgroupId.y * ${fe}u + localId.y;
  let outputBlock = workgroupId.z;
  if (
    outputX >= params.outputWidth || outputY >= params.outputHeight ||
    outputBlock >= ${s}u
  ) {
    return;
  }

  var acc = bias[outputBlock];
  for (var ky = 0u; ky < 3u; ky++) {
    for (var kx = 0u; kx < 3u; kx++) {
      let patchBase =
        ((localId.y + ky) * ${rt}u + localId.x + kx) *
        ${a}u;
      for (var inputBlock = 0u; inputBlock < ${a}u; inputBlock++) {
        ${Hn("inputPatch[patchBase + inputBlock]",`((((outputBlock * 3u + ky) * 3u + kx) * ${a}u + inputBlock) * 4u)`,o)}
      }
    }
  }
  let outputIndex =
    (outputY * params.outputWidth + outputX) * ${s}u + outputBlock;
  outputData[outputIndex] = ${c};
}
`}function Yu(o,e){let t=le(o),r=Array.from({length:e},(l,a)=>`@group(0) @binding(${a}) var<storage, read> input${a}: array<vec4<f32>>;`).join(`
`),i=Array.from({length:e},(l,a)=>{let p=a*3;return`if (channel < ${p+3}u) { return input${a}[pixel][channel - ${p}u]; }`}).join(`
  `),s=e,n=e+1;return`${ht(o)}
struct Params {
  width: u32,
  height: u32,
  outputBlocks: u32,
  inputChannels: u32,
}
${r}
@group(0) @binding(${s}) var<storage, read_write> outputData: array<${t}>;
@group(0) @binding(${n}) var<uniform> params: Params;

fn readChannel(pixel: u32, channel: u32) -> f32 {
  ${i}
  return 0.0;
}

@compute @workgroup_size(${ye}, ${ye}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.width || gid.y >= params.height || gid.z >= params.outputBlocks) {
    return;
  }
  let pixel = gid.y * params.width + gid.x;
  let firstChannel = gid.z * 4u;
  let value = vec4<f32>(
    readChannel(pixel, firstChannel),
    readChannel(pixel, firstChannel + 1u),
    readChannel(pixel, firstChannel + 2u),
    readChannel(pixel, firstChannel + 3u)
  );
  outputData[pixel * params.outputBlocks + gid.z] = ${Ue("value",o)};
}
`}var Qb="chromium-experimental-subgroup-matrix",Zb="wgpu-cooperative-matrix",zi=32,Hi=8,Or=4,Jb=1024;function jh(o,e="f32"){let t=o.features.has(Qb)?"tint":o.features.has(Zb)?"naga":void 0;if(!t||!o.features.has("subgroups"))return;let r=o.adapterInfo,i=r?.subgroupMaxSize??0;if(!r||!i||r.subgroupMinSize!==i)return;let s=e==="f16"?[["f16","f16"],["f16","f32"],["f32","f32"]]:[["f16","f32"],["f32","f32"],["f16","f16"]],n=o.features.has("shader-f16");for(let[l,a]of s)if(!(!n&&(l==="f16"||a==="f16"))){for(let p of[8,16])if(r.subgroupMatrixConfigs?.find(c=>c.componentType===l&&c.resultComponentType===a&&c.M===p&&c.N===p&&c.K===p))return{componentType:l,resultType:a,size:p,subgroupSize:i,dialect:t}}}function ew(o,e){return e.outputFragments??2}function tw(o,e,t,r){let i=o.componentType==="f16"?2:4,s=o.resultType==="f16"?2:4,n=o.size,l=(Hi+2)*(zi+2)*n,a=t?16:9;return i*(r?2:1)*(l+a*n*n*e)+s*Or*zi*n}function Kh(o){let e=o.size,t=o.dialect==="naga",r=(s,n,l)=>t?`coop_mat${e}x${e}<${s}, ${l}>`:`subgroup_matrix_${n}<${s}, ${e}, ${e}>`,i=r(o.resultType,"result","C");return{left:r(o.componentType,"left","A"),right:r(o.componentType,"right","B"),result:i,load:(s,n,l,a)=>t?`coopLoadT<${s}>(&${n}[${l}], ${a})`:`subgroupMatrixLoad<${s}, row_major>(&${n}, ${l}, ${a})`,store:(s,n,l,a)=>t?`coopStoreT(${l}, &${s}[${n}], ${a});`:`subgroupMatrixStore<row_major>(&${s}, ${n}, ${l}, ${a});`,multiplyAdd:t?"coopMultiplyAdd":"subgroupMatrixMultiplyAccumulate",declare:s=>t?`var ${s}: ${i};`:`var ${s} = ${i}();`,extensions:t?"enable wgpu_cooperative_matrix;":`enable subgroups;
enable chromium_experimental_subgroup_matrix;
diagnostic(off, chromium_experimental.subgroup_matrix_uniformity);`}}function Vu(o,e,t,r,i,s,n){let l=(d,m,x)=>`{
        let staged = vec4<${s}>(${m});
        let base = ${x} + i * 4u;
        ${d.array}[base] = staged.x;
        ${d.array}[base + 1u] = staged.y;
        ${d.array}[base + 2u] = staged.z;
        ${d.array}[base + 3u] = staged.w;
      }`,a=d=>e.map(m=>`
    for (var batch = 0u; batch < ${Math.ceil(m.vectors/r)}u; batch++) {
      let i = localIndex + batch * ${r}u;
      if (i < ${m.vectors}u) {
        var value = ${i}(0.0);
        ${m.read}
        ${l(m,"value",d(m))}
      }
    }`).join(""),p=d=>e.map(m=>`let ${m.array}Base = ${d} * ${(m.stride??m.vectors)*4}u;`).join(`
    `);if(!n||o<2)return`
  for (var chunk = 0u; chunk < ${o}u; chunk++) {
    let stageChunk = chunk;
    ${p("0u")}
    ${a(d=>`${d.array}Base`)}
    workgroupBarrier();
    ${t}
    workgroupBarrier();
  }`;let u=e.map(d=>Math.ceil(d.vectors/r)),c=e.map((d,m)=>Array.from({length:u[m]},(x,v)=>`
      {
        let i = localIndex + ${v*r}u;
        var value = ${i}(0.0);
        if (i < ${d.vectors}u) {
          ${d.read}
        }
        prefetch${m}_${v} = value;
      }`).join("")).join(""),f=e.map((d,m)=>Array.from({length:u[m]},(x,v)=>`var prefetch${m}_${v} = ${i}(0.0);`).join(`
    `)).join(`
    `),h=e.map((d,m)=>Array.from({length:u[m]},(x,v)=>`
      {
        let i = localIndex + ${v*r}u;
        if (i < ${d.vectors}u) ${l(d,`prefetch${m}_${v}`,`next * ${(d.stride??d.vectors)*4}u`)}
      }`).join("")).join("");return`
  {
    let stageChunk = 0u;
    ${a(()=>"0u")}
  }
  workgroupBarrier();
  for (var chunk = 0u; chunk < ${o}u; chunk++) {
    let buffer = chunk & 1u;
    let next = 1u - buffer;
    ${p("buffer")}
    ${f}
    if (chunk + 1u < ${o}u) {
      let stageChunk = chunk + 1u;
      ${c}
    }
    ${t}
    if (chunk + 1u < ${o}u) {
      ${h}
    }
    workgroupBarrier();
  }`}function Qh(o,e){return o.dialect==="naga"?Array.from({length:9},(t,r)=>`{ let tap = ${r}u; ${e} }`).join(`
`):`for (var tap = 0u; tap < 9u; tap++) { ${e} }`}function rw(o,e,t,r,i,s,n,l,a,p=!1){let u=s.size,c=s.componentType,f=s.resultType,h=zi,d=h+2,m=Hi+2,x=l?2:1,v=u/4,g=Math.ceil(r/v),y=n*u,w=h/u,b=Or*s.subgroupSize,T=le(o),_=le(e),{left:S,right:A,load:I,store:P,multiplyAdd:C,declare:B,extensions:R}=Kh(s),E=[];for(let O=0;O<2;O++)for(let J=0;J<w;J++)for(let U=0;U<n;U++)E.push(`acc${O}_${J}_${U}`);let N=E.map(B).join(`
  `),M=Array.from({length:n},(O,J)=>`let b${J} = ${I(A,"weightTile",`weightTileBase + tap * ${u*y}u + ${J*u}u`,`${y}u`)};`).join(`
      `),oe=[];for(let O=0;O<2;O++)for(let J=0;J<w;J++)oe.push(`{
        let a = ${I(S,"inputTile",`inputTileBase + ((rowBase + ${O}u + ky) * ${d}u + ${J*u}u + kx) * ${u}u`,`${u}u`)};
        ${Array.from({length:n},(U,j)=>`acc${O}_${J}_${j} = ${C}(a, b${j}, acc${O}_${J}_${j});`).join(`
        `)}
      }`);let we=`let ky = tap / 3u;
      let kx = tap % 3u;
      ${M}
      ${oe.join(`
      `)}`,ze=Qh(s,we),G=[];if(p)for(let O=0;O<n;O++)G.push(`{
    var pooled = vec4<f32>(-3.402823e38);
    let pixel = lane / ${v}u;
    let outputBlock = blockBase + ${O*v}u + lane % ${v}u;
    ${Array.from({length:2},(J,U)=>`
    ${Array.from({length:w},(j,Ft)=>P("resultTile",`resultBase + ${Ft*u*u}u`,`acc${U}_${Ft}_${O}`,`${u}u`)).join(`
    `)}
    workgroupBarrier();
    {
      let base = resultBase + pixel * ${u*2}u + (lane % ${v}u) * 4u;
      let first = vec4<f32>(resultTile[base], resultTile[base + 1u], resultTile[base + 2u], resultTile[base + 3u]);
      let second = vec4<f32>(resultTile[base + ${u}u], resultTile[base + ${u+1}u], resultTile[base + ${u+2}u], resultTile[base + ${u+3}u]);
      if (tileY + rowBase + ${U}u < params.inputHeight) {
        pooled = max(pooled, first);
        if (tileX + pixel * 2u + 1u < params.inputWidth) { pooled = max(pooled, second); }
      }
    }
    workgroupBarrier();`).join(`
    `)}
    let outputX = tileX / 2u + pixel;
    let outputY = (tileY + rowBase) / 2u;
    if (outputX < params.outputWidth && outputY < params.outputHeight && outputBlock < ${i}u) {
      let acc = pooled + bias[outputBlock];
      outputData[(outputY * params.outputWidth + outputX) * ${i}u + outputBlock] = ${Ue(nt("acc",t),e)};
    }
  }`);else for(let O=0;O<2;O++)for(let J=0;J<n;J++)G.push(`{
    ${Array.from({length:w},(U,j)=>P("resultTile",`resultBase + ${j*u*u}u`,`acc${O}_${j}_${J}`,`${u}u`)).join(`
    `)}
    workgroupBarrier();
    let outputY = tileY + rowBase + ${O}u;
    for (var e = lane; e < ${h*v}u; e += ${s.subgroupSize}u) {
      let pixel = e / ${v}u;
      let outputX = tileX + pixel;
      let outputBlock = blockBase + ${J*v}u + e % ${v}u;
      if (outputX < params.outputWidth && outputY < params.outputHeight && outputBlock < ${i}u) {
        let base = resultBase + pixel * ${u}u + (e % ${v}u) * 4u;
        let acc = vec4<f32>(f32(resultTile[base]), f32(resultTile[base + 1u]),
          f32(resultTile[base + 2u]), f32(resultTile[base + 3u])) + bias[outputBlock];
        outputData[(outputY * params.outputWidth + outputX) * ${i}u + outputBlock] = ${Ue(nt("acc",t),e)};
      }
    }
    workgroupBarrier();
  }`);let se=a?(()=>{let[O,J]=a.sourceBlocks,U=j=>j===a.upsampledSource?`min(u32(inputY) / 2u, params.source${j}Height - 1u) * params.source${j}Width + min(u32(inputX) / 2u, params.source${j}Width - 1u)`:`u32(inputY) * params.source${j}Width + u32(inputX)`;return`if (block < ${O}u) {
          value = input0[(${U(0)}) * ${O}u + block];
        } else {
          value = input1[(${U(1)}) * ${J}u + block - ${O}u];
        }`})():`value = inputData[(u32(inputY) * params.inputWidth + u32(inputX)) * ${r}u + block];`,He=a?`struct Params {
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
  inputBlocks: u32,
  source0Width: u32,
  source0Height: u32,
  source1Width: u32,
  source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${T}>;
@group(0) @binding(1) var<storage, read> input1: array<${T}>;
@group(0) @binding(2) var<storage, read> weights: array<${T}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${_}>;
@group(0) @binding(5) var<uniform> params: Params;`:`struct Params {
  inputWidth: u32,
  inputHeight: u32,
  outputWidth: u32,
  outputHeight: u32,
  inputBlocks: u32,
  outputBlocks: u32,
}
@group(0) @binding(0) var<storage, read> inputData: array<${T}>;
@group(0) @binding(1) var<storage, read> weights: array<${T}>;
@group(0) @binding(2) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(3) var<storage, read_write> outputData: array<${_}>;
@group(0) @binding(4) var<uniform> params: Params;`,We=a?"params.outputWidth":"params.inputWidth",he=a?"params.outputHeight":"params.inputHeight";return`${o==="fp16"||e==="fp16"||c==="f16"||f==="f16"?`enable f16;
`:""}${R}
${He}

var<workgroup> inputTile: array<${c}, ${x*m*d*u}>;
var<workgroup> weightTile: array<${c}, ${x*9*u*y}>;
var<workgroup> resultTile: array<${f}, ${Or*h*u}>;

@compute @workgroup_size(${b})
fn main(
  @builtin(local_invocation_index) localIndex: u32,
  @builtin(workgroup_id) workgroupId: vec3<u32>,
  @builtin(subgroup_id) subgroup: u32,
  @builtin(subgroup_invocation_id) lane: u32
) {
  let tileX = workgroupId.x * ${h}u;
  let tileY = workgroupId.y * ${Hi}u;
  let blockBase = workgroupId.z * ${y/4}u;
  let rowBase = subgroup * 2u;
  let resultBase = subgroup * ${h*u}u;
  ${N}

  ${Vu(g,[{array:"inputTile",vectors:m*d*v,read:`let pixel = i / ${v}u;
      let block = stageChunk * ${v}u + i % ${v}u;
      let inputX = i32(tileX + pixel % ${d}u) - 1;
      let inputY = i32(tileY + pixel / ${d}u) - 1;
      if (block < ${r}u && inputX >= 0 && inputY >= 0 &&
          inputX < i32(${We}) && inputY < i32(${he})) {
        ${se}
      }`},{array:"weightTile",vectors:9*u*y/4,read:`let quad = i % ${y/4}u;
      let row = i / ${y/4}u;
      let tap = row / ${u}u;
      let inputChannel = stageChunk * ${u}u + row % ${u}u;
      let outputBlock = blockBase + quad;
      if (inputChannel < ${r*4}u && outputBlock < ${i}u) {
        value = weights[((tap * ${r}u + inputChannel / 4u) * ${i}u + outputBlock) * 4u + inputChannel % 4u];
      }`}],ze,b,T,c,l)}

  ${G.join(`
  `)}
}
`}var Ia=[[-1,0],[0,1]],Wh=[[[0],[1,2]],[[0,1],[2]]],Un=16;function iw(o,e,t,r,i,s){let n=Q(t.outputChannels),l=i[s],a=i[1-s],p=s===0?0:i[0]*4,u=s===0?i[0]*4:0,c=Un*l*n*16,f=new Float32Array(c+9*a*n*16),h=Ca(t.weight),d=(g,y,w,b)=>g<t.outputChannels&&y<t.inputChannels?h[((g*t.inputChannels+y)*3+w)*3+b]:0;for(let g=0;g<n*4;g++){let y=g>>2,w=g&3;for(let b=0;b<l*4;b++)for(let T=0;T<4;T++)for(let _=0;_<4;_++){let S=0;for(let I of Wh[T>>1][_>>1])for(let P of Wh[T&1][_&1])S+=d(g,p+b,I,P);let A=((T*4+_)*l+(b>>2))*n+y;f[A*16+(b&3)*4+w]=S}for(let b=0;b<a*4;b++)for(let T=0;T<9;T++){let _=(T*a+(b>>2))*n+y;f[c+_*16+(b&3)*4+w]=d(g,u+b,T/3|0,T%3)}}let m;if(r==="fp16"){let g=new Uint16Array(f.length);for(let y=0;y<f.length;y++)g[y]=qh(f[y]);m=new Uint8Array(g.buffer)}else m=new Uint8Array(f.buffer);let x=new Float32Array(n*4);x.set(Ca(t.bias));let v=zn(o,`oidn/${e}/weights/${r}/subpixel`,m,Ie.STORAGE);try{return{weights:v,weightLayout:"k-major",bias:zn(o,`oidn/${e}/bias`,new Uint8Array(x.buffer),Ie.STORAGE)}}catch(g){throw v.destroy(),g}}function nw(o,e,t,r,i,s,n,l,a){let p=i.size,u=a?2:1,c=i.componentType,f=i.resultType,h=zi,d=Hi,m=h+2,x=d+2,v=h/2+2,g=d/2+2,y=p/4,w=n[l],b=n[1-l],T=Math.ceil(w/y),_=Math.ceil(b/y),S=s*p,A=d/2,I=h/2/p,P=Or*i.subgroupSize,C=le(o),B=le(e),{left:R,right:E,load:N,store:M,multiplyAdd:oe,declare:we,extensions:ze}=Kh(i),G=(ee,ie,me)=>`acc${ee}_${ie}_${me}`,se=[];for(let ee=0;ee<A;ee++)for(let ie=0;ie<I;ie++)for(let me=0;me<s;me++)se.push(we(G(ee,ie,me)));let He=(ee,ie)=>{let me=[];for(let vt=0;vt<A;vt++)for(let rn=0;rn<I;rn++)me.push(`{
        let a = ${N(R,"inputTile",`inputTileBase + ${ee(vt,rn)}`,ie)};
        ${Array.from({length:s},(Iw,Ya)=>`${G(vt,rn,Ya)} = ${oe}(a, b${Ya}, ${G(vt,rn,Ya)});`).join(`
        `)}
      }`);return me.join(`
      `)},We=ee=>Array.from({length:s},(ie,me)=>`let b${me} = ${N(E,"weightTile",`weightTileBase + ${ee} * ${p*S}u + ${me*p}u`,`${S}u`)};`).join(`
      `),he=(ee,ie,me)=>`(${me} * params.source${ee}Width + ${ie}) * ${n[ee]}u`,ke=Math.max(x*m,g*v)*y,O=Un*p*S/4,J=(ee,ie,me)=>({array:"weightTile",vectors:ee*p*S/4,stride:O,read:`let quad = i % ${S/4}u;
      let row = i / ${S/4}u;
      let matrix = row / ${p}u;
      let inputChannel = stageChunk * ${p}u + row % ${p}u;
      let outputBlock = blockBase + quad;
      if (inputChannel < ${ie*4}u && outputBlock < ${r}u) {
        value = weights[${me}u + ((matrix * ${ie}u + inputChannel / 4u) * ${r}u + outputBlock) * 4u + inputChannel % 4u];
      }`}),U=(ee,ie,me)=>({array:"inputTile",vectors:ee*ie*y,stride:ke,read:`let pixel = i / ${y}u;
      let block = stageChunk * ${y}u + i % ${y}u;
      ${me}`}),j=`let inputX = i32(tileX / 2u + pixel % ${v}u) - 1;
      let inputY = i32(tileY / 2u + pixel / ${v}u) - 1;
      if (block < ${w}u && inputX >= 0 && inputY >= 0 &&
          inputX < i32(params.source${l}Width) && inputY < i32(params.source${l}Height)) {
        value = input${l}[${he(l,"u32(inputX)","u32(inputY)")} + block];
      }`,Ft=1-l,Ze=`let inputX = i32(tileX + pixel % ${m}u) - 1;
      let inputY = i32(tileY + pixel / ${m}u) - 1;
      if (block < ${b}u && inputX >= 0 && inputY >= 0 &&
          inputX < i32(params.outputWidth) && inputY < i32(params.outputHeight)) {
        value = input${Ft}[${he(Ft,"u32(inputX)","u32(inputY)")} + block];
      }`,yt=[];for(let ee=0;ee<2;ee++)for(let ie=0;ie<2;ie++)yt.push(`{
      let matrix = phase * 4u + ${ee*2+ie}u;
      let offsetY = select(${Ia[0][ee]+1}u, ${Ia[1][ee]+1}u, phaseY == 1u);
      let offsetX = select(${Ia[0][ie]+1}u, ${Ia[1][ie]+1}u, phaseX == 1u);
      ${We("matrix")}
      ${He((me,vt)=>`((${me}u + offsetY) * ${v}u + ${vt*p}u + offsetX) * ${p}u`,`${p}u`)}
    }`);let tn=`let ky = tap / 3u;
      let kx = tap % 3u;
      ${We("tap")}
      ${He((ee,ie)=>`((phaseY + ${ee*2}u + ky) * ${m}u + phaseX + kx + ${ie*p*2}u) * ${p}u`,`${p*2}u`)}`,og=Qh(i,tn),wc=[];for(let ee=0;ee<A;ee++)for(let ie=0;ie<s;ie++)wc.push(`{
    ${Array.from({length:I},(me,vt)=>M("resultTile",`resultBase + ${vt*p*p}u`,G(ee,vt,ie),`${p}u`)).join(`
    `)}
    workgroupBarrier();
    let outputY = tileY + phaseY + ${ee*2}u;
    for (var e = lane; e < ${h/2*y}u; e += ${i.subgroupSize}u) {
      let pixel = e / ${y}u;
      let outputX = tileX + phaseX + pixel * 2u;
      let outputBlock = blockBase + ${ie*y}u + e % ${y}u;
      if (outputX < params.outputWidth && outputY < params.outputHeight && outputBlock < ${r}u) {
        let base = resultBase + pixel * ${p}u + (e % ${y}u) * 4u;
        let acc = vec4<f32>(f32(resultTile[base]), f32(resultTile[base + 1u]),
          f32(resultTile[base + 2u]), f32(resultTile[base + 3u])) + bias[outputBlock];
        outputData[(outputY * params.outputWidth + outputX) * ${r}u + outputBlock] = ${Ue(nt("acc",t),e)};
      }
    }
    workgroupBarrier();
  }`);return`${o==="fp16"||e==="fp16"||c==="f16"||f==="f16"?`enable f16;
`:""}${ze}
struct Params {
  outputWidth: u32,
  outputHeight: u32,
  outputBlocks: u32,
  inputBlocks: u32,
  source0Width: u32,
  source0Height: u32,
  source1Width: u32,
  source1Height: u32,
}
@group(0) @binding(0) var<storage, read> input0: array<${C}>;
@group(0) @binding(1) var<storage, read> input1: array<${C}>;
@group(0) @binding(2) var<storage, read> weights: array<${C}>;
@group(0) @binding(3) var<storage, read> bias: array<vec4<f32>>;
@group(0) @binding(4) var<storage, read_write> outputData: array<${B}>;
@group(0) @binding(5) var<uniform> params: Params;

var<workgroup> inputTile: array<${c}, ${u*ke*4}>;
var<workgroup> weightTile: array<${c}, ${u*O*4}>;
var<workgroup> resultTile: array<${f}, ${Or*(h/2)*p}>;

@compute @workgroup_size(${P})
fn main(
  @builtin(local_invocation_index) localIndex: u32,
  @builtin(workgroup_id) workgroupId: vec3<u32>,
  @builtin(subgroup_id) subgroup: u32,
  @builtin(subgroup_invocation_id) lane: u32
) {
  let tileX = workgroupId.x * ${h}u;
  let tileY = workgroupId.y * ${d}u;
  let blockBase = workgroupId.z * ${S/4}u;
  let phase = subgroup;
  let phaseY = subgroup >> 1u;
  let phaseX = subgroup & 1u;
  let resultBase = subgroup * ${h/2*p}u;
  ${se.join(`
  `)}

  ${Vu(T,[U(g,v,j),J(Un,w,0)],yt.join(`
    `),P,C,c,a)}

  ${Vu(_,[U(x,m,Ze),J(9,b,Un*w*r*4)],og,P,C,c,a)}

  ${wc.join(`
  `)}
}
`}function ow(o){return o.op==="concat"?o.inputs:o.op==="fusedUpsampleConcatConv2d"?o.inputs.map(e=>e.value):[o.input]}function Zh(o,e="auto"){let t=o.features.has("shader-f16");if(e==="fp16"&&!t)throw new Error("OIDN FP16 was requested but the OIDNGPUDevice does not have shader-f16 enabled");return e==="auto"?t?"fp16":"fp32":e}var Aa=class{_device;usedKernels=new Set;get weightBuffers(){return[...this._packedConvs.values()].flatMap(e=>[e.weights,e.bias])}precision;kernelSetting;gemm;maxSpatialInputBlocks;subgroupsAvailable;subgroupMatrix;subgroupMatrixOutputFragments;subgroupMatrixSubPixel;subgroupMatrixDoubleBuffer;_subgroupMatrixFixedFragments;_model;_gemmByOutputBlocks=new Map;_packedConvs=new Map;_pipelineCache;_executionCache=new Map;_retiredExecutions=new Set;_clock=0;_shapeCacheSize;_profileNextExecution=!1;_profileOperations=0;_resources=new Oi;_disposed=!1;constructor(e,t,r={}){this._device=e,this._pipelineCache=Nb(e),this.precision=Zh(e,r.precision??"auto"),this.kernelSetting=r.kernel??"auto";let i=r.gemm?.workgroupSize??[8,8];if(this.gemm=Object.freeze({tilePolicy:r.gemm?.tilePolicy??(r.gemm?.workgroupSize?"fixed":"output-aligned"),decoderLoad:r.gemm?.decoderLoad??"per-load",decoderSubPixel:r.gemm?.decoderSubPixel??this.precision==="fp16",unrollRows:r.gemm?.unrollRows??!1,poolLayout:r.gemm?.poolLayout??"channels",sharedLayout:r.gemm?.sharedLayout??(this.precision==="fp16"?"padded-input":"padded"),accumulationOrder:r.gemm?.accumulationOrder??"k-major",finalLayer:r.gemm?.finalLayer??"shared-auto",loadMode:r.gemm?.loadMode??"native",addressMode:r.gemm?.addressMode??"incremental",weightLayout:r.gemm?.weightLayout??"k-major",rowsPerThread:r.gemm?.rowsPerThread??8,workgroupSize:Object.freeze([i[0],i[1]])}),!["fixed","output-aligned"].includes(this.gemm.tilePolicy))throw new Error(`Unsupported GEMM tile policy: ${this.gemm.tilePolicy}`);if(!["per-load","source-first"].includes(this.gemm.decoderLoad))throw new Error(`Unsupported GEMM decoder load: ${this.gemm.decoderLoad}`);if(!["spatial","channels"].includes(this.gemm.poolLayout))throw new Error(`Unsupported GEMM pool layout: ${this.gemm.poolLayout}`);if(!["linear","padded","padded-input","padded-weights"].includes(this.gemm.sharedLayout))throw new Error(`Unsupported GEMM shared layout: ${this.gemm.sharedLayout}`);if(!["k-major","row-major"].includes(this.gemm.accumulationOrder))throw new Error(`Unsupported GEMM accumulation order: ${this.gemm.accumulationOrder}`);if(!["direct","shared-input","shared-input-weights","shared-auto"].includes(this.gemm.finalLayer))throw new Error(`Unsupported GEMM final layer: ${this.gemm.finalLayer}`);if(!["native","packed-weights","packed-all"].includes(this.gemm.loadMode))throw new Error(`Unsupported GEMM load mode: ${this.gemm.loadMode}`);if(!["analytic","incremental","base-offset"].includes(this.gemm.addressMode))throw new Error(`Unsupported GEMM address mode: ${this.gemm.addressMode}`);if(!["output-major","k-major"].includes(this.gemm.weightLayout))throw new Error(`Unsupported GEMM weight layout: ${this.gemm.weightLayout}`);let s=it(this.gemm),n=(Yt(this.gemm).input+Yt(this.gemm).weights)*4*(this.precision==="fp16"?2:4);if(![2,4,8].includes(s.rowsPerThread)||![4,8,16].includes(s.workgroupX)||![4,8].includes(s.workgroupY)||i.length!==2||s.workgroupX>e.limits.maxComputeWorkgroupSizeX||s.workgroupY>e.limits.maxComputeWorkgroupSizeY||s.workgroupX*s.workgroupY>e.limits.maxComputeInvocationsPerWorkgroup||n>e.limits.maxComputeWorkgroupStorageSize)throw new Error("Unsupported GEMM tile configuration for this OIDNGPUDevice");this.subgroupsAvailable=e.features.has("subgroups");let l=r.subgroupMatrix??{},a=this.kernelSetting==="subgroup-matrix"?jh(e,l.accumulation):void 0;this.subgroupMatrixOutputFragments=a?ew(a,l):0,this.subgroupMatrixSubPixel=l.subPixel??!0,this._subgroupMatrixFixedFragments=l.outputFragments!==void 0;let p=c=>a!==void 0&&tw(a,this.subgroupMatrixOutputFragments,this.subgroupMatrixSubPixel,c)<=e.limits.maxComputeWorkgroupStorageSize;if(a&&l.outputFragments===void 0&&!p(!1)&&(this.subgroupMatrixOutputFragments=1),this.subgroupMatrixDoubleBuffer=(l.doubleBuffer??!1)&&p(!0),a&&Or*a.subgroupSize<=e.limits.maxComputeInvocationsPerWorkgroup&&Or*a.subgroupSize<=e.limits.maxComputeWorkgroupSizeX&&p(this.subgroupMatrixDoubleBuffer)&&(this.subgroupMatrix=a),this.maxSpatialInputBlocks=this.precision==="fp16"&&e.limits.maxComputeInvocationsPerWorkgroup>=fe*fe&&e.limits.maxComputeWorkgroupSizeX>=fe&&e.limits.maxComputeWorkgroupSizeY>=fe?Math.floor(e.limits.maxComputeWorkgroupStorageSize/(rt*rt*4*2)):0,this._shapeCacheSize=Math.max(1,r.shapeCacheSize??2),t.inputChannels%3!==0||t.inputChannels<3||t.inputChannels>9)throw new Error(`Native OIDN expects 3, 6, or 9 input channels, got ${t.inputChannels}`);this._model={spec:t.spec,inputChannels:t.inputChannels,outputChannels:t.outputChannels,channelsByValue:new Map(t.channelsByValue),convChannels:new Map(t.convChannels)};let u=new Map;for(let c of Li(this._model,{fuseConvPool:this._matrixPool()}).nodes){if(c.op==="fusedUpsampleConcatConv2d"){let f=this._subPixelDecoder(c);f&&u.set(c.conv.id,f)}if(c.op!=="conv2d"&&c.op!=="maxPool2d"&&c.op!=="fusedConvReluMaxPool2d"&&c.op!=="fusedUpsampleConcatConv2d")throw new Error(`Native OIDN descriptor ${t.spec.id} leaves unsupported ${c.op} node ${c.id} after graph optimization`)}try{for(let[c,f]of t.convTensors){let h=this._selectConvKernel(Q(f.inputChannels),c===t.spec.output),d=h==="implicit-gemm"?this.gemm.weightLayout:h==="subgroup-matrix"?"k-major":"output-major",m=u.get(c),x=m?iw(e,c,f,this.precision,m.sourceBlocks,m.upsampledSource):Lb(e,c,f,this.precision,d);this._resources.track("gpu-buffer",x.weights),this._resources.track("gpu-buffer",x.bias),this._packedConvs.set(c,x)}}catch(c){for(let f of this._packedConvs.values())this._releaseBuffer(f.weights),this._releaseBuffer(f.bias);throw this._packedConvs.clear(),c}}_pipeline(e,t){let r=this._pipelineCache.get(e);return r||(r=this._device.createComputePipeline({label:`oidn/${e}`,layout:"auto",compute:{module:this._device.createShaderModule({label:`oidn/${e}`,code:t}),entryPoint:"main"}}),this._pipelineCache.set(e,r)),r}_nodePipelineSpec(e,t,r=this.subgroupMatrixOutputFragments){if(e.op==="conv2d"){let i=t?"fp32":this.precision,s=Q(this._model.convChannels.get(e.id).inputChannels),n=Q(this._model.convChannels.get(e.id).outputChannels),l=this._gemmForOutput(n),a=this._selectConvKernel(s,t),p=l.finalLayer==="shared-input-weights"||l.finalLayer==="shared-auto"&&Wu(this.precision,s,!0)<=this._device.limits.maxComputeWorkgroupStorageSize;return a!=="subgroup-matrix"&&t&&this._model.convChannels.get(e.id).outputChannels===3&&(this.kernelSetting==="auto"||this.kernelSetting==="implicit-gemm"||this.kernelSetting==="subgroup-matrix")&&l.finalLayer!=="direct"&&this._device.limits.maxComputeWorkgroupSizeX>=8&&this._device.limits.maxComputeWorkgroupSizeY>=8&&this._device.limits.maxComputeInvocationsPerWorkgroup>=64&&Wu(this.precision,s,p)<=this._device.limits.maxComputeWorkgroupStorageSize?{key:`conv-final-rgb/${this.precision}/${e.activation}/in${s}/weights-${p?"shared":"storage"}`,kernel:"direct",code:Oh(this.precision,e.activation,s,p)}:a==="subgroup-matrix"?this._subgroupMatrixSpec(`conv/${e.activation}/in${s}/out${n}`,i,e.activation,s,n,r):{key:`conv-${a}/${this.precision}/${i}/${e.activation}/in${s}/out${n}`+(a==="implicit-gemm"?`/address-${l.addressMode}/weights-${l.weightLayout}-v1/tile-${l.workgroupSize.join("x")}-r${l.rowsPerThread}/loads-${l.loadMode}/shared-${l.sharedLayout}/acc-${l.accumulationOrder}/unroll-${Number(l.unrollRows)}`:""),kernel:a,code:a==="implicit-gemm"?Vb(this.precision,i,e.activation,s,n,l):a==="spatial"?Wb(this.precision,i,e.activation,s,n):a==="subgroup"?Hb(this.precision,i,e.activation,s,n):zb(this.precision,i,e.activation,s,n)}}if(e.op==="maxPool2d"){let i=Q(this._model.channelsByValue.get(e.id)),s=this._coalescedPool();return{key:`max-pool/${this.precision}/out${i}/${s?"channels":"spatial"}`,kernel:"direct",code:qb(this.precision,i,s)}}if(e.op==="fusedConvReluMaxPool2d"){let i=Q(this._model.convChannels.get(e.conv.id).inputChannels),s=Q(this._model.convChannels.get(e.conv.id).outputChannels);return this._matrixPool()?this._subgroupMatrixSpec(`conv-pool/${e.conv.activation}/in${i}/out${s}`,this.precision,e.conv.activation,i,s,r,void 0,!0):{key:`conv-pool/${this.precision}/${e.conv.activation}/in${i}/out${s}`,kernel:"direct",code:Gb(this.precision,e.conv.activation,i,s)}}if(e.op==="fusedUpsampleConcatConv2d"){if(e.inputs.length!==2)throw new Error(`Native fused decoder ${e.id} requires two inputs`);let i=e.inputs.map(h=>Q(this._model.channelsByValue.get(h.value))),s=e.inputs.findIndex(h=>h.upsample);if(s!==0&&s!==1)throw new Error(`Native fused decoder ${e.id} has no upsample input`);let n=i[0]+i[1],l=t?"fp32":this.precision,a=this._selectConvKernel(n,t),p=a==="subgroup"?"direct":a,u=Q(this._model.convChannels.get(e.conv.id).outputChannels);if(p==="subgroup-matrix"&&this._subPixelDecoder(e)){let h=this.subgroupMatrix;return{key:`subgroup-matrix-subpixel/${this.precision}/${l}/${h.dialect}/${h.componentType}-${h.resultType}-${h.size}-sg${h.subgroupSize}/n${r}${this.subgroupMatrixDoubleBuffer?"-db":""}/${e.conv.activation}/${i.join("+")}/out${u}/up${s}`,kernel:"subgroup-matrix",code:nw(this.precision,l,e.conv.activation,u,h,r,i,s,this.subgroupMatrixDoubleBuffer)}}if(p==="subgroup-matrix")return this._subgroupMatrixSpec(`decoder/${e.conv.activation}/${i.join("+")}/out${u}/up${s}`,l,e.conv.activation,n,u,r,{sourceBlocks:i,upsampledSource:s});let c=this._gemmForOutput(u);return{key:`decoder-${p}${p==="implicit-gemm"&&this._subPixelDecoder(e)?"-subpixel":""}/${this.precision}/${l}/${e.conv.activation}/${i.join("+")}/out${u}/up${s}`+(p==="implicit-gemm"?`/address-${c.addressMode}/weights-${c.weightLayout}-v1/tile-${c.workgroupSize.join("x")}-r${c.rowsPerThread}/loads-${c.loadMode}/shared-${c.sharedLayout}/acc-${c.accumulationOrder}/unroll-${Number(c.unrollRows)}/decoder-${c.decoderLoad}`:""),kernel:p,code:p==="implicit-gemm"?(this._subPixelDecoder(e)?Yb:Xb)(this.precision,l,e.conv.activation,i,s,u,c):p==="spatial"?Kb(this.precision,l,e.conv.activation,i,s,u):jb(this.precision,l,e.conv.activation,i,s,u)}}throw new Error(`Native OIDN does not implement unfused ${e.op} node ${e.id}`)}_subPixelDecoder(e){if(e.id===this._model.spec.output||e.inputs.length!==2)return;let t=this._selectConvKernel(Q(this._model.convChannels.get(e.conv.id).inputChannels),!1);if(!(t==="subgroup-matrix"&&this.subgroupMatrixSubPixel)&&!(t==="implicit-gemm"&&this.gemm.decoderSubPixel&&this.gemm.weightLayout==="k-major"))return;let r=e.inputs[0].upsample?0:e.inputs[1].upsample?1:-1;if(r!==-1)return{sourceBlocks:e.inputs.map(i=>Q(this._model.channelsByValue.get(i.value))),upsampledSource:r}}_subgroupMatrixSpec(e,t,r,i,s,n,l,a=!1){let p=this.subgroupMatrix;return{key:`subgroup-matrix/${this.precision}/${t}/${p.dialect}/${p.componentType}-${p.resultType}-${p.size}-sg${p.subgroupSize}/n${n}${this.subgroupMatrixDoubleBuffer?"-db":""}/${e}`,kernel:"subgroup-matrix",code:rw(this.precision,t,r,i,s,p,n,this.subgroupMatrixDoubleBuffer,l,a)}}_subgroupMatrixFragmentsFor(e){let t=Math.min(this.subgroupMatrixOutputFragments,Math.ceil(Q(e.channels)*4/this.subgroupMatrix.size));return this._subgroupMatrixFixedFragments||t===1?t:Math.ceil(e.width/zi)*Math.ceil(e.height/Hi)*Math.ceil(Q(e.channels)*4/(t*this.subgroupMatrix.size))<Jb?1:t}_gemmForOutput(e){if(this.gemm.tilePolicy!=="output-aligned"||this.gemm.workgroupSize[0]!==8||e<=0||e%16!==0)return this.gemm;let t=this._gemmByOutputBlocks.get(e);if(t)return t;let r=Object.freeze({...this.gemm,workgroupSize:Object.freeze([16,this.gemm.workgroupSize[1]])}),i=it(r),s=Yt(r),n=this._device.limits,a=i.workgroupX<=n.maxComputeWorkgroupSizeX&&i.workgroupY<=n.maxComputeWorkgroupSizeY&&i.workgroupX*i.workgroupY<=n.maxComputeInvocationsPerWorkgroup&&(s.input+s.weights)*4*(this.precision==="fp16"?2:4)<=n.maxComputeWorkgroupStorageSize?r:this.gemm;return this._gemmByOutputBlocks.set(e,a),a}_matrixPool(){let e=this.subgroupMatrix;return this._model.spec.family==="oidn-unet-large"&&this.precision==="fp16"&&e?.dialect==="naga"&&e.size===8&&e.subgroupSize===32&&e.componentType==="f16"&&e.resultType==="f32"}_coalescedPool(){return this.gemm.poolLayout==="channels"&&(this.kernelSetting==="auto"||this.kernelSetting==="implicit-gemm"||this.kernelSetting==="subgroup-matrix")}_selectConvKernel(e,t){let r=this.precision==="fp16"&&e<=this.maxSpatialInputBlocks;return this.kernelSetting==="direct"?"direct":this.kernelSetting==="spatial"?r?"spatial":"direct":this.kernelSetting==="implicit-gemm"?t?"direct":"implicit-gemm":this.kernelSetting==="subgroup"?this.subgroupsAvailable?"subgroup":"direct":this.kernelSetting==="subgroup-matrix"?t?this.subgroupMatrix?.dialect==="naga"?"subgroup-matrix":"direct":this.subgroupMatrix?"subgroup-matrix":"implicit-gemm":t?"direct":"implicit-gemm"}_nodePipeline(e,t){let{key:r,code:i}=this._nodePipelineSpec(e,t);return this._pipeline(r,i)}_createExecution(e,t){let r=this._matrixPool(),i=zu(this._model,e,t,{fuseConvPool:r}),s=new Map,n=[],l=[],a=new Map;i.nodes.forEach((c,f)=>{for(let h of ow(c))a.set(h,f)}),a.set(i.spec.output,i.nodes.length);let p=[],u=c=>(p.push(c),this._resources.track("gpu-buffer",c));try{let c=(b,T,_,S)=>{let A=Fb(T,_),I=l.filter(C=>C.lastUse<S),P=I.filter(C=>C.capacity>=A).sort((C,B)=>C.capacity-B.capacity)[0];!P&&r&&(P=I.sort((C,B)=>B.capacity-C.capacity)[0]),P||(P={capacity:0,lastUse:-2,values:[]},l.push(P)),P.capacity=Math.max(P.capacity,A),P.lastUse=a.get(b)??-1,P.values.push(b)};c(i.spec.input,i.inputShape,this.precision==="fp16"?2:4,-1),i.plannedNodes.forEach(({node:b,outputShape:T},_)=>{let S=b.id===i.spec.output;c(b.id,T,S||this.precision==="fp32"?4:2,_)}),l.forEach((b,T)=>{let _=u(this._device.createBuffer({label:`oidn/activation/${e}x${t}/${T}`,size:qu(b.capacity,4),usage:Ie.STORAGE|Ie.COPY_SRC|Ie.COPY_DST}));n.push({buffer:_,capacity:b.capacity});for(let S of b.values)s.set(S,_)});let f=this._model.inputChannels/3,h=`pack/${this.precision}/${f}`,d=this._pipeline(h,Yu(this.precision,f)),m=[],x=[],v=[],g=[],y=[];i.plannedNodes.forEach(({node:b,outputShape:T},_)=>{let S=b.id===i.spec.output,A=this.subgroupMatrix?this._subgroupMatrixFragmentsFor(T):0,I=this._nodePipelineSpec(b,S,A);I.kernel&&this.usedKernels.add(I.kernel);let P=this._pipeline(I.key,I.code);m.push(P),x.push(I.kernel??"direct"),v.push(I.kernel==="subgroup-matrix"?A:0);let C=s.get(b.id),B,R,E;if(b.op==="conv2d"){let M=i.valueShapes.get(b.input);E=b.id,R=[M.width,M.height,T.width,T.height,Q(M.channels),Q(T.channels)];let oe=this._packedConvs.get(E);B=[{binding:0,resource:{buffer:s.get(b.input)}},{binding:1,resource:{buffer:oe.weights}},{binding:2,resource:{buffer:oe.bias}},{binding:3,resource:{buffer:C}}]}else if(b.op==="maxPool2d"){let M=i.valueShapes.get(b.input);R=[M.width,M.height,T.width,T.height,Q(T.channels)],B=[{binding:0,resource:{buffer:s.get(b.input)}},{binding:1,resource:{buffer:C}}]}else if(b.op==="fusedConvReluMaxPool2d"){let M=i.valueShapes.get(b.input);E=b.conv.id,R=[M.width,M.height,T.width,T.height,Q(M.channels),Q(T.channels)];let oe=this._packedConvs.get(E);B=[{binding:0,resource:{buffer:s.get(b.input)}},{binding:1,resource:{buffer:oe.weights}},{binding:2,resource:{buffer:oe.bias}},{binding:3,resource:{buffer:C}}]}else if(b.op==="fusedUpsampleConcatConv2d"){E=b.conv.id;let M=i.valueShapes.get(b.inputs[0].value),oe=i.valueShapes.get(b.inputs[1].value);R=[T.width,T.height,Q(T.channels),Q(this._model.convChannels.get(E).inputChannels),M.width,M.height,oe.width,oe.height];let we=this._packedConvs.get(E);B=[{binding:0,resource:{buffer:s.get(b.inputs[0].value)}},{binding:1,resource:{buffer:s.get(b.inputs[1].value)}},{binding:2,resource:{buffer:we.weights}},{binding:3,resource:{buffer:we.bias}},{binding:4,resource:{buffer:C}}]}else throw new Error(`Unexpected native node ${b.op}`);let N=u(Hh(this._device,`oidn/${b.id}/params/${e}x${t}`,R));y.push(N),B.push({binding:B.length,resource:{buffer:N}}),g.push(this._device.createBindGroup({label:`oidn/${b.id}/bindings`,layout:P.getBindGroupLayout(0),entries:B}))});let w=u(Hh(this._device,`oidn/input/params/${e}x${t}`,[e,t,Q(this._model.inputChannels),this._model.inputChannels]));return y.push(w),{plan:i,valueBuffers:s,slots:n,nodeBindings:g,nodePipelines:m,nodeKernels:x,nodeFragments:v,inputPipeline:d,inputUniform:w,ownedBuffers:y,lastUsed:++this._clock}}catch(c){for(let f of p)this._releaseBuffer(f);throw c}}_execution(e,t){if(this._disposed)throw new Error("Native OIDN executor is disposed");let r=`${e}x${t}`,i=this._executionCache.get(r);if(!i&&(i=this._createExecution(e,t),this._executionCache.set(r,i),this._executionCache.size>this._shapeCacheSize)){let s=[...this._executionCache.entries()].filter(([n])=>n!==r).sort((n,l)=>n[1].lastUsed-l[1].lastUsed)[0];s&&(this._executionCache.delete(s[0]),this._retireExecution(s[1]))}return i.lastUsed=++this._clock,i}_retireExecution(e){this._destroyExecution(e)}prewarm(e){for(let t of e)this._execution(t.width,t.height)}profileNextExecution(){return this._device.features.has("timestamp-query")?(this._profileNextExecution=!0,!0):!1}packedInputBuffer(e,t){let r=this._execution(e,t);return r.valueBuffers.get(r.plan.spec.input)}executePacked(e,t){return this._execute([],e,t,!0)}execute(e,t,r){return this._execute(e,t,r,!1)}_execute(e,t,r,i){let s=this._model.inputChannels/3;if(!i&&e.length!==s)throw new Error(`Native OIDN expected ${s} input buffers, got ${e.length}`);let n=this._execution(t,r),l=[...i?[]:["input-pack"],...n.plan.nodes.map(m=>m.id)],a=this._profileNextExecution&&this._device.features.has("timestamp-query");this._profileNextExecution=!1;let p=l.length*2,u=a?this._resources.track("gpu-query-set",this._device.createQuerySet({type:"timestamp",count:p})):void 0,c=p*8,f,h;try{f=a?this._resources.track("gpu-buffer",this._device.createBuffer({label:`oidn/profile/resolve/${t}x${r}`,size:c,usage:Ie.QUERY_RESOLVE|Ie.COPY_SRC})):void 0,h=a?this._resources.track("gpu-buffer",this._device.createBuffer({label:`oidn/profile/readback/${t}x${r}`,size:c,usage:Ie.COPY_DST|Ie.MAP_READ})):void 0}catch(m){throw this._releaseBuffer(h),this._releaseBuffer(f),this._releaseQuerySet(u),m}let d=(m,x)=>({label:m,...u?{timestampWrites:{querySet:u,beginningOfPassWriteIndex:x*2,endOfPassWriteIndex:x*2+1}}:{}});try{let m=this._device.createCommandEncoder({label:`oidn/native/${t}x${r}`});if(!i){let x=e.map((y,w)=>({binding:w,resource:{buffer:y}}));x.push({binding:s,resource:{buffer:n.valueBuffers.get(n.plan.spec.input)}}),x.push({binding:s+1,resource:{buffer:n.inputUniform}});let v=this._device.createBindGroup({label:"oidn/input/bindings",layout:n.inputPipeline.getBindGroupLayout(0),entries:x}),g=m.beginComputePass(d("oidn/input-pack",0));g.setPipeline(n.inputPipeline),g.setBindGroup(0,v),g.dispatchWorkgroups(Math.ceil(t/ye),Math.ceil(r/ye),Q(this._model.inputChannels)),g.end()}n.plan.plannedNodes.forEach(({node:x,outputShape:v},g)=>{let y=m.beginComputePass(d(`oidn/${n.plan.nodes[g].id}`,g+(i?0:1)));if(y.setPipeline(n.nodePipelines[g]),y.setBindGroup(0,n.nodeBindings[g]),x.op==="maxPool2d"&&this._coalescedPool()){let w=Math.ceil(v.width*Q(v.channels)/ye),b=this._device.limits.maxComputeWorkgroupsPerDimension;y.dispatchWorkgroups(Math.min(w,b),Math.ceil(v.height/ye),Math.ceil(w/b))}else if(n.nodeKernels[g]==="subgroup-matrix")y.dispatchWorkgroups(Math.ceil(v.width*(x.op==="fusedConvReluMaxPool2d"?2:1)/zi),Math.ceil(v.height*(x.op==="fusedConvReluMaxPool2d"?2:1)/Hi),Math.ceil(Q(v.channels)/(n.nodeFragments[g]*this.subgroupMatrix.size/4)));else if(n.nodeKernels[g]==="implicit-gemm"){let w=it(this._gemmForOutput(Q(v.channels))),b=x.op==="fusedUpsampleConcatConv2d"&&this._subPixelDecoder(x)!==void 0;y.dispatchWorkgroups(Math.ceil((b?Math.ceil(v.width/2)*Math.ceil(v.height/2):v.width*v.height)/w.tileM),Math.ceil(Q(v.channels)/w.tileNBlocks),b?4:1)}else y.dispatchWorkgroups(Math.ceil(v.width/ye),Math.ceil(v.height/ye),Q(v.channels));y.end()}),u&&(m.resolveQuerySet(u,0,p,f,0),m.copyBufferToBuffer(f,0,h,0,c)),this._device.queue.submit([m.finish()])}catch(m){throw this._releaseBuffer(h),this._releaseBuffer(f),this._releaseQuerySet(u),m}return u&&(this._profileOperations++,this._readProfile(u,f,h,l)),n.valueBuffers.get(n.plan.spec.output)}_readProfile(e,t,r,i){this._releaseQuerySet(e),this._releaseBuffer(t),this._releaseBuffer(r),this._profileOperations--}_releaseBuffer(e){this._resources.release("gpu-buffer",e,()=>e.destroy())}_releaseQuerySet(e){this._resources.release("gpu-query-set",e,()=>e.destroy())}_destroyExecution(e){e.slots.forEach(t=>this._releaseBuffer(t.buffer)),e.ownedBuffers.forEach(t=>this._releaseBuffer(t))}getResourceInfo(){return this._resources.snapshot(this._retiredExecutions.size+this._profileOperations)}dispose(){if(!this._disposed){this._disposed=!0;for(let e of this._packedConvs.values())this._releaseBuffer(e.weights),this._releaseBuffer(e.bias);this._packedConvs.clear();for(let e of this._executionCache.values())this._destroyExecution(e);this._executionCache.clear();for(let e of this._retiredExecutions)this._destroyExecution(e);this._retiredExecutions.clear()}}};var Jh=new WeakMap,Wn=class extends Aa{_lastExecutionProfile;constructor(e,t,r){super(e,t,r)}execute(e,t,r){return super.execute(e,t,r)}get _asyncDevice(){return this._device}get _pipelinePromises(){let e=Jh.get(this._device);return e||(e=new Map,Jh.set(this._device,e)),e}getLastExecutionProfile(){return this._lastExecutionProfile}_pipelineAsync(e,t){let r=this._pipelineCache.get(e);if(r)return Promise.resolve(r);let i=this._pipelinePromises.get(e);if(i)return i;let s=this._asyncDevice.createComputePipelineAsync({label:`oidn/${e}`,layout:"auto",compute:{module:this._device.createShaderModule({label:`oidn/${e}`,code:t}),entryPoint:"main"}}).then(n=>(this._pipelineCache.set(e,n),this._pipelinePromises.delete(e),n),n=>{throw this._pipelinePromises.delete(e),n});return this._pipelinePromises.set(e,s),s}_retireExecution(e){this._retiredExecutions.add(e),this._asyncDevice.queue.onSubmittedWorkDone().catch(()=>{}).then(()=>{this._retiredExecutions.delete(e),this._destroyExecution(e)})}async prepare(){if(this._disposed)throw new Error("Native OIDN executor is disposed");let e=Li(this._model,{fuseConvPool:!1}),t=this._model.inputChannels/3,r=[{key:`pack/${this.precision}/${t}`,code:Yu(this.precision,t)},...e.nodes.map(i=>this._nodePipelineSpec(i,i.id===e.spec.output))];await Promise.all(r.map(({key:i,code:s})=>this._pipelineAsync(i,s)))}_readProfile(e,t,r,i){this._lastExecutionProfile=(async()=>{try{await r.mapAsync(Hu.READ);let s=new Uint32Array(r.getMappedRange()),n=a=>s[a*2+1]*4294967296+s[a*2],l=i.map((a,p)=>({id:a,durationMs:(n(p*2+1)-n(p*2))/1e6}));return{totalMs:l.reduce((a,p)=>a+p.durationMs,0),layers:l}}finally{r.mapState==="mapped"&&r.unmap(),this._releaseQuerySet(e),this._releaseBuffer(t),this._releaseBuffer(r),this._profileOperations--}})()}async executeCPU(e,t,r){let i=t*r*this._model.inputChannels;if(e.length!==i)throw new Error(`Native OIDN CPU input has ${e.length} values, expected ${i}`);let s=this._execution(t,r),n=this._model.inputChannels/3,l=t*r;s.cpuInputBuffers||(s.cpuInputBuffers=Array.from({length:n},(f,h)=>{let d=this._device.createBuffer({label:`oidn/cpu-input/${t}x${r}/${h}`,size:l*16,usage:Ie.STORAGE|Ie.COPY_DST});return this._resources.track("gpu-buffer",d),s.ownedBuffers.push(d),d}),s.cpuReadbackBuffer=this._device.createBuffer({label:`oidn/cpu-readback/${t}x${r}`,size:l*16,usage:Ie.COPY_DST|Ie.MAP_READ}),this._resources.track("gpu-buffer",s.cpuReadbackBuffer),s.ownedBuffers.push(s.cpuReadbackBuffer));for(let f=0;f<n;f++){let h=new Float32Array(l*4);for(let d=0;d<l;d++){let m=d*this._model.inputChannels+f*3,x=d*4;h[x]=e[m],h[x+1]=e[m+1],h[x+2]=e[m+2]}this._device.queue.writeBuffer(s.cpuInputBuffers[f],0,new Uint8Array(h.buffer,h.byteOffset,h.byteLength))}let a=super.execute(s.cpuInputBuffers,t,r),p=this._device.createCommandEncoder({label:`oidn/cpu-readback/${t}x${r}`});p.copyBufferToBuffer(a,0,s.cpuReadbackBuffer,0,l*16),this._device.queue.submit([p.finish()]),await s.cpuReadbackBuffer.mapAsync(Hu.READ);let u=new Float32Array(s.cpuReadbackBuffer.getMappedRange()),c=new Float32Array(l*3);for(let f=0;f<l;f++)c[f*3]=u[f*4],c[f*3+1]=u[f*4+1],c[f*3+2]=u[f*4+2];return s.cpuReadbackBuffer.unmap(),c}};var em="This is not an object",tm="This is not a Float16Array object",ju="This constructor is not a subclass of Float16Array",Pa="The constructor property value is not an object",rm="Species constructor didn't return TypedArray object",im="Derived constructor created TypedArray object which was too small length",Vi="Attempting to access detached ArrayBuffer",Vn="Cannot convert undefined or null to object",Ba="Cannot mix BigInt and other types, use explicit conversions",Ku="@@iterator property is not callable",Qu="Reduce of empty array with no initial value",nm="The comparison function must be either a function or undefined",Ra="Offset is out of bounds";function ue(o){return(e,...t)=>Ne(o,e,t)}function Gi(o,e){return ue(mr(o,e).get)}var{apply:Ne,construct:qi,defineProperty:Ju,get:Ma,getOwnPropertyDescriptor:mr,getPrototypeOf:Xi,has:Gn,ownKeys:Da,set:ec,setPrototypeOf:tc}=Reflect,om=Proxy,{EPSILON:sm,MAX_SAFE_INTEGER:rc,isFinite:Na,isNaN:jt}=Number,{iterator:Ke,species:am,toStringTag:Fa,for:lm}=Symbol,Ur=Object,{create:qn,defineProperty:zr,freeze:um,is:ic}=Ur,Zu=Ur.prototype,cm=Zu.__lookupGetter__?ue(Zu.__lookupGetter__):(o,e)=>{if(o==null)throw ce(Vn);let t=Ur(o);do{let r=mr(t,e);if(r!==void 0)return Dt(r,"get")?r.get:void 0}while((t=Xi(t))!==null)},Dt=Ur.hasOwn||ue(Zu.hasOwnProperty),pm=Array,nc=pm.isArray,ka=pm.prototype,fm=ue(ka.join),dm=ue(ka.push),hm=ue(ka.toLocaleString),Xn=ka[Ke],mm=ue(Xn),{abs:gm,trunc:La}=Math,Yi=ArrayBuffer,xm=Yi.isView,ym=Yi.prototype,vm=ue(ym.slice),bm=Gi(ym,"byteLength"),Ea=typeof SharedArrayBuffer<"u"?SharedArrayBuffer:null,wm=Ea&&Gi(Ea.prototype,"byteLength"),Oa=Xi(Uint8Array),sw=Oa.from,ve=Oa.prototype,_m=ve[Ke],Tm=ue(ve.keys),Sm=ue(ve.values),Im=ue(ve.entries),$m=ue(ve.set),oc=ue(ve.reverse),Cm=ue(ve.fill),Am=ue(ve.copyWithin),sc=ue(ve.sort),ji=ue(ve.slice),Pm=ue(ve.subarray),be=Gi(ve,"buffer"),gr=Gi(ve,"byteOffset"),Y=Gi(ve,"length"),ac=Gi(ve,Fa),Bm=Uint8Array,Fe=Uint16Array,lc=(...o)=>Ne(sw,Fe,o),Ua=Uint32Array,Rm=Float32Array,Kt=Xi([][Ke]()),Ki=ue(Kt.next),Em=ue((function*(){})().next),Mm=Xi(Kt),Dm=DataView.prototype,JP=ue(Dm.getUint16),eB=ue(Dm.setUint16),ce=TypeError,za=RangeError,uc=WeakSet,Nm=uc.prototype,Fm=ue(Nm.add),km=ue(Nm.has),Qi=WeakMap,cc=Qi.prototype,Zi=ue(cc.get),Lm=ue(cc.has),Yn=ue(cc.set);var Om=new Qi,aw=qn(null,{next:{value:function(){let e=Zi(Om,this);return Ki(e)}},[Ke]:{value:function(){return this}}});function jn(o){if(o[Ke]===Xn&&Kt.next===Ki)return o;let e=qn(aw);return Yn(Om,e,mm(o)),e}var Um=new Qi,zm=qn(Mm,{next:{value:function(){let e=Zi(Um,this);return Em(e)},writable:!0,configurable:!0}});for(let o of Da(Kt))o!=="next"&&zr(zm,o,mr(Kt,o));function pc(o){let e=qn(zm);return Yn(Um,e,o),e}function Hr(o){return o!==null&&typeof o=="object"||typeof o=="function"}function fc(o){return o!==null&&typeof o=="object"}function Kn(o){return ac(o)!==void 0}function Ha(o){let e=ac(o);return e==="BigInt64Array"||e==="BigUint64Array"}function lw(o){try{return nc(o)?!1:(bm(o),!0)}catch{return!1}}function dc(o){if(Ea===null)return!1;try{return wm(o),!0}catch{return!1}}function Hm(o){return lw(o)||dc(o)}function hc(o){return nc(o)?o[Ke]===Xn&&Kt.next===Ki:!1}function Wm(o){return Kn(o)?o[Ke]===_m&&Kt.next===Ki:!1}function Qn(o){if(typeof o!="string")return!1;let e=+o;return o!==e+""||!Na(e)?!1:e===La(e)}var Zn=lm("__Float16Array__");function Vm(o){if(!fc(o))return!1;let e=Xi(o);if(!fc(e))return!1;let t=e.constructor;if(t===void 0)return!1;if(!Hr(t))throw ce(Pa);return Gn(t,Zn)}var mc=1/sm;function uw(o){return o+mc-mc}var qm=6103515625e-14,cw=65504,Xm=.0009765625,Gm=Xm*qm,pw=Xm*mc;function fw(o){let e=+o;if(!Na(e)||e===0)return e;let t=e>0?1:-1,r=gm(e);if(r<qm)return t*uw(r/Gm)*Gm;let i=(1+pw)*r,s=i-(i-r);return s>cw||jt(s)?t*(1/0):t*s}var Ym=new Yi(4),jm=new Rm(Ym),Km=new Ua(Ym),mt=new Fe(512),gt=new Bm(512);for(let o=0;o<256;++o){let e=o-127;e<-24?(mt[o]=0,mt[o|256]=32768,gt[o]=24,gt[o|256]=24):e<-14?(mt[o]=1024>>-e-14,mt[o|256]=1024>>-e-14|32768,gt[o]=-e-1,gt[o|256]=-e-1):e<=15?(mt[o]=e+15<<10,mt[o|256]=e+15<<10|32768,gt[o]=13,gt[o|256]=13):e<128?(mt[o]=31744,mt[o|256]=64512,gt[o]=24,gt[o|256]=24):(mt[o]=31744,mt[o|256]=64512,gt[o]=13,gt[o|256]=13)}function xt(o){jm[0]=fw(o);let e=Km[0],t=e>>23&511;return mt[t]+((e&8388607)>>gt[t])}var gc=new Ua(2048);for(let o=1;o<1024;++o){let e=o<<13,t=0;for(;(e&8388608)===0;)e<<=1,t-=8388608;e&=-8388609,t+=947912704,gc[o]=e|t}for(let o=1024;o<2048;++o)gc[o]=939524096+(o-1024<<13);var Ji=new Ua(64);for(let o=1;o<31;++o)Ji[o]=o<<23;Ji[31]=1199570944;Ji[32]=2147483648;for(let o=33;o<63;++o)Ji[o]=2147483648+(o-32<<23);Ji[63]=3347054592;var Qm=new Fe(64);for(let o=1;o<64;++o)o!==32&&(Qm[o]=1024);function re(o){let e=o>>10;return Km[0]=gc[Qm[e]+(o&1023)]+Ji[e],jm[0]}function Nt(o){let e=+o;return jt(e)||e===0?0:La(e)}function Wa(o){let e=Nt(o);return e<0?0:e<rc?e:rc}function Jn(o,e){if(!Hr(o))throw ce(em);let t=o.constructor;if(t===void 0)return e;if(!Hr(t))throw ce(Pa);let r=t[am];return r??e}function en(o){if(dc(o))return!1;try{return vm(o,0,0),!1}catch{}return!0}function xc(o,e){let t=jt(o),r=jt(e);if(t&&r)return 0;if(t)return 1;if(r||o<e)return-1;if(o>e)return 1;if(o===0&&e===0){let i=ic(o,0),s=ic(e,0);if(!i&&s)return-1;if(i&&!s)return 1}return 0}var yc=2,Ga=new Qi;function Wr(o){return Lm(Ga,o)||!xm(o)&&Vm(o)}function Z(o){if(!Wr(o))throw ce(tm)}function Va(o,e){let t=Wr(o),r=Kn(o);if(!t&&!r)throw ce(rm);if(typeof e=="number"){let i;if(t){let s=F(o);i=Y(s)}else i=Y(o);if(i<e)throw ce(im)}if(Ha(o))throw ce(Ba)}function F(o){let e=Zi(Ga,o);if(e!==void 0){let i=be(e);if(en(i))throw ce(Vi);return e}let t=o.buffer;if(en(t))throw ce(Vi);let r=qi(Qe,[t,o.byteOffset,o.length],o.constructor);return Zi(Ga,r)}function Zm(o){let e=Y(o),t=[];for(let r=0;r<e;++r)t[r]=re(o[r]);return t}var Jm=new uc;for(let o of Da(ve)){if(o===Fa)continue;let e=mr(ve,o);Dt(e,"get")&&typeof e.get=="function"&&Fm(Jm,e.get)}var dw=um({get(o,e,t){return Qn(e)&&Dt(o,e)?re(Ma(o,e)):km(Jm,cm(o,e))?Ma(o,e):Ma(o,e,t)},set(o,e,t,r){return Qn(e)&&Dt(o,e)?ec(o,e,xt(t)):ec(o,e,t,r)},getOwnPropertyDescriptor(o,e){if(Qn(e)&&Dt(o,e)){let t=mr(o,e);return t.value=re(t.value),t}return mr(o,e)},defineProperty(o,e,t){return Qn(e)&&Dt(o,e)&&Dt(t,"value")?(t.value=xt(t.value),Ju(o,e,t)):Ju(o,e,t)}}),Qe=class o{constructor(e,t,r){let i;if(Wr(e))i=qi(Fe,[F(e)],new.target);else if(Hr(e)&&!Hm(e)){let n,l;if(Kn(e)){n=e,l=Y(e);let a=be(e);if(en(a))throw ce(Vi);if(Ha(e))throw ce(Ba);let p=new Yi(l*yc);i=qi(Fe,[p],new.target)}else{let a=e[Ke];if(a!=null&&typeof a!="function")throw ce(Ku);a!=null?hc(e)?(n=e,l=e.length):(n=[...e],l=n.length):(n=e,l=Wa(n.length)),i=qi(Fe,[l],new.target)}for(let a=0;a<l;++a)i[a]=xt(n[a])}else i=qi(Fe,arguments,new.target);let s=new om(i,dw);return Yn(Ga,s,i),s}static from(e,...t){let r=this;if(!Gn(r,Zn))throw ce(ju);if(r===o){if(Wr(e)&&t.length===0){let u=F(e),c=new Fe(be(u),gr(u),Y(u));return new o(be(ji(c)))}if(t.length===0)return new o(be(lc(e,xt)));let a=t[0],p=t[1];return new o(be(lc(e,function(u,...c){return xt(Ne(a,this,[u,...jn(c)]))},p)))}let i,s,n=e[Ke];if(n!=null&&typeof n!="function")throw ce(Ku);if(n!=null)hc(e)?(i=e,s=e.length):Wm(e)?(i=e,s=Y(e)):(i=[...e],s=i.length);else{if(e==null)throw ce(Vn);i=Ur(e),s=Wa(i.length)}let l=new r(s);if(t.length===0)for(let a=0;a<s;++a)l[a]=i[a];else{let a=t[0],p=t[1];for(let u=0;u<s;++u)l[u]=Ne(a,p,[i[u],u])}return l}static of(...e){let t=this;if(!Gn(t,Zn))throw ce(ju);let r=e.length;if(t===o){let s=new o(r),n=F(s);for(let l=0;l<r;++l)n[l]=xt(e[l]);return s}let i=new t(r);for(let s=0;s<r;++s)i[s]=e[s];return i}keys(){Z(this);let e=F(this);return Tm(e)}values(){Z(this);let e=F(this);return pc((function*(){for(let t of Sm(e))yield re(t)})())}entries(){Z(this);let e=F(this);return pc((function*(){for(let[t,r]of Im(e))yield[t,re(r)]})())}at(e){Z(this);let t=F(this),r=Y(t),i=Nt(e),s=i>=0?i:r+i;if(!(s<0||s>=r))return re(t[s])}with(e,t){Z(this);let r=F(this),i=Y(r),s=Nt(e),n=s>=0?s:i+s,l=+t;if(n<0||n>=i)throw za(Ra);let a=new Fe(be(r),gr(r),Y(r)),p=new o(be(ji(a))),u=F(p);return u[n]=xt(l),p}map(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0],n=Jn(r,o);if(n===o){let a=new o(i),p=F(a);for(let u=0;u<i;++u){let c=re(r[u]);p[u]=xt(Ne(e,s,[c,u,this]))}return a}let l=new n(i);Va(l,i);for(let a=0;a<i;++a){let p=re(r[a]);l[a]=Ne(e,s,[p,a,this])}return l}filter(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0],n=[];for(let p=0;p<i;++p){let u=re(r[p]);Ne(e,s,[u,p,this])&&dm(n,u)}let l=Jn(r,o),a=new l(n);return Va(a),a}reduce(e,...t){Z(this);let r=F(this),i=Y(r);if(i===0&&t.length===0)throw ce(Qu);let s,n;t.length===0?(s=re(r[0]),n=1):(s=t[0],n=0);for(let l=n;l<i;++l)s=e(s,re(r[l]),l,this);return s}reduceRight(e,...t){Z(this);let r=F(this),i=Y(r);if(i===0&&t.length===0)throw ce(Qu);let s,n;t.length===0?(s=re(r[i-1]),n=i-2):(s=t[0],n=i-1);for(let l=n;l>=0;--l)s=e(s,re(r[l]),l,this);return s}forEach(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=0;n<i;++n)Ne(e,s,[re(r[n]),n,this])}find(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=0;n<i;++n){let l=re(r[n]);if(Ne(e,s,[l,n,this]))return l}}findIndex(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=0;n<i;++n){let l=re(r[n]);if(Ne(e,s,[l,n,this]))return n}return-1}findLast(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=i-1;n>=0;--n){let l=re(r[n]);if(Ne(e,s,[l,n,this]))return l}}findLastIndex(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=i-1;n>=0;--n){let l=re(r[n]);if(Ne(e,s,[l,n,this]))return n}return-1}every(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=0;n<i;++n)if(!Ne(e,s,[re(r[n]),n,this]))return!1;return!0}some(e,...t){Z(this);let r=F(this),i=Y(r),s=t[0];for(let n=0;n<i;++n)if(Ne(e,s,[re(r[n]),n,this]))return!0;return!1}set(e,...t){Z(this);let r=F(this),i=Nt(t[0]);if(i<0)throw za(Ra);if(e==null)throw ce(Vn);if(Ha(e))throw ce(Ba);if(Wr(e))return $m(F(this),F(e),i);if(Kn(e)){let a=be(e);if(en(a))throw ce(Vi)}let s=Y(r),n=Ur(e),l=Wa(n.length);if(i===1/0||l+i>s)throw za(Ra);for(let a=0;a<l;++a)r[a+i]=xt(n[a])}reverse(){Z(this);let e=F(this);return oc(e),this}toReversed(){Z(this);let e=F(this),t=new Fe(be(e),gr(e),Y(e)),r=new o(be(ji(t))),i=F(r);return oc(i),r}fill(e,...t){Z(this);let r=F(this);return Cm(r,xt(e),...jn(t)),this}copyWithin(e,t,...r){Z(this);let i=F(this);return Am(i,e,t,...jn(r)),this}sort(e){Z(this);let t=F(this),r=e!==void 0?e:xc;return sc(t,(i,s)=>r(re(i),re(s))),this}toSorted(e){Z(this);let t=F(this);if(e!==void 0&&typeof e!="function")throw new ce(nm);let r=e!==void 0?e:xc,i=new Fe(be(t),gr(t),Y(t)),s=new o(be(ji(i))),n=F(s);return sc(n,(l,a)=>r(re(l),re(a))),s}slice(e,t){Z(this);let r=F(this),i=Jn(r,o);if(i===o){let d=new Fe(be(r),gr(r),Y(r));return new o(be(ji(d,e,t)))}let s=Y(r),n=Nt(e),l=t===void 0?s:Nt(t),a;n===-1/0?a=0:n<0?a=s+n>0?s+n:0:a=s<n?s:n;let p;l===-1/0?p=0:l<0?p=s+l>0?s+l:0:p=s<l?s:l;let u=p-a>0?p-a:0,c=new i(u);if(Va(c,u),u===0)return c;let f=be(r);if(en(f))throw ce(Vi);let h=0;for(;a<p;)c[h]=re(r[a]),++a,++h;return c}subarray(e,t){Z(this);let r=F(this),i=Jn(r,o),s=new Fe(be(r),gr(r),Y(r)),n=Pm(s,e,t),l=new i(be(n),gr(n),Y(n));return Va(l),l}indexOf(e,...t){Z(this);let r=F(this),i=Y(r),s=Nt(t[0]);if(s===1/0)return-1;s<0&&(s+=i,s<0&&(s=0));for(let n=s;n<i;++n)if(Dt(r,n)&&re(r[n])===e)return n;return-1}lastIndexOf(e,...t){Z(this);let r=F(this),i=Y(r),s=t.length>=1?Nt(t[0]):i-1;if(s===-1/0)return-1;s>=0?s=s<i-1?s:i-1:s+=i;for(let n=s;n>=0;--n)if(Dt(r,n)&&re(r[n])===e)return n;return-1}includes(e,...t){Z(this);let r=F(this),i=Y(r),s=Nt(t[0]);if(s===1/0)return!1;s<0&&(s+=i,s<0&&(s=0));let n=jt(e);for(let l=s;l<i;++l){let a=re(r[l]);if(n&&jt(a)||a===e)return!0}return!1}join(e){Z(this);let t=F(this),r=Zm(t);return fm(r,e)}toLocaleString(...e){Z(this);let t=F(this),r=Zm(t);return hm(r,...jn(e))}get[Fa](){if(Wr(this))return"Float16Array"}};zr(Qe,"BYTES_PER_ELEMENT",{value:yc});zr(Qe,Zn,{});tc(Qe,Oa);var qa=Qe.prototype;zr(qa,"BYTES_PER_ELEMENT",{value:yc});zr(qa,Ke,{value:qa.values,writable:!0,configurable:!0});tc(qa,ve);var eg=new WeakMap;function hw(o,e){let t=eg.get(o);t||(t=new Map,eg.set(o,t));let r=t.get(e);if(!r){let i=o.createShaderModule({label:`oidn/webnn/input-pack/${e}`,code:xw(e)}),s=o.createShaderModule({label:"oidn/webnn/output-unpack",code:yw()});r={input:o.createComputePipeline({label:`oidn/webnn/input-pack/${e}`,layout:"auto",compute:{module:i,entryPoint:"main"}}),output:o.createComputePipeline({label:"oidn/webnn/output-unpack",layout:"auto",compute:{module:s,entryPoint:"main"}})},t.set(e,r)}return r}var xr=8;function rg(o,e){return Math.ceil(o/e)*e}function mw(o,e,t,r){let i=o.createBuffer({label:e,size:rg(t.byteLength,4),usage:r,mappedAtCreation:!0});return new Uint8Array(i.getMappedRange()).set(new Uint8Array(t.buffer,t.byteOffset,t.byteLength)),i.unmap(),i}function tg(o,e,t){let r=new Uint32Array(rg(t.length,4));return r.set(t),mw(o,e,r,GPUBufferUsage.UNIFORM)}function vc(o,e,t,r){return!!o?.[e]?.[t]?.dataTypes?.includes?.(r)}function gw(o,e){if(e==="fp16"&&o.desc.dataType==="Float16")return new Uint8Array(o.data.buffer,o.data.byteOffset,o.data.byteLength);if(e==="fp32"&&o.desc.dataType==="Float32")return new Uint8Array(o.data.buffer,o.data.byteOffset,o.data.byteLength);let t=o.desc.dataType==="Float32"?new Float32Array(o.data.buffer,o.data.byteOffset,o.data.byteLength/4):new Qe(o.data.buffer,o.data.byteOffset,o.data.byteLength/2),r=e==="fp16"?new Qe(t):new Float32Array(t);return new Uint8Array(r.buffer,r.byteOffset,r.byteLength)}function xw(o){let e=Array.from({length:o},(r,i)=>`@group(0) @binding(${i}) var<storage, read> input${i}: array<vec4<f32>>;`).join(`
`),t=Array.from({length:o},(r,i)=>{let s=i*3;return`if (channel < ${s+3}u) {
      return input${i}[pixel][channel - ${s}u];
    }`}).join(`
  `);return`enable f16;
struct Params { width: u32, height: u32, channels: u32, padding: u32 }
${e}
@group(0) @binding(${o}) var<storage, read_write> outputData: array<f16>;
@group(0) @binding(${o+1}) var<uniform> params: Params;

fn readChannel(pixel: u32, channel: u32) -> f32 {
  ${t}
  return 0.0;
}

@compute @workgroup_size(${xr}, ${xr}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.width || gid.y >= params.height || gid.z >= params.channels) {
    return;
  }
  let pixel = gid.y * params.width + gid.x;
  let outputIndex = (gid.z * params.height + gid.y) * params.width + gid.x;
  outputData[outputIndex] = f16(readChannel(pixel, gid.z));
}
`}function yw(){return`enable f16;
struct Params { width: u32, height: u32, padding0: u32, padding1: u32 }
@group(0) @binding(0) var<storage, read> inputData: array<f16>;
@group(0) @binding(1) var<storage, read_write> outputData: array<vec4<f32>>;
@group(0) @binding(2) var<uniform> params: Params;

@compute @workgroup_size(${xr}, ${xr}, 1)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
  if (gid.x >= params.width || gid.y >= params.height) { return; }
  let pixel = gid.y * params.width + gid.x;
  let plane = params.width * params.height;
  outputData[pixel] = vec4<f32>(
    f32(inputData[pixel]),
    f32(inputData[plane + pixel]),
    f32(inputData[plane * 2u + pixel]),
    0.0
  );
}
`}function vw(o,e){if(e==="fp32")throw new Error("OIDN WebNN GPU interop currently requires FP16 exportable tensors");if(!o.features.has("shader-f16"))throw new Error("OIDN WebNN requires shader-f16 on the shared GPUDevice");return"fp16"}function bw(o,e,t){return t==="relu"?o.relu(e):e}var eo=class{_device;_model;precision;support;_context;_builderConstructor;_shapeCache=new Map;_shapePromises=new Map;_retiredExecutions=new Set;_pendingCreationCount=0;_shapeCacheSize;_clock=0;_inputPipeline;_outputPipeline;_resources=new Oi;_disposed=!1;constructor(e,t,r={}){this._device=e,this._model=t,this.precision=vw(e,r.precision??"auto"),this._shapeCacheSize=Math.max(1,r.shapeCacheSize??2),this.support={available:!1,fp16Conv:!1,gpuInterop:!1};let i=hw(e,t.inputChannels/3);this._inputPipeline=i.input,this._outputPipeline=i.output}async prepare(){if(this._disposed)throw new Error("OIDN WebNN executor is disposed");let e=globalThis.navigator?.ml,t=globalThis.MLGraphBuilder;if(!e?.createContext||typeof t!="function")throw this.support.reason="WebNN is not exposed by this browser",new Error(this.support.reason);this._builderConstructor=t;try{try{this._context=await e.createContext({deviceType:"gpu",powerPreference:"high-performance"})}catch{this._context=await e.createContext({deviceType:"gpu"})}if(this._resources.track("ml-context",this._context),this._disposed)throw new Error("OIDN WebNN executor is disposed");if(typeof this._context.createExportableTensor!="function"||typeof this._context.exportToGPU!="function")throw this.support.reason="WebNN WebGPU tensor interop is unavailable",new Error(this.support.reason);let r=this._context.opSupportLimits?.()??{};if(this.support.fp16Conv=vc(r,"conv2d","input","float16")&&vc(r,"conv2d","filter","float16")&&vc(r,"conv2d","output","float16"),!this.support.fp16Conv)throw this.support.reason="WebNN does not support FP16 conv2d",new Error(this.support.reason);let i,s;try{i=this._resources.track("ml-tensor",await this._context.createExportableTensor({dataType:"float16",shape:[4]},this._device)),s=this._resources.track("gpu-buffer",await this._context.exportToGPU(i)),this.support.gpuInterop=!0}catch(n){throw this.support.reason=`WebNN FP16 WebGPU interop failed: ${String(n)}`,new Error(this.support.reason)}finally{this._releaseBuffer(s),this._releaseTensor(i)}this.support.available=!0}catch(r){throw this._releaseContext(),r}}_constant(e,t){return e.constant({dataType:"float16",shape:[...t.desc.dims]},gw(t,this.precision))}async _createExecution(e,t){if(this._disposed)throw new Error("OIDN WebNN executor is disposed");let r=new this._builderConstructor(this._context),i=new Map,s=new Map;i.set(this._model.spec.input,r.input("input",{dataType:"float16",shape:[1,this._model.inputChannels,t,e]})),s.set(this._model.spec.input,[this._model.inputChannels,t,e]);for(let f of this._model.spec.nodes){let h,d;if(f.op==="conv2d"){let m=s.get(f.input),x=this._model.convTensors.get(f.id),v=r.conv2d(i.get(f.input),this._constant(r,x.weight),{bias:this._constant(r,x.bias),padding:[1,1,1,1],inputLayout:"nchw",filterLayout:"oihw"});h=bw(r,v,f.activation),d=[x.outputChannels,m[1],m[2]]}else if(f.op==="maxPool2d"){let m=s.get(f.input);h=r.maxPool2d(i.get(f.input),{windowDimensions:[2,2],strides:[2,2],padding:[0,m[1]%2,0,m[2]%2],layout:"nchw"}),d=[m[0],Math.ceil(m[1]/2),Math.ceil(m[2]/2)]}else if(f.op==="upsample2d"){let m=s.get(f.input);h=r.resample2d(i.get(f.input),{mode:"nearest-neighbor",axes:[2,3],scales:[2,2]}),d=[m[0],m[1]*2,m[2]*2]}else{let m=f.inputs.map(x=>s.get(x));if(m.some(x=>x[1]!==m[0][1]||x[2]!==m[0][2]))throw new Error(`WebNN concat ${f.id} has mismatched spatial shapes`);h=r.concat(f.inputs.map(x=>i.get(x)),1),d=[m.reduce((x,v)=>x+v[0],0),m[0][1],m[0][2]]}i.set(f.id,h),s.set(f.id,d)}let n,l,a,p,u,c;try{return n=this._resources.track("ml-graph",await r.build({output:i.get(this._model.spec.output)})),l=this._resources.track("ml-tensor",await this._context.createExportableTensor({dataType:"float16",shape:[1,this._model.inputChannels,t,e],writable:!0},this._device)),a=this._resources.track("ml-tensor",await this._context.createExportableTensor({dataType:"float16",shape:[1,this._model.outputChannels,t,e],readable:!0},this._device)),p=this._resources.track("gpu-buffer",this._device.createBuffer({label:`oidn/webnn/output/${e}x${t}`,size:e*t*4*4,usage:GPUBufferUsage.STORAGE|GPUBufferUsage.COPY_SRC|GPUBufferUsage.COPY_DST})),u=this._resources.track("gpu-buffer",tg(this._device,`oidn/webnn/input/${e}x${t}`,[e,t,this._model.inputChannels])),c=this._resources.track("gpu-buffer",tg(this._device,`oidn/webnn/output/${e}x${t}`,[e,t])),{graph:n,inputTensor:l,outputTensor:a,outputBuffer:p,inputUniform:u,outputUniform:c,width:e,height:t,lastUsed:++this._clock}}catch(f){throw this._releaseBuffer(c),this._releaseBuffer(u),this._releaseBuffer(p),this._releaseTensor(a),this._releaseTensor(l),this._releaseGraph(n),f}}async _execution(e,t){let r=`${e}x${t}`,i=this._shapeCache.get(r);if(!i){let s=this._shapePromises.get(r);s||(s=(async()=>{this._pendingCreationCount++;try{return await this._createExecution(e,t)}finally{this._pendingCreationCount--}})(),this._shapePromises.set(r,s));try{if(i=await s,this._disposed)throw this._destroyExecution(i),new Error("OIDN WebNN executor is disposed");this._shapeCache.set(r,i)}finally{this._shapePromises.get(r)===s&&this._shapePromises.delete(r)}if(this._shapeCache.size>this._shapeCacheSize){let n=[...this._shapeCache.entries()].filter(([l])=>l!==r).sort((l,a)=>l[1].lastUsed-a[1].lastUsed)[0];n&&(this._shapeCache.delete(n[0]),this._retireExecution(n[1]))}}return i.lastUsed=++this._clock,i}async prewarm(e){for(let t of e)await this._execution(t.width,t.height)}async execute(e,t,r){let i=this._model.inputChannels/3;if(e.length!==i)throw new Error(`OIDN WebNN expected ${i} input buffers, got ${e.length}`);let s=await this._execution(t,r),n=this._resources.track("gpu-buffer",await this._context.exportToGPU(s.inputTensor));try{let a=e.map((f,h)=>({binding:h,resource:{buffer:f}}));a.push({binding:i,resource:{buffer:n}}),a.push({binding:i+1,resource:{buffer:s.inputUniform}});let p=this._device.createBindGroup({label:"oidn/webnn/input-bindings",layout:this._inputPipeline.getBindGroupLayout(0),entries:a}),u=this._device.createCommandEncoder({label:"oidn/webnn/input-pack"}),c=u.beginComputePass();c.setPipeline(this._inputPipeline),c.setBindGroup(0,p),c.dispatchWorkgroups(Math.ceil(t/xr),Math.ceil(r/xr),this._model.inputChannels),c.end(),this._device.queue.submit([u.finish()])}finally{this._releaseBuffer(n)}this._context.dispatch(s.graph,{input:s.inputTensor},{output:s.outputTensor});let l=this._resources.track("gpu-buffer",await this._context.exportToGPU(s.outputTensor));try{let a=this._device.createBindGroup({label:"oidn/webnn/output-bindings",layout:this._outputPipeline.getBindGroupLayout(0),entries:[{binding:0,resource:{buffer:l}},{binding:1,resource:{buffer:s.outputBuffer}},{binding:2,resource:{buffer:s.outputUniform}}]}),p=this._device.createCommandEncoder({label:"oidn/webnn/output-unpack"}),u=p.beginComputePass();u.setPipeline(this._outputPipeline),u.setBindGroup(0,a),u.dispatchWorkgroups(Math.ceil(t/xr),Math.ceil(r/xr)),u.end(),this._device.queue.submit([p.finish()])}finally{this._releaseBuffer(l)}return s.outputBuffer}async executeCPU(e,t,r){let i=await this._execution(t,r),s=t*r,n=new Qe(s*this._model.inputChannels);for(let p=0;p<s;p++)for(let u=0;u<this._model.inputChannels;u++)n[u*s+p]=e[p*this._model.inputChannels+u];this._context.writeTensor(i.inputTensor,n),this._context.dispatch(i.graph,{input:i.inputTensor},{output:i.outputTensor});let l=new Qe(await this._context.readTensor(i.outputTensor)),a=new Float32Array(s*this._model.outputChannels);for(let p=0;p<s;p++)for(let u=0;u<this._model.outputChannels;u++)a[p*this._model.outputChannels+u]=l[u*s+p];return a}_destroyExecution(e){this._releaseGraph(e.graph),this._releaseTensor(e.inputTensor),this._releaseTensor(e.outputTensor),this._releaseBuffer(e.outputBuffer),this._releaseBuffer(e.inputUniform),this._releaseBuffer(e.outputUniform)}_retireExecution(e){this._retiredExecutions.add(e),this._device.queue.onSubmittedWorkDone().catch(()=>{}).then(()=>{this._retiredExecutions.delete(e),this._destroyExecution(e)})}_releaseBuffer(e){this._resources.release("gpu-buffer",e,()=>e.destroy())}_releaseTensor(e){this._resources.release("ml-tensor",e,()=>e.destroy())}_releaseGraph(e){this._resources.release("ml-graph",e,()=>e.destroy?.())}_releaseContext(){this._resources.release("ml-context",this._context,()=>this._context.destroy?.())}getResourceInfo(){return this._resources.snapshot(this._pendingCreationCount+this._retiredExecutions.size)}dispose(){if(!this._disposed){this._disposed=!0;for(let e of this._shapeCache.values())this._destroyExecution(e);this._shapeCache.clear();for(let e of this._retiredExecutions)this._destroyExecution(e);this._retiredExecutions.clear(),this._shapePromises.clear(),this._releaseContext()}}};var ww=100;function Gr(o,e){return Math.ceil(o/e)*e}function Xa(o){return o.data instanceof GPUBuffer||o.data instanceof GPUTexture}var bc=class{_device;_aux;_hdr;_hdrTransfer;_dataProcessGPU;_nativeExecutor;_webNNExecutor;_modelSpec;_inputChannels;_engine;_dynamicTileController;_lastExecution;_profileNextImage=!1;_lastImageProfile;_activeExecutionFailures=new Set;_deviceLostObserved=!1;_deviceLostSettled=!1;_deviceLostReason;constructor(e,t,r={}){this._aux=r.aux||!1,this._hdr=r.hdr||!1,this._hdrTransfer=r.hdrTransfer??"pu",this._engine=r.engine??"auto";let i=r.modelSpec??Sa(e),s=Ou(e,i);this._modelSpec=s.spec,this._inputChannels=s.inputChannels;let n=this._aux?9:3;if(s.inputChannels!==n)throw new Error(`OIDN model expects ${s.inputChannels} input channels, but aux=${this._aux} provides ${n}`);this._dynamicTileController=new _a(r.maxTileSize??512,r.dynamicTile),this._device=t,this._observeDeviceLoss(),this._engine==="webnn"?this._webNNExecutor=new eo(this._device,s,{precision:r.precision}):this._nativeExecutor=new Wn(this._device,s,{precision:r.precision,kernel:r.kernel,gemm:r.gemm,subgroupMatrix:r.subgroupMatrix})}getDevice(){return this._device}async prepare(){if(this._webNNExecutor){await this._webNNExecutor.prepare();let e=Gr(this._modelSpec.receptiveField/2,16),t=[this._dynamicTileController.tileSize,this._dynamicTileController.minTileSize];await this._webNNExecutor.prewarm([...new Set(t)].map(r=>({width:r+2*e,height:r+2*e})));return}await this._nativeExecutor.prepare()}async prepareForImage(e,t,r={}){let i=Gr(this._modelSpec.receptiveField/2,16),s=r.tileOverlap===void 0?i:Gr(Math.max(0,r.tileOverlap),16),n=Ta(e,t,r.wholeImage?Gr(Math.max(e,t),16):this._dynamicTileController.tileSize,s),l=[...new Map(n.tiles.map(({input:a})=>[`${a.width}x${a.height}`,{width:a.width,height:a.height}])).values()];await this._webNNExecutor?.prewarm(l),this._nativeExecutor?.prewarm(l)}getRuntimeInfo(){return{configuredEngine:this._engine,gpuEngine:this._webNNExecutor?"webnn":"wgsl",precision:(this._webNNExecutor??this._nativeExecutor).precision,kernel:this._nativeExecutor?{configured:this._nativeExecutor.kernelSetting,gemm:this._nativeExecutor.gemm,maxSpatialInputBlocks:this._nativeExecutor.maxSpatialInputBlocks,subgroupsAvailable:this._nativeExecutor.subgroupsAvailable,subgroupMatrix:this._nativeExecutor.subgroupMatrix}:void 0,webnn:this._webNNExecutor?.support,resources:(this._webNNExecutor??this._nativeExecutor).getResourceInfo(),model:this._modelSpec.id,modelFamily:this._modelSpec.family,inputChannels:this._inputChannels,hdrTransfer:this._hdrTransfer,dynamicTile:{enabled:this._dynamicTileController.enabled,currentTileSize:this._dynamicTileController.tileSize,minTileSize:this._dynamicTileController.minTileSize,maxTileSize:this._dynamicTileController.maxTileSize,targetTileTimeMs:this._dynamicTileController.targetTileTimeMs},lastExecution:this._lastExecution,activeExecutionCount:this._activeExecutionFailures.size}}_observeDeviceLoss(){if(this._activeExecutionFailures??=new Set,this._deviceLostObserved)return;this._deviceLostObserved=!0;let e=this._device.lost;if(!e)return;let t=r=>{if(this._deviceLostSettled)return;this._deviceLostSettled=!0,this._deviceLostReason=r;let i=[...this._activeExecutionFailures];this._activeExecutionFailures.clear();for(let s of i)s(r)};e.then(r=>t(new Error(`WebGPU device lost: ${r.message}`)),t)}_registerExecutionFailure(e){return this._observeDeviceLoss(),this._activeExecutionFailures.add(e),this._deviceLostSettled&&(this._activeExecutionFailures.delete(e),queueMicrotask(()=>e(this._deviceLostReason))),()=>this._activeExecutionFailures.delete(e)}profileNextExecution(){return!this._nativeExecutor||!this._device.features.has("timestamp-query")?!1:(this._profileNextImage=!0,!0)}getLastExecutionProfile(){return this._lastImageProfile?Promise.resolve(this._lastImageProfile):void 0}_processImageData(e,t,r,i){let s=e.data,n=s.length/4,l=this._aux?9:3,a=new Float32Array(n*l);if(t&&!r||r&&!t)throw new Error("Normal map and albedo map are both required");if(t&&r&&(t.width!==r.width||t.height!==r.height||e.width!==t.width||e.height!==t.height))throw new Error("Image size mismatch");let p=t?.data,u=r?.data,c=p instanceof Float32Array?1:255,f=u instanceof Float32Array?1:255;for(let h=0;h<s.length;h+=4){let d=h/4*l;for(let m=0;m<3;m++)i?a[d+m]=s[h+m]:a[d+m]=s[h+m]/255,p&&(a[d+m+3]=p[h+m]/c),u&&(a[d+m+6]=u[h+m]/f)}return a}_readTile(e,t,r,i){let s=new Float32Array(r.width*r.height*t),n=e.length/(i*t);for(let l=0;l<r.height;l++)for(let a=0;a<r.width;a++){let p=Math.min(i-1,a+r.x),c=(Math.min(n-1,l+r.y)*i+p)*t,f=(l*r.width+a)*t;for(let h=0;h<t;h++)s[f+h]=e[c+h]}return s}_writeTile(e,t,r,i,s,n){let{data:l,width:a}=e,p=r.x-t.x,u=r.y-t.y;for(let c=0;c<r.height;c++)for(let f=0;f<r.width;f++){let h=((c+u)*s+f+p)*3,d=((c+r.y)*a+(f+r.x))*4;for(let m=0;m<3;m++)n?l[d+m]=i[h+m]:l[d+m]=Math.min(Math.max(i[h+m]*255,0),255);e.data[d+3]=n?1:255}}async _executeTile(e,t,r,i,s,n,l,a,p){let u=this._aux?9:3,c=new Fi(i.input.x,i.input.y,i.input.width,i.input.height),f=new Fi(i.output.x,i.output.y,i.output.width,i.output.height),h=c.width,d=c.height,m,x,v=1,g=this._device,y=this._dataProcessGPU;if(e instanceof Float32Array){let b=this._readTile(e,u,c,n);a&&(v=Lu({data:b,channels:u}),b=Fu({data:b,channels:u,inputScale:v,transfer:this._hdrTransfer})),x=await(this._webNNExecutor??this._nativeExecutor).executeCPU(b,h,d)}else{y||(y=this._dataProcessGPU=new va(g,a,this._hdrTransfer)),y.setImageSize(n,l),y.setInputTile(c),s&&y.copyInputDataToOutput(e.color);let{color:b,albedo:T,normal:_}=y.forward(e.color,this._aux?e.albedo:void 0,this._aux?e.normal:void 0,p);m=await(this._webNNExecutor??this._nativeExecutor).execute(this._aux?[b,T,_]:[b],h,d)}let w;if(e instanceof Float32Array){a&&(x=ku({data:x,channels:3,inputScale:v,transfer:this._hdrTransfer})),this._writeTile(r,c,f,x,c.width,a);for(let b=0;b<f.height;b++)for(let T=0;T<f.width;T++){let _=(b*f.width+T)*4,S=((b+f.y)*n+(T+f.x))*4;for(let A=0;A<4;A++)t.data[_+A]=r.data[S+A]}}else y.setOutputTile(f,c),w=y.inverse(m,e.color);return w}tileExecute({color:e,albedo:t,normal:r,done:i,progress:s,denoiseAlpha:n,tileOverlap:l,wholeImage:a,scheduling:p="event-loop",error:u}){if(this._aux&&(!t||!r))throw new Error("Normal map and albedo map are both required");if(!this._aux&&(t||r))throw new Error("Normal map and albedo map are not required");let c=e.width,f=e.height,h=this._dynamicTileController.tileSize,d=a?Gr(Math.max(c,f),16):h,m=Gr(this._modelSpec.receptiveField/2,16),x=l===void 0?m:Gr(Math.max(0,l),16),v=Ta(c,f,d,x),g=v.tiles.length>1,y=this._hdr||!1,w;Xa(e)||(w=this._processImageData(e,t,r,y));function b(G,se){return y?{data:new Float32Array(G*se*4),width:G,height:se}:new ImageData(G,se)}let T=Xa(e)?void 0:b(c,f),_="active",S,A,I=()=>!1,P=()=>typeof performance>"u"?Date.now():performance.now(),C=P(),B=[],R=this._profileNextImage===!0;this._profileNextImage=!1;let E=new Map,N=()=>{S!==void 0&&(clearTimeout(S),S=void 0),A!==void 0&&typeof cancelAnimationFrame<"u"&&(cancelAnimationFrame(A),A=void 0)},M=G=>{console.error("OIDN error callback failed",G)},oe=G=>{if(_==="active")if(_="settled",N(),I(),u)try{Promise.resolve(u(G)).catch(M)}catch(se){M(se)}else console.error("OIDN execution failed",G)},we=G=>{if(p==="event-loop"||typeof requestAnimationFrame>"u")S=setTimeout(()=>{S=void 0,G()},0);else{let se=()=>{N(),G()};A=requestAnimationFrame(se),S=setTimeout(se,ww)}},ze=async G=>{if(_!=="active")return;let se=v.tiles[G],He=Xa(e)?void 0:b(se.output.width,se.output.height),We=P();R&&this._nativeExecutor.profileNextExecution();let he=await this._executeTile(Xa(e)?{color:e.data,albedo:t?.data,normal:r?.data}:w,He,T,se,G===0,c,f,y,n);if(R){let U=await this._nativeExecutor.getLastExecutionProfile();for(let j of U?.layers??[])E.set(j.id,(E.get(j.id)??0)+j.durationMs)}if(_!=="active")return;let ke=T||{data:he,width:c,height:f};if(s&&await s(ke,He,new Fi(se.output.x,se.output.y,se.output.width,se.output.height),G,v.tiles.length),_!=="active")return;let O=G+1<v.tiles.length;if(await this._device.queue.onSubmittedWorkDone(),_!=="active")return;await(async()=>{if(B.push(P()-We),_==="active")if(O)we(()=>{_==="active"&&ze(G+1).catch(oe)});else{let U=[...B].sort((Ze,yt)=>Ze-yt),j=Math.floor(U.length/2),Ft=U.length%2?U[j]:(U[j-1]+U[j])/2;if(this._lastExecution={width:c,height:f,tileCount:v.tiles.length,tileColumns:v.columns,tileRows:v.rows,tileOverlap:v.overlap,inputPixelCount:v.inputPixelCount,inputShapeCount:v.inputShapeCount,durationMs:P()-C,tileTimeMs:{min:U[0],median:Ft,mean:U.reduce((Ze,yt)=>Ze+yt,0)/U.length,max:U[U.length-1]}},g&&this._dynamicTileController.observe(B),I(),R){let Ze=[...E].map(([yt,tn])=>({id:yt,durationMs:tn}));this._lastImageProfile={totalMs:Ze.reduce((yt,tn)=>yt+tn.durationMs,0),layers:Ze}}await i(ke),_==="active"&&(_="settled")}})()};return I=this._registerExecutionFailure(oe),ze(0).catch(oe),()=>{_==="active"&&(_="aborted",N(),I())}}dispose(){this._dataProcessGPU?.dispose(),this._nativeExecutor?.dispose(),this._webNNExecutor?.dispose()}},ig=bc;async function ng(){if(!navigator.gpu)throw new Error("WebGPU is not available");let o={powerPreference:"high-performance"},e=await navigator.gpu.requestAdapter(o);if(!e)throw new Error("No WebGPU adapter is available");let t={},r=[];e.features.has("timestamp-query")&&r.push("timestamp-query"),e.features.has("bgra8unorm-storage")&&r.push("bgra8unorm-storage"),e.features.has("shader-f16")&&r.push("shader-f16");for(let s of["subgroups","chromium-experimental-subgroup-matrix"])e.features.has(s)&&r.push(s);t.requiredFeatures=r;let i=e.limits;return t.requiredLimits={maxComputeWorkgroupStorageSize:i.maxComputeWorkgroupStorageSize,maxComputeWorkgroupsPerDimension:i.maxComputeWorkgroupsPerDimension,maxStorageBufferBindingSize:i.maxStorageBufferBindingSize,maxBufferSize:i.maxBufferSize,maxComputeWorkgroupSizeX:i.maxComputeWorkgroupSizeX,maxComputeInvocationsPerWorkgroup:i.maxComputeInvocationsPerWorkgroup},e.requestDevice(t)}async function _w(o,e,t){let r=e?.device??await ng(),i=gh(o),s=new ig(i,r,t);return await s.prepare(),s}async function Tw(o,e,t){return fetch(o).then(r=>r.arrayBuffer()).then(r=>_w(r,e,t))}export{Su as OIDNDenoiser,_u as WebGPUPathTracer,Tw as initUNetFromURL};
