#include "tracker.hpp"
#include <iostream>
#include <map>
#include <stdexcept>
using namespace pv;
void check(bool b,const char* message){if(!b)throw std::runtime_error(message);}
Frame texture(int dx=0,int dy=0,bool blank=false) {
    int w=160,h=128;std::vector<uint8_t> a(size_t(w)*h*3);
    for(int y=0;y<h;++y)for(int x=0;x<w;++x){float X=float(x-dx),Y=float(y-dy);
        float v=blank?.5f:.5f+.14f*std::sin(X*.31f+Y*.17f)+.13f*std::cos(Y*.37f-X*.11f)+.12f*std::sin(X*.71f)*std::cos(Y*.63f);
        for(int k=0;k<3;++k)a[(size_t(y)*w+x)*3+k]=uint8_t(std::clamp(v*255,0.f,255.f));}
    return Frame(w,h,std::move(a));
}
int main(){try{
    auto a=texture(),b=texture(2,-1),blank=texture(0,0,true);
    int good=0;float error=0;
    for(int y=24;y<104;y+=12)for(int x=24;x<136;x+=12){auto f=flow(a,b,{float(x),float(y)});if(f.confidence>.25f){++good;error+=norm(f.q-V{float(x+2),float(y-1)});}}
    check(good>40,"translation: too few confident points");check(error/good<.1f,"translation: inaccurate flow");
    check(flow(blank,blank,{60,60}).confidence==0,"flat regions must be uncertain");
    std::cout<<"translation: "<<good<<" points, mean endpoint error="<<error/good<<" px\n";
    Config c;c.particles=35;c.spacing=14;c.minTrack=3;c.seedBudget=4;c.grace=2;
    std::vector<Record> out;Tracker t(c,160,128,[&](auto r){out.push_back(r);});t.seed(a,a,0);
    check(t.ps.size()>15,"too few seeds");for(size_t i=0;i<t.ps.size();++i)for(size_t j=0;j<i;++j)check(norm(t.ps[i].p-t.ps[j].p)>=c.spacing,"spacing violation");
    check(out.empty(),"probation samples escaped");t.step(a,a,1);check(out.empty(),"short tracks escaped");t.step(a,a,2);
    check(!out.empty(),"qualified tracks not flushed");t.finish(2);
    std::map<uint64_t,int> counts;
    for(auto r:out)if(r.type==1)++counts[r.id];
    for(auto kv:counts)check(kv.second>=3,"short track retained");
    out.clear();Tracker shorty(c,160,128,[&](auto r){out.push_back(r);});shorty.seed(a,a,0);shorty.finish(0);check(out.empty()&&shorty.dropped>0,"EOF short-track drop failed");
    c.minTrack=1;c.stillSpeed=.1f;Tracker still(c,160,128,[&](auto r){out.push_back(r);});still.seed(a,a,0);still.step(a,a,1);still.step(a,a,2);
    bool stopped=false;for(auto r:out)if(r.type==2&&(r.flags&STILL))stopped=true;check(stopped,"stationary recycling failed");
    out.clear();c.stillSpeed=0;Tracker uncertain(c,160,128,[&](auto r){out.push_back(r);});uncertain.seed(a,a,0);uncertain.step(a,blank,1);
    check(uncertain.ended==0,"uncertainty grace not honored");uncertain.step(blank,blank,2);check(uncertain.ended>0&&uncertain.ps.empty(),"uncertainty recycling or blank seeding failed");
    // Constant color offset exceeds anchor threshold while a very low score
    // threshold keeps flow accepted: isolate color lifecycle from uncertainty.
    out.clear();c.colorLimit=.01f;c.confidence=0;auto bright=a;
    for(auto& x:bright.rgb)x=uint8_t(std::min(255,int(x)+15));
    bright=Frame(160,128,bright.rgb);Tracker color(c,160,128,[&](auto r){out.push_back(r);});color.seed(a,a,0);color.step(a,bright,1);color.step(bright,bright,2);
    bool changed=false;for(auto r:out)if(r.type==2&&(r.flags&COLOR))changed=true;check(changed,"color recycling failed");
    out.clear();c.colorLimit=.18f;c.confidence=.25f;c.particles=2;c.mergeAge=3;c.mergeDistance=3;c.spacing=8;
    Tracker merge(c,160,128,[&](auto r){out.push_back(r);});
    for(int i=0;i<2;++i){Particle p;p.id=i+1;p.p={60.f+i,60};p.anchor=a.color(p.p);p.age=3;p.committed=true;merge.ps.push_back(p);}merge.nextID=3;
    merge.step(a,a,3);merge.step(a,a,4);
    bool joined=false;for(auto r:out)if(r.type==2&&(r.flags&MERGED)&&r.id==2&&r.related==1)joined=true;check(joined,"merge lineage failed");
    // Same geometry with merge disabled must instead retire the younger track.
    out.clear();c.mergeDistance=0;Tracker crowded(c,160,128,[&](auto r){out.push_back(r);});
    for(int i=0;i<2;++i){Particle p;p.id=i+1;p.p={60.f+i,60};p.anchor=a.color(p.p);p.age=3;p.committed=true;crowded.ps.push_back(p);}crowded.nextID=3;
    crowded.step(a,a,3);crowded.step(a,a,4);bool culled=false;
    for(auto r:out)if(r.type==2&&(r.flags&CROWDED)&&r.id==2)culled=true;
    check(culled,"crowding recycling failed");
    std::cout<<"PASS: motion, texture confidence, spacing, probation, EOF, stillness, uncertainty, color, crowding, merge lineage\n";
    return 0;
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}}
