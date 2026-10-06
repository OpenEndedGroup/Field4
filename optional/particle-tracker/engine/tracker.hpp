#pragma once
#include "flow.hpp"
#include <fstream>
#include <functional>
#include <random>
#include <stdexcept>
#include <cstring>
#include <numeric>
namespace pv {
enum Flags : uint32_t {BORN=1,UNCERTAIN=2,CROWDED=4,COLOR=8,STILL=16,LOST=32,MERGED=64,EOF_END=128};
struct Config {
    int particles=2000,grace=4,minTrack=8,mergeAge=30,seedBudget=16;
    float spacing=0,confidence=.25f,seedConfidence=.45f,colorLimit=.18f,stillSpeed=0;
    float mergeDistance=0,mergeVelocity=1.5f; uint32_t seed=1;
};
struct Record {
    uint32_t type=1,flags=0; uint64_t id=0,frame=0,related=0;
    float x=0,y=0,vx=0,vy=0,r=0,g=0,b=0,confidence=0,fb=0,residual=0,texture=0,colorDelta=0;
}; // Serialized manually: 80 bytes, no ABI padding.
struct Writer {
    std::ofstream out; uint64_t records=0;
    template<class T> void scalar(T x) {
        static_assert(sizeof(T)==4||sizeof(T)==8); unsigned char b[sizeof(T)]; std::memcpy(b,&x,sizeof(T));
        uint16_t n=1; if(*reinterpret_cast<unsigned char*>(&n)!=1) std::reverse(b,b+sizeof(T));
        out.write(reinterpret_cast<char*>(b),sizeof(T));
    }
    Writer(const std::string& path,uint32_t w,uint32_t h,double fps,uint32_t seed):out(path,std::ios::binary) {
        if(!out) throw std::runtime_error("Cannot open output: "+path);
        out.exceptions(std::ios::badbit|std::ios::failbit);
        out.write("PVTRACK1",8); scalar(uint32_t(1));scalar(uint32_t(64));scalar(uint32_t(80));scalar(w);scalar(h);scalar(seed);scalar(fps);
        scalar(uint64_t(0));scalar(uint64_t(0));scalar(uint64_t(0));
    }
    void write(const Record& r) {
        scalar(r.type);scalar(r.flags);scalar(r.id);scalar(r.frame);scalar(r.related);
        for(float x:{r.x,r.y,r.vx,r.vy,r.r,r.g,r.b,r.confidence,r.fb,r.residual,r.texture,r.colorDelta}) scalar(x);
        ++records;
    }
    void finish(uint64_t frames) {
        out.seekp(40);scalar(frames);scalar(records);scalar(uint64_t(1));out.flush();out.close();
    }
};
struct Particle {
    uint64_t id=0; V p,velocity; std::array<float,3> anchor{};
    uint32_t age=0; std::array<int,4> bad{}; bool committed=false,dead=false;
    uint64_t mergePeer=0; int mergeRun=0;
    std::vector<Record> pending; Record last;
};
struct Grid {
    int nx,ny; float cell; std::vector<std::vector<size_t>> bins;
    Grid(int w,int h,float c):nx(int(std::ceil(w/c))),ny(int(std::ceil(h/c))),cell(c),bins(size_t(nx)*ny){}
    int index(V p) const {return std::clamp(int(p.y/cell),0,ny-1)*nx+std::clamp(int(p.x/cell),0,nx-1);}
    void add(V p,size_t i) {bins[index(p)].push_back(i);}
    template<class F> void near(V p,float radius,F f) const {
        int x0=std::max(0,int(std::floor((p.x-radius)/cell))),x1=std::min(nx-1,int((p.x+radius)/cell));
        int y0=std::max(0,int(std::floor((p.y-radius)/cell))),y1=std::min(ny-1,int((p.y+radius)/cell));
        for(int y=y0;y<=y1;++y) for(int x=x0;x<=x1;++x) for(size_t i:bins[size_t(y)*nx+x]) f(i);
    }
};
struct Tracker {
    Config c; int w,h; uint64_t nextID=1,kept=0,dropped=0,ended=0,merged=0;
    using Estimator=std::function<Flow(const Frame&,const Frame&,V,V)>;
    Estimator estimate=flow;
    std::vector<Particle> ps; std::mt19937 rng; std::function<void(const Record&)> sink;
    Tracker(Config cfg,int W,int H,std::function<void(const Record&)> out):c(cfg),w(W),h(H),rng(c.seed),sink(std::move(out)) {
        if(c.spacing==0) c.spacing=.75f*std::sqrt(float(w)*h/c.particles);
    }
    Record sample(const Particle& p,uint64_t frame,const Frame& image,const Flow& f,uint32_t flags,float cd=0) const {
        auto rgb=image.color(p.p); Record r; r.flags=flags;r.id=p.id;r.frame=frame;r.x=p.p.x;r.y=p.p.y;
        r.vx=p.velocity.x;r.vy=p.velocity.y;r.r=rgb[0];r.g=rgb[1];r.b=rgb[2];r.confidence=f.confidence;
        r.fb=f.fb;r.residual=f.residual;r.texture=f.texture;r.colorDelta=cd; return r;
    }
    void append(Particle& p,Record r) {
        p.last=r; ++p.age;
        if(p.committed) sink(r);
        else {
            p.pending.push_back(r);
            if(p.age>=uint32_t(c.minTrack)) {for(auto& q:p.pending)sink(q);p.pending.clear();p.committed=true;++kept;}
        }
    }
    void end(Particle& p,uint64_t frame,uint32_t flags,uint64_t related=0) {
        p.dead=true;++ended;
        if(!p.committed) {++dropped;return;}
        auto r=p.last;r.type=2;r.frame=frame;r.flags=flags;r.related=related;sink(r);
        if(flags&MERGED)++merged;
    }
    // A birth uses flow from this image to the adjacent image. At t=0 this
    // looks forward; subsequently it looks backward, so no future data needed.
    void seed(const Frame& image,const Frame& adjacent,uint64_t frame) {
        Grid grid(w,h,c.spacing); for(size_t i=0;i<ps.size();++i)grid.add(ps[i].p,i);
        std::uniform_real_distribution<float> X(8,float(w-9)),Y(8,float(h-9));
        int missing=c.particles-int(ps.size()),budget=missing*c.seedBudget;
        for(int attempt=0;attempt<budget && int(ps.size())<c.particles;++attempt) {
            V best;float score=-1;
            // Best candidate approximate Poisson disk: preserve hard spacing,
            // favor holes. Capped local search prevents quadratic N scaling.
            for(int trial=0;trial<8;++trial) {
                V q{X(rng),Y(rng)};float d=3*c.spacing;
                grid.near(q,d,[&](size_t i){d=std::min(d,norm(q-ps[i].p));});
                if(d>=c.spacing && d>score){score=d;best=q;}
            }
            if(score<0)continue;
            Flow f=estimate(image,adjacent,best,{});
            if(!f.valid||f.confidence<c.seedConfidence)continue;
            Particle p;p.id=nextID++;p.p=best;p.anchor=image.color(best);
            // Birth velocity is unknown, regardless of which adjacent frame
            // was used to establish confidence.
            append(p,sample(p,frame,image,f,BORN));
            ps.push_back(std::move(p));grid.add(best,ps.size()-1);
        }
    }
    void step(const Frame& prev,const Frame& curr,uint64_t frame) {
        std::vector<Flow> flows(ps.size());
        #ifdef _OPENMP
        #pragma omp parallel for schedule(static)
        #endif
        for(int i=0;i<int(ps.size());++i) flows[i]=estimate(prev,curr,ps[i].p,ps[i].velocity);
        std::vector<uint32_t> flags(ps.size(),0);std::vector<float> cds(ps.size(),0);
        for(size_t i=0;i<ps.size();++i) {
            auto& p=ps[i];auto& f=flows[i];
            if(!f.valid||f.confidence<c.confidence)flags[i]|=UNCERTAIN;
            // Never jump to a failed flow estimate: hold position during grace.
            if(!(flags[i]&UNCERTAIN)) {p.velocity=f.q-p.p;p.p=f.q;} else p.velocity={};
            if(!curr.pyr[0].inside(p.p,7)) {end(p,frame,LOST);continue;}
            cds[i]=colorDistance(curr.color(p.p),p.anchor);
            if(cds[i]>c.colorLimit)flags[i]|=COLOR;
            if(c.stillSpeed>0 && norm(p.velocity)<c.stillSpeed)flags[i]|=STILL;
        }
        Grid grid(w,h,c.spacing);for(size_t i=0;i<ps.size();++i)if(!ps[i].dead)grid.add(ps[i].p,i);
        for(size_t i=0;i<ps.size();++i) {
            auto& p=ps[i];if(p.dead)continue;
            size_t peer=ps.size();float nearest=c.spacing;
            grid.near(p.p,std::max(c.spacing,c.mergeDistance),[&](size_t j){
                const auto& q=ps[j];if(j==i||q.dead)return;
                // Only the younger (or larger-ID tie) yields.
                bool wins=q.age>p.age||(q.age==p.age&&q.id<p.id);
                if(!wins)return;
                float d=norm(q.p-p.p);
                if(d<c.spacing)flags[i]|=CROWDED;
                if(c.mergeDistance>0 && p.age>=uint32_t(c.mergeAge)&&q.age>=uint32_t(c.mergeAge)
                    && p.committed&&q.committed && !(flags[i]&UNCERTAIN)&&!(flags[j]&UNCERTAIN)
                    && d<c.mergeDistance && norm(p.velocity-q.velocity)<c.mergeVelocity
                    && colorDistance(curr.color(p.p),curr.color(q.p))<c.colorLimit
                    && (peer==ps.size()||d<nearest)) {nearest=d;peer=j;}
            });
            if(peer!=ps.size()) {
                uint64_t id=ps[peer].id;p.mergeRun=(p.mergePeer==id?p.mergeRun+1:1);p.mergePeer=id;
            } else {p.mergeRun=0;p.mergePeer=0;}
            uint32_t reason=0;const uint32_t bits[4]={UNCERTAIN,CROWDED,COLOR,STILL};
            for(int k=0;k<4;++k){p.bad[k]=(flags[i]&bits[k])?p.bad[k]+1:0;if(p.bad[k]>=c.grace)reason|=bits[k];}
            append(p,sample(p,frame,curr,flows[i],flags[i],cds[i]));
            if(p.mergeRun>=c.grace)end(p,frame,MERGED,p.mergePeer);
            else if(reason)end(p,frame,reason);
        }
        ps.erase(std::remove_if(ps.begin(),ps.end(),[](const Particle& p){return p.dead;}),ps.end());
        seed(curr,prev,frame);
    }
    void finish(uint64_t lastFrame) {for(auto& p:ps)end(p,lastFrame,EOF_END);ps.clear();}
};
} // namespace pv
