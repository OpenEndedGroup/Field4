// Field's isolated binary boundary for Loris (GPL-2.0-or-later).
#include "Analyzer.h"
#include "Synthesizer.h"
#include "Partial.h"
#include "Breakpoint.h"
#include "Channelizer.h"
#include "Dilator.h"
#include "Distiller.h"
#include "FrequencyReference.h"
#include "LinearEnvelope.h"
#include "Morpher.h"
#include "PartialUtils.h"
#include "Sieve.h"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

static uint32_t readInt(std::istream& in) {
    unsigned char b[4];
    if (!in.read(reinterpret_cast<char*>(b), 4)) throw std::runtime_error("truncated integer");
    return (uint32_t(b[0])<<24)|(uint32_t(b[1])<<16)|(uint32_t(b[2])<<8)|b[3];
}
static void writeInt(std::ostream& out, uint32_t n) {
    unsigned char b[4]={static_cast<unsigned char>(n>>24),static_cast<unsigned char>(n>>16),
                        static_cast<unsigned char>(n>>8),static_cast<unsigned char>(n)};
    out.write(reinterpret_cast<char*>(b),4);
}
static double readDouble(std::istream& in) {
    uint64_t bits=uint64_t(readInt(in))<<32; bits|=readInt(in);
    double d; std::memcpy(&d,&bits,8);
    if (!std::isfinite(d)) throw std::runtime_error("nonfinite input");
    return d;
}
static void writeDouble(std::ostream& out,double d) {
    if (!std::isfinite(d)) throw std::runtime_error("nonfinite output");
    uint64_t bits; std::memcpy(&bits,&d,8); writeInt(out,bits>>32);writeInt(out,bits);
}
static uint32_t bounded(std::istream& in,uint32_t limit) {
    auto n=readInt(in); if(n>limit)throw std::runtime_error("input limit exceeded");return n;
}
// One partial set in the mode-1 layout: count, then id/label/points per partial.
static void readPartials(std::istream& in,double rate,uint32_t frames,Loris::PartialList& partials,
                         std::vector<uint32_t>& identities,uint64_t& total) {
    auto count=bounded(in,100000);
    for(uint32_t i=0;i<count;++i) {
        auto id=readInt(in);
        Loris::Partial p; p.setLabel(readInt(in)); auto points=bounded(in,10000000);
        if(!points)throw std::runtime_error("empty partial");
        total+=points;if(total>10000000)throw std::runtime_error("too many breakpoints");
        double previous=-1;
        for(uint32_t j=0;j<points;++j) {
            double t=readDouble(in),f=readDouble(in),a=readDouble(in);
            double phase=readDouble(in),bw=readDouble(in);
            if(t<0||t<=previous||t>double(frames)/rate||f<=0||f>=rate/2||a<0||bw<0||bw>1)
                throw std::runtime_error("invalid breakpoint");
            p.insert(t,Loris::Breakpoint(f,a,bw,phase));previous=t;
        }
        partials.push_back(p);identities.push_back(id);
    }
}
// Envelope as count followed by (time,value) pairs.
static Loris::LinearEnvelope readEnvelope(std::istream& in) {
    auto n=bounded(in,100000); if(!n)throw std::runtime_error("empty envelope");
    Loris::LinearEnvelope e;
    for(uint32_t i=0;i<n;++i){double t=readDouble(in),v=readDouble(in);e.insert(t,v);}
    return e;
}
// Transforms can extend or move partials: keep the declared timeline and Field's breakpoint invariants.
static void conform(Loris::PartialList& partials,double rate,uint32_t frames) {
    double end=double(frames)/rate;
    for(auto& p:partials) {
        Loris::PartialUtils::crop(p,0.0,end);
        for(auto it=p.begin();it!=p.end();) {
            auto& b=it.breakpoint();
            if(!(b.frequency()>0&&b.frequency()<rate/2)||!(b.amplitude()>=0)) { it=p.erase(it); continue; }
            b.setBandwidth(std::min(1.0,std::max(0.0,b.bandwidth())));
            ++it;
        }
    }
    for(auto it=partials.begin();it!=partials.end();) it=it->size()==0?partials.erase(it):std::next(it);
}
int main(int argc,char** argv) {
    try {
        if(argc!=3)throw std::runtime_error("usage: field_loris input.bin output.bin");
        std::ifstream in(argv[1],std::ios::binary);
        char magic[8];in.read(magic,8);
        if(!in || std::memcmp(magic,"FLORIS01",8))throw std::runtime_error("protocol mismatch");
        auto mode=readInt(in);double rate=readDouble(in);
        if(rate<8000 || rate>192000)throw std::runtime_error("sample rate outside 8000..192000");
        auto frames=bounded(in,30000000);
        if(!frames)throw std::runtime_error("empty audio");
        Loris::PartialList partials;
        std::vector<uint32_t> identities;
        if(mode==0) {
            double resolution=readDouble(in),width=readDouble(in),hop=readDouble(in);
            double drift=readDouble(in),floor=readDouble(in),minimum=readDouble(in);
            if(resolution<=0||width<=0||hop<=0||drift<=0||floor>=0||minimum<=0)
                throw std::runtime_error("invalid analyzer parameters");
            std::vector<double> audio(frames);
            for(auto& s:audio)s=readDouble(in);
            Loris::Analyzer analyzer(resolution,width);
            analyzer.setHopTime(hop);analyzer.setFreqDrift(drift);
            analyzer.setAmpFloor(floor);analyzer.setFreqFloor(minimum);
            partials=analyzer.analyze(audio,rate);
        } else if(mode==1) {
            uint64_t total=0; readPartials(in,rate,frames,partials,identities,total);
        } else if(mode==2) {
            // channelize against a reference, then optionally distill (fade, gap seconds).
            uint64_t total=0; readPartials(in,rate,frames,partials,identities,total);
            auto kind=readInt(in);
            std::unique_ptr<Loris::Envelope> reference; int label=1;
            if(kind==0){ auto e=readEnvelope(in); label=int(bounded(in,1000)); reference.reset(e.clone()); }
            else if(kind==1){ double lo=readDouble(in),hi=readDouble(in);
                if(lo<=0||hi<=lo)throw std::runtime_error("invalid fundamental range");
                reference.reset(new Loris::FrequencyReference(partials.begin(),partials.end(),lo,hi)); }
            else throw std::runtime_error("unknown reference kind");
            if(label<1)throw std::runtime_error("reference label must be positive");
            double stretch=readDouble(in); if(stretch<0)throw std::runtime_error("negative stretch");
            Loris::Channelizer ch(*reference,label,stretch); ch.channelize(partials.begin(),partials.end());
            auto distill=readInt(in); double fade=readDouble(in),gap=readDouble(in);
            if(fade<0||gap<0)throw std::runtime_error("negative distill time");
            if(distill){ Loris::Distiller d(fade,gap); d.distill(partials); }
            identities.clear(); conform(partials,rate,frames);
        } else if(mode==3) {
            uint64_t total=0; readPartials(in,rate,frames,partials,identities,total);
            double fade=readDouble(in); if(fade<0)throw std::runtime_error("negative sieve fade");
            Loris::Sieve sieve(fade); sieve.sift(partials.begin(),partials.end());
            if(readInt(in)) for(auto it=partials.begin();it!=partials.end();) it=it->label()==0?partials.erase(it):std::next(it);
            identities.clear(); conform(partials,rate,frames);
        } else if(mode==4) {
            uint64_t total=0; auto outFrames=bounded(in,30000000); if(!outFrames)throw std::runtime_error("empty output");
            readPartials(in,rate,frames,partials,identities,total);
            auto n=bounded(in,100000); if(!n)throw std::runtime_error("no markers");
            std::vector<double> from(n),to(n);
            for(auto& v:from)v=readDouble(in); for(auto& v:to)v=readDouble(in);
            Loris::Dilator dil(from.begin(),from.end(),to.begin());
            dil.dilate(partials.begin(),partials.end());
            frames=outFrames; conform(partials,rate,frames);
            identities.clear();
        } else if(mode==5) {
            uint64_t total=0; auto outFrames=bounded(in,30000000); if(!outFrames)throw std::runtime_error("empty output");
            Loris::PartialList src,tgt; std::vector<uint32_t> ignore;
            readPartials(in,rate,frames,src,ignore,total);
            auto tgtFrames=bounded(in,30000000); readPartials(in,rate,tgtFrames,tgt,ignore,total);
            auto ff=readEnvelope(in),af=readEnvelope(in),bf=readEnvelope(in);
            auto refLabel=readInt(in); double shape=readDouble(in);
            Loris::Morpher m(ff,af,bf);
            if(shape>0)m.setAmplitudeShape(shape);
            if(refLabel){ m.setSourceReferencePartial(src,refLabel); m.setTargetReferencePartial(tgt,refLabel); }
            m.morph(src.begin(),src.end(),tgt.begin(),tgt.end());
            partials=std::move(m.partials());
            frames=outFrames; conform(partials,rate,frames);
                } else throw std::runtime_error("unknown operation");
        if(in.peek()!=std::char_traits<char>::eof())throw std::runtime_error("trailing input");
        std::vector<double> output(frames,0.0);
        Loris::Synthesizer synth(rate,output);
        synth.synthesize(partials.begin(),partials.end());
        // Loris may add a fade beyond the requested timeline; keep the explicit frame extent.
        output.resize(frames);
        std::ofstream out(argv[2],std::ios::binary|std::ios::trunc);
        out.write("FLORIS02",8);writeDouble(out,rate);writeInt(out,partials.size());
        uint32_t id=0;
        for(const auto& p:partials) {
            writeInt(out,mode==1?identities.at(id):id);++id;writeInt(out,p.label());writeInt(out,p.size());
            for(auto it=p.begin();it!=p.end();++it) {
                auto& b=it.breakpoint();writeDouble(out,it.time());writeDouble(out,b.frequency());
                writeDouble(out,b.amplitude());writeDouble(out,b.phase());writeDouble(out,b.bandwidth());
            }
        }
        writeInt(out,frames);for(double s:output)writeDouble(out,s);
        if(!out)throw std::runtime_error("unable to write result");
        return 0;
    } catch(const std::exception& e) { std::cerr<<"field-loris: "<<e.what()<<'\n';return 1; }
}
