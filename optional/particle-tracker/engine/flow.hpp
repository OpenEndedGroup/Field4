#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>
namespace pv {
struct V {
    float x=0,y=0;
    V operator+(V b) const {return {x+b.x,y+b.y};}
    V operator-(V b) const {return {x-b.x,y-b.y};}
    V operator*(float s) const {return {x*s,y*s};}
};
inline float norm(V p) {return std::hypot(p.x,p.y);}
struct Image {
    int w=0,h=0; std::vector<float> a;
    Image()=default;
    Image(int W,int H):w(W),h(H),a(size_t(W)*H){}
    float at(int x,int y) const {return a[size_t(std::clamp(y,0,h-1))*w+std::clamp(x,0,w-1)];}
    float sample(V p) const {
        int x=int(std::floor(p.x)),y=int(std::floor(p.y));
        float u=p.x-x,v=p.y-y;
        return (at(x,y)*(1-u)+at(x+1,y)*u)*(1-v)+(at(x,y+1)*(1-u)+at(x+1,y+1)*u)*v;
    }
    bool inside(V p,int margin=7) const {
        return std::isfinite(p.x)&&std::isfinite(p.y)&&p.x>=margin&&p.y>=margin&&p.x<w-1-margin&&p.y<h-1-margin;
    }
};
struct Frame {
    int w,h; std::vector<uint8_t> rgb; std::vector<Image> pyr;
    Frame(int W,int H,std::vector<uint8_t> bytes):w(W),h(H),rgb(std::move(bytes)) {
        Image gray(w,h);
        for(size_t i=0;i<gray.a.size();++i) gray.a[i]=(.2126f*rgb[3*i]+.7152f*rgb[3*i+1]+.0722f*rgb[3*i+2])/255.f;
        pyr.push_back(std::move(gray));
        const int k[5]={1,4,6,4,1};
        while(pyr.size()<5 && pyr.back().w>=64 && pyr.back().h>=64) {
            const Image& src=pyr.back(); Image dst((src.w+1)/2,(src.h+1)/2);
            for(int y=0;y<dst.h;++y) for(int x=0;x<dst.w;++x) {
                float z=0; for(int j=-2;j<=2;++j) for(int i=-2;i<=2;++i) z+=k[i+2]*k[j+2]*src.at(x*2+i,y*2+j);
                dst.a[size_t(y)*dst.w+x]=z/256;
            }
            pyr.push_back(std::move(dst));
        }
    }
    std::array<float,3> color(V p) const {
        // Bilinear decoded RGB, stored in the video's transfer-encoded space.
        p.x=std::clamp(p.x,0.f,float(w-1)); p.y=std::clamp(p.y,0.f,float(h-1));
        int x=int(p.x),y=int(p.y); float u=p.x-x,v=p.y-y;
        std::array<float,3> c{};
        for(int j=0;j<2;++j) for(int i=0;i<2;++i) for(int k=0;k<3;++k)
            c[k]+=rgb[(size_t(std::min(y+j,h-1))*w+std::min(x+i,w-1))*3+k]/255.f*(i?u:1-u)*(j?v:1-v);
        return c;
    }
};
inline float colorDistance(const std::array<float,3>& a,const std::array<float,3>& b) {
    float d=0; for(int i=0;i<3;++i) d+=(a[i]-b[i])*(a[i]-b[i]); return std::sqrt(d/3);
}
struct Flow {V q; float confidence=0,fb=1e6f,residual=1,texture=0; bool valid=false;};
struct LK {V q; float residual=1,eig=0; bool valid=false;};
inline LK oneWay(const Frame& a,const Frame& b,V p,V initial={}) {
    constexpr int R=5,N=(2*R+1)*(2*R+1);
    int top=int(a.pyr.size())-1;
    while(top>0 && !a.pyr[top].inside(p*(1.f/float(1<<top)),R+2)) --top;
    V d=initial*(1.f/float(1<<top)); LK out;
    for(int level=top;level>=0;--level) {
        if(level<top) d=d*2;
        const auto& A=a.pyr[level]; const auto& B=b.pyr[level];
        V c=p*(1.f/float(1<<level));
        if(!A.inside(c,R+2)) return out;
        float ref[N],gx[N],gy[N],xx=0,xy=0,yy=0; int k=0;
        for(int y=-R;y<=R;++y) for(int x=-R;x<=R;++x,++k) {
            V z=c+V{float(x),float(y)};
            ref[k]=A.sample(z); gx[k]=(A.sample(z+V{1,0})-A.sample(z-V{1,0}))*.5f;
            gy[k]=(A.sample(z+V{0,1})-A.sample(z-V{0,1}))*.5f;
            xx+=gx[k]*gx[k]; xy+=gx[k]*gy[k]; yy+=gy[k]*gy[k];
        }
        float det=xx*yy-xy*xy;
        if(det<1e-12f) {if(level) continue; return out;}
        bool failed=false;
        for(int it=0;it<18;++it) {
            if(!B.inside(c+d,R+1)) {failed=true;break;}
            float bx=0,by=0; k=0;
            for(int y=-R;y<=R;++y) for(int x=-R;x<=R;++x,++k) {
                float e=ref[k]-B.sample(c+d+V{float(x),float(y)}); bx+=gx[k]*e; by+=gy[k]*e;
            }
            V step{(yy*bx-xy*by)/det,(xx*by-xy*bx)/det};
            float len=norm(step); if(!std::isfinite(len)) return out;
            if(len>2) step=step*(2/len);
            d=d+step; if(len<.015f) break;
        }
        if(failed) return out;
        if(level==0) {
            if(!B.inside(c+d,R+1)) return out;
            float err=0; k=0;
            for(int y=-R;y<=R;++y) for(int x=-R;x<=R;++x,++k) {float e=ref[k]-B.sample(c+d+V{float(x),float(y)});err+=e*e;}
            out={p+d,std::sqrt(err/N),(xx+yy-std::sqrt((xx-yy)*(xx-yy)+4*xy*xy))/(2*N),true};
        }
    }
    return out;
}
inline Flow flow(const Frame& a,const Frame& b,V p,V initial={}) {
    Flow r; r.q=p; auto f=oneWay(a,b,p,initial); if(!f.valid) return r;
    r.q=f.q; auto back=oneWay(b,a,f.q,p-f.q); if(!back.valid) return r;
    r.fb=norm(back.q-p); r.residual=std::max(f.residual,back.residual); r.texture=std::max(0.f,std::min(f.eig,back.eig));
    // Conservative heuristic score, not a calibrated probability.
    r.confidence=std::exp(-.5f*std::pow(r.fb/1.0f,2)-.5f*std::pow(r.residual/.07f,2))*std::min(1.f,r.texture/.0003f);
    r.valid=std::isfinite(r.confidence); return r;
}
} // namespace pv
