#pragma once
#include "flow.hpp"
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
namespace pv {
// Streaming dense flow. Model inference stays outside the particle loop.
struct DenseStream {
    std::ifstream file; std::istream* in; int w,h;
    std::vector<float> forward,backward;
    template<class T> T readScalar() {
        unsigned char b[sizeof(T)];in->read(reinterpret_cast<char*>(b),sizeof(T));
        if(!*in)throw std::runtime_error("Truncated dense-flow stream");
        uint16_t n=1;if(*reinterpret_cast<unsigned char*>(&n)!=1)std::reverse(b,b+sizeof(T));
        T value;std::memcpy(&value,b,sizeof(T));return value;
    }
    DenseStream(const std::string& path,int W,int H,double fps):in(&std::cin),w(W),h(H) {
        if(path!="-"){file.open(path,std::ios::binary);if(!file)throw std::runtime_error("Cannot open dense flow");in=&file;}
        char magic[8];in->read(magic,8);
        if(!*in||std::memcmp(magic,"PVFLOW1\0",8))throw std::runtime_error("Invalid dense-flow header");
        auto fw=readScalar<uint32_t>(),fh=readScalar<uint32_t>();double ffps=readScalar<double>();auto reserved=readScalar<uint64_t>();
        if(fw!=uint32_t(w)||fh!=uint32_t(h)||!std::isfinite(ffps)||std::abs(ffps-fps)>1e-6||reserved)
            throw std::runtime_error("Flow/video dimensions or FPS mismatch");
        forward.resize(size_t(w)*h*2);backward.resize(forward.size());
    }
    void next(uint64_t index) {
        if(readScalar<uint64_t>()!=index)throw std::runtime_error("Flow/video pair index mismatch");
        for(auto* a:{&forward,&backward}) {
            in->read(reinterpret_cast<char*>(a->data()),std::streamsize(a->size()*sizeof(float)));
            if(!*in)throw std::runtime_error("Truncated dense-flow field");
            uint16_t n=1;if(*reinterpret_cast<unsigned char*>(&n)!=1)for(float& f:*a){auto b=reinterpret_cast<unsigned char*>(&f);std::reverse(b,b+4);}
            for(float f:*a)if(!std::isfinite(f)||std::abs(f)>1e7f)throw std::runtime_error("Invalid dense-flow value");
        }
    }
    void finish() {
        if(readScalar<uint64_t>()!=UINT64_MAX)throw std::runtime_error("Dense flow did not finish with video");
        if(in->peek()!=std::char_traits<char>::eof())throw std::runtime_error("Trailing dense-flow data");
    }
    V sample(const std::vector<float>& a,V p) const {
        p.x=std::clamp(p.x,0.f,float(w-1));p.y=std::clamp(p.y,0.f,float(h-1));
        int x=int(p.x),y=int(p.y);float u=p.x-x,v=p.y-y;V q;
        for(int j=0;j<2;++j)for(int i=0;i<2;++i){size_t k=(size_t(std::min(y+j,h-1))*w+std::min(x+i,w-1))*2;
            float z=(i?u:1-u)*(j?v:1-v);q.x+=a[k]*z;q.y+=a[k+1]*z;}
        return q;
    }
    Flow query(bool ahead,const Frame& src,const Frame& dst,V p) const {
        Flow r;r.q=p;r.texture=-1;const auto& f=ahead?forward:backward;const auto& b=ahead?backward:forward;
        if(!src.pyr[0].inside(p,2))return r;
        V d=sample(f,p),q=p+d;if(!dst.pyr[0].inside(q,2))return r;
        r.q=q;r.fb=norm(d+sample(b,q));
        // Per-pixel warping (rather than one rigid patch translation) retains
        // deformation within the photometric consistency check.
        float error=0;int count=0;
        for(int y=-1;y<=1;++y)for(int x=-1;x<=1;++x){V z=p+V{float(x),float(y)},target=z+sample(f,z);
            if(!dst.pyr[0].inside(target,0))continue;
            float e=src.pyr[0].sample(z)-dst.pyr[0].sample(target);error+=e*e;++count;}
        if(!count)return r;
        r.residual=std::sqrt(error/count);
        float scale=1.f+.05f*norm(d);
        r.confidence=std::exp(-.5f*std::pow(r.fb/scale,2)-.5f*std::pow(r.residual/.10f,2));
        r.valid=std::isfinite(r.confidence);return r;
    }
};
}
