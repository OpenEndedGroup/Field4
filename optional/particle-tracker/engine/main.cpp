#include "tracker.hpp"
#include "dense.hpp"
#include <memory>
#include <cerrno>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <map>
#include <sstream>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>
#ifdef _OPENMP
#include <omp.h>
#endif
extern char** environ;
namespace fs=std::filesystem;
struct Process {
    int fd=-1;pid_t pid=-1;
    explicit Process(std::vector<std::string> args) {
        int p[2];if(pipe(p))throw std::runtime_error("pipe failed");
        posix_spawn_file_actions_t actions;posix_spawn_file_actions_init(&actions);
        posix_spawn_file_actions_adddup2(&actions,p[1],STDOUT_FILENO);
        posix_spawn_file_actions_addclose(&actions,p[0]);posix_spawn_file_actions_addclose(&actions,p[1]);
        std::vector<char*> argv;for(auto& s:args)argv.push_back(s.data());argv.push_back(nullptr);
        int e=posix_spawnp(&pid,argv[0],&actions,nullptr,argv.data(),environ);
        posix_spawn_file_actions_destroy(&actions);close(p[1]);
        if(e){close(p[0]);pid=-1;throw std::runtime_error("Cannot launch "+args[0]+": "+std::strerror(e));}fd=p[0];
    }
    Process(const Process&)=delete;
    size_t readBytes(void* data,size_t n) {
        size_t done=0;while(done<n){ssize_t k=read(fd,static_cast<char*>(data)+done,n-done);if(k<0){if(errno==EINTR)continue;throw std::runtime_error("decoder pipe read failed");}if(!k)break;done+=size_t(k);}return done;
    }
    void finish() {
        if(fd>=0){close(fd);fd=-1;}int status=0;pid_t r;do{r=waitpid(pid,&status,0);}while(r<0&&errno==EINTR);pid=-1;
        if(r<0||!WIFEXITED(status)||WEXITSTATUS(status))throw std::runtime_error("ffmpeg/ffprobe failed");
    }
    ~Process(){if(fd>=0)close(fd);if(pid>0){kill(pid,SIGTERM);while(waitpid(pid,nullptr,0)<0&&errno==EINTR){}}}
};
const char* help=R"(particle-video INPUT OUTPUT.pvt [options]
C++17 CPU optical-flow particles; requires ffmpeg and ffprobe on PATH.
  --flow PATH            dense neural flow stream; - reads stdin
  --particles N          target live particles (2000; may underfill)
  --spacing PX           birth minimum distance (0 = automatic)
  --grace N              consecutive bad frames per condition (4)
  --min-track N          discard tracks shorter than N samples (8)
  --confidence X         minimum tracking score (0.25)
  --seed-confidence X    minimum birth score (0.45)
  --color-limit X        RGB RMS change from birth color (0.18)
  --still-speed PX       recycle if speed < PX/frame (0 = disabled)
  --merge-distance PX    coalesce close mature tracks (0 = disabled)
  --merge-age N          minimum pre-merge age in frames (30)
  --merge-velocity PX    maximum velocity disagreement (1.5)
  --seed-budget N        candidate batches per empty slot (16)
  --seed N               deterministic particle RNG seed (1)
  --width N              resize to width, retaining aspect ratio
  --fps X                resample video to this FPS (default source average)
  --frames N             process at most N frames
  --threads N            OpenMP worker count; default <=8
Distances use processed-image pixels; timers use processed frames.
Output is constant-frame-rate, with t = frame / fps. See README.md.
)";
int main(int argc,char** argv) {
    std::string temporary;
    try {
        if(argc==2&&std::string(argv[1])=="--help"){std::cout<<help;return 0;}
        if(argc<3)throw std::runtime_error(help);
        pv::Config c;int width=0,frames=0,threads=0;double fps=0;std::string flowPath;
        for(int i=3;i<argc;i+=2) {
            if(i+1>=argc)throw std::runtime_error("Missing option value");
            std::string key=argv[i],s=argv[i+1];
            if(key=="--flow"){flowPath=s;continue;}
            size_t used=0;double v=std::stod(s,&used);
            if(used!=s.size()||!std::isfinite(v)||v<0||v>1e9)throw std::runtime_error("Invalid number: "+s);
            auto integer=[&](){if(v!=std::floor(v)||v>10000000)throw std::runtime_error("Invalid integer: "+s);return int(v);};
            if(key=="--particles")c.particles=integer();else if(key=="--spacing")c.spacing=float(v);
            else if(key=="--grace")c.grace=integer();else if(key=="--min-track")c.minTrack=integer();
            else if(key=="--confidence")c.confidence=float(v);else if(key=="--seed-confidence")c.seedConfidence=float(v);
            else if(key=="--color-limit")c.colorLimit=float(v);else if(key=="--still-speed")c.stillSpeed=float(v);
            else if(key=="--merge-distance")c.mergeDistance=float(v);else if(key=="--merge-age")c.mergeAge=integer();
            else if(key=="--merge-velocity")c.mergeVelocity=float(v);else if(key=="--seed-budget")c.seedBudget=integer();
            else if(key=="--seed")c.seed=uint32_t(integer());else if(key=="--width")width=integer();
            else if(key=="--fps")fps=v;else if(key=="--frames")frames=integer();else if(key=="--threads")threads=integer();
            else throw std::runtime_error("Unknown option: "+key);
        }
        if(c.particles<1||c.particles>1000000||c.grace<1||c.minTrack<1||c.minTrack>10000||c.mergeAge<1||c.seedBudget<1||c.seedBudget>1000
            ||c.confidence>1||c.seedConfidence>1||c.colorLimit>1||threads>256||fps>1000||width>16384)
            throw std::runtime_error("Option out of range");
        if(c.spacing>0&&c.spacing<1)throw std::runtime_error("Spacing must be zero (auto) or >=1 pixel");
        fs::path input=fs::absolute(argv[1]),output=fs::absolute(argv[2]);
        if(!fs::is_regular_file(input))throw std::runtime_error("Input must be a local video file");
        if(fs::exists(output)&&fs::equivalent(input,output))throw std::runtime_error("Input and output must differ");
        #ifdef _OPENMP
        omp_set_num_threads(threads?threads:std::min(8,omp_get_max_threads()));
        #else
        if(threads>1)std::cerr<<"Built without OpenMP; tracking sequentially\n";
        #endif
        Process probe({"ffprobe","-v","error","-select_streams","v:0","-show_entries","stream=width,height,avg_frame_rate","-of","default=noprint_wrappers=1",input.string()});
        std::string info;char b[1024];size_t k;while((k=probe.readBytes(b,sizeof(b))))info.append(b,k);probe.finish();
        std::istringstream lines(info);std::string line;int w=0,h=0;double sourceFPS=0;
        while(std::getline(lines,line)) {
            auto eq=line.find('=');if(eq==std::string::npos)continue;auto key=line.substr(0,eq),val=line.substr(eq+1);
            if(key=="width")w=std::stoi(val);
            if(key=="height")h=std::stoi(val);
            if(key=="avg_frame_rate"){auto slash=val.find('/');if(slash!=std::string::npos){double den=std::stod(val.substr(slash+1));if(den>0)sourceFPS=std::stod(val.substr(0,slash))/den;}}
        }
        if(w<1||h<1)throw std::runtime_error("No decodable video stream");
        if(width){h=int(std::round(double(h)*width/w));w=width;}if(!fps)fps=sourceFPS;
        if(w<32||h<32||w>16384||h>16384||size_t(w)*h>100000000||!std::isfinite(fps)||fps<=0||fps>1000)
            throw std::runtime_error("Unsupported dimensions/FPS; supply --width and/or --fps");
        if(c.spacing>std::max(w,h)||c.mergeDistance>std::max(w,h))throw std::runtime_error("Spacing/merge distance exceeds image dimensions");
        if(c.spacing==0)c.spacing=std::max(1.f,.75f*std::sqrt(float(w)*h/c.particles));
        std::ostringstream filter;filter.precision(12);filter<<"fps="<<fps<<",scale="<<w<<":"<<h;
        std::vector<std::string> cmd={"ffmpeg","-v","error","-nostdin","-noautorotate","-i",input.string(),"-map","0:v:0","-an","-sn","-vf",filter.str(),"-threads","1"};
        if(frames){cmd.push_back("-frames:v");cmd.push_back(std::to_string(frames));}
        cmd.insert(cmd.end(),{"-f","rawvideo","-pix_fmt","rgb24","pipe:1"});Process decoder(cmd);
        auto readFrame=[&]()->std::vector<uint8_t>{std::vector<uint8_t> bytes(size_t(w)*h*3);size_t n=decoder.readBytes(bytes.data(),bytes.size());if(n&&n!=bytes.size())throw std::runtime_error("Truncated decoded frame");if(!n)bytes.clear();return bytes;};
        auto a=readFrame(),z=readFrame();if(a.empty()||z.empty())throw std::runtime_error("At least two decoded frames are required");
        auto candidate=output.string()+".part."+std::to_string(getpid());
        if(fs::exists(candidate))throw std::runtime_error("Temporary output already exists");
        temporary=candidate;
        std::cerr<<"Flow backend: "<<(flowPath.empty()?"LK fallback":"external dense flow")<<"\n";
        pv::Writer writer(temporary,uint32_t(w),uint32_t(h),fps,c.seed);
        pv::Frame prev(w,h,std::move(a)),curr(w,h,std::move(z));
        std::unique_ptr<pv::DenseStream> dense;
        if(!flowPath.empty()){dense=std::make_unique<pv::DenseStream>(flowPath,w,h,fps);dense->next(0);}
        pv::Tracker tracker(c,w,h,[&](const pv::Record& r){writer.write(r);});
        if(dense)tracker.estimate=[&](const pv::Frame& from,const pv::Frame& to,pv::V p,pv::V){return dense->query(&from==&prev,from,to,p);};
        auto start=std::chrono::steady_clock::now();tracker.seed(prev,curr,0);uint64_t index=1;
        for(;;){tracker.step(prev,curr,index);if(index%30==0)std::cerr<<"frame "<<index<<", live "<<tracker.ps.size()<<", kept "<<tracker.kept<<", dropped "<<tracker.dropped<<"\n";
            prev=std::move(curr);auto bytes=readFrame();if(bytes.empty())break;curr=pv::Frame(w,h,std::move(bytes));++index;if(dense)dense->next(index-1);}
        decoder.finish();if(dense)dense->finish();tracker.finish(index);writer.finish(index+1);fs::rename(temporary,output);temporary.clear();
        double seconds=std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count();
        std::cerr<<"Saved "<<index+1<<" frames, "<<writer.records<<" records; kept="<<tracker.kept<<" dropped="<<tracker.dropped<<" merged="<<tracker.merged
                 <<"; "<<seconds<<" s, "<<(index+1)/seconds<<" processed fps; spacing="<<c.spacing<<"\n";
        return 0;
    }catch(const std::exception& e){if(!temporary.empty()){std::error_code ignored;fs::remove(temporary,ignored);}std::cerr<<"Error: "<<e.what()<<"\n";return 1;}
}
