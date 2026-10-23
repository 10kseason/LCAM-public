#include "lunacore/ser_night.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <random>
#include <vector>

namespace {
void check(bool value,const char* message){if(!value){std::cerr<<message<<'\n';std::exit(1);}}
struct Fixture {
    int w=256,h=256;
    std::array<std::vector<std::uint16_t>,3> pixels;
    std::array<lunacore::RawFrame,3> frames{};
    std::vector<std::uint16_t> truth;
    explicit Fixture(bool correlated=false):truth(static_cast<std::size_t>(w)*h) {
        std::mt19937 random(76119);std::normal_distribution<double> noise(0,4),common(0,8);
        for(auto& p:pixels)p.resize(truth.size());
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const int p=y*w+x;
            const double scene=y<h/2? .30+.12*std::sin(x*.19)+.08*std::cos(y*.27)+.04*std::sin((x+y)*.11):.08;
            truth[p]=static_cast<std::uint16_t>(std::floor(64+4031*scene+.5));
            const double shared=correlated?common(random):0;
            for(int i=0;i<3;++i)pixels[i][p]=static_cast<std::uint16_t>(std::clamp(
                std::floor(truth[p]+shared+noise(random)*(correlated?.25:1)+.5),65.0,4090.0));
        }
        for(int f=0;f<3;++f){frames[f].pixels=pixels[f].data();auto& m=frames[f].metadata;
            m.black={64,64,64,64};m.white=4095;m.exposure=1;
            m.intercept.fill((correlated?65.0:16.0)/(4031.0*4031.0));}
    }
    lunacore::SerNightPlan prepare(){lunacore::SerNightPlan p;
        check(lunacore::prepare_ser_night(frames,w,h,{0,0,w,h},p),"prepare failed");return p;}
};

void compare_baseline(const Fixture& f,const lunacore::SerNightPlan& plan,const std::vector<std::uint32_t>& out){
    for(std::size_t p=0;p<out.size();++p)check(out[p]==f.pixels[plan.baseline][p],"not exact baseline");
}
}

int main(){
    Fixture f;const auto original=f.pixels;
    auto plan=f.prepare();
    std::cout<<"accepted mask "<<plan.accepted_mask<<" baseline "<<plan.baseline<<'\n';
    check(plan.accepted_mask==7,"textured synthetic alignment not accepted");
    std::vector<std::uint32_t> out(f.truth.size()),again(out.size());lunacore::SerNightStats stats;
    check(lunacore::fuse_ser_night(f.frames,f.w,f.h,plan,out.data(),stats),"fusion failed");
    check(stats.lifted_cells>50,"static noise has no useful lift");
    double before=0,after=0,lifted_error=0;std::size_t count=0;
    for(int y=f.h/2+12;y<f.h-12;++y)for(int x=12;x<f.w-12;++x){
        const int p=y*f.w+x; if(!(out[p]>>16))continue;
        const double a=static_cast<double>(f.pixels[plan.baseline][p])-f.truth[p];
        const double b=static_cast<double>(out[p]&65535)-f.truth[p];before+=a*a;after+=b*b;++count;
        const double gain=std::exp2(.35*static_cast<double>(out[p]>>16)/65535.0);
        lifted_error+=b*b*gain*gain;
    }
    check(count>100,"insufficient tested signal");check(after<before*.85,"independent noise not reduced");
    check(lifted_error<before,"added gain exceeds observed noise reserve");
    std::cout<<"static lifted "<<stats.lifted_cells<<" changed "<<stats.changed<<" MSE ratio "<<after/before<<'\n';
    check(lunacore::fuse_ser_night(f.frames,f.w,f.h,plan,again.data(),stats)&&again==out,"nondeterministic");
    check(f.pixels==original,"source modified");
    for(int y=0;y<f.h;++y)for(int x=0;x<f.w;++x){
        const int p=y*f.w+x;
        if(x<6||y<6||x+6>=f.w||y+6>=f.h)check(out[p]==f.pixels[plan.baseline][p],"border changed");
        const int cell=(y&~1)*f.w+(x&~1);
        check((out[p]>>16)==(out[cell]>>16),"CFA channels use different EV");
    }
    check(lunacore::fuse_ser_night(f.frames,f.w,f.h,plan,out.data(),stats,false),"OFF failed");
    compare_baseline(f,plan,out);

    // A moving patch cannot borrow brightness, including its interior and erosion border.
    const int candidate=(plan.baseline+1)%3;
    for(int y=170;y<210;++y)for(int x=80;x<120;++x)f.pixels[candidate][y*f.w+x]+=150;
    check(lunacore::fuse_ser_night(f.frames,f.w,f.h,plan,out.data(),stats),"motion processing failed");
    for(int y=170;y<210;++y)for(int x=80;x<120;++x)
        check(out[y*f.w+x]==f.pixels[plan.baseline][y*f.w+x],"moving patch not preserved");

    Fixture correlated(true);auto cp=correlated.prepare();
    check(lunacore::fuse_ser_night(correlated.frames,f.w,f.h,cp,out.data(),stats),"correlated failed");
    check(stats.lifted_cells==0,"common pattern noise incorrectly earns reserve");
    compare_baseline(correlated,cp,out);

    Fixture duplicate;duplicate.pixels[1]=duplicate.pixels[0];duplicate.pixels[2]=duplicate.pixels[0];
    auto dp=duplicate.prepare();check(dp.accepted_mask==(1<<dp.baseline),"duplicate evidence accepted");
    check(lunacore::fuse_ser_night(duplicate.frames,f.w,f.h,dp,out.data(),stats),"duplicate render failed");
    compare_baseline(duplicate,dp,out);

    Fixture missing;for(auto& frame:missing.frames)frame.metadata.intercept.fill(0);
    auto mp=missing.prepare();check(mp.accepted_mask==1,"missing noise not reference only");
    check(lunacore::fuse_ser_night(missing.frames,f.w,f.h,mp,out.data(),stats),"missing noise render failed");
    compare_baseline(missing,mp,out);
    missing.frames[1]=missing.frames[0];missing.frames[2]=missing.frames[0];
    mp=missing.prepare();check(mp.accepted_mask==1&&mp.baseline==0,"single frame rejected");

    Fixture range;
    for(int y=150;y<190;++y)for(int x=40;x<90;++x)for(auto& p:range.pixels)p[y*f.w+x]=64;
    for(int y=190;y<225;++y)for(int x=40;x<90;++x)for(auto& p:range.pixels)p[y*f.w+x]=4095;
    auto rp=range.prepare();
    check(lunacore::fuse_ser_night(range.frames,f.w,f.h,rp,out.data(),stats),"clipped fixture failed");
    for(int y=150;y<225;++y)for(int x=40;x<90;++x)
        check(out[y*f.w+x]==range.pixels[rp.baseline][y*f.w+x],"black or saturated changed");

    Fixture roi;lunacore::SerNightPlan ap;
    check(lunacore::prepare_ser_night(roi.frames,f.w,f.h,{18,22,238,230},ap),"offset ROI rejected");
    check(lunacore::fuse_ser_night(roi.frames,f.w,f.h,ap,out.data(),stats),"offset ROI fusion failed");
    for(int y=0;y<f.h;++y)for(int x=0;x<f.w;++x)
        if(x<18||x>=238||y<22||y>=230)
            check(out[y*f.w+x]==roi.pixels[ap.baseline][y*f.w+x],"inactive RAW changed");

    Fixture translated;
    const auto unshifted=translated.pixels[1];
    for(int y=0;y<f.h;++y)for(int x=2;x<f.w;++x)
        translated.pixels[1][y*f.w+x]=unshifted[y*f.w+x-2];
    auto tp=translated.prepare();
    check(tp.accepted_mask==7,"translated evidence rejected");
    check(tp.shifts[2]-tp.shifts[0]==2,"wrong Bayer translation");
    check(lunacore::fuse_ser_night(translated.frames,f.w,f.h,tp,out.data(),stats),"translated fusion failed");
    check(stats.lifted_cells>50,"translated dark signal has no lift");

    Fixture structure;
    for(int y=140;y<230;++y)for(int x=20;x<230;++x){
        const int p=y*structure.w+x;
        const std::uint16_t dn=static_cast<std::uint16_t>(386+((x%5==0||y%7==0)?6:0));
        for(auto& pixels:structure.pixels)pixels[p]=dn;
    }
    auto sp=structure.prepare();
    check(lunacore::fuse_ser_night(structure.frames,f.w,f.h,sp,out.data(),stats),"structure failed");
    for(int y=144;y<226;++y)for(int x=24;x<226;++x)
        check((out[y*f.w+x]&65535)==structure.pixels[sp.baseline][y*f.w+x],"faint structure altered");

    Fixture channels;
    for(int y=0;y<f.h;++y)for(int x=0;x<f.w;++x){
        const int c=(y%2)*2+x%2;
        for(auto& image:channels.pixels)image[y*f.w+x]+=static_cast<std::uint16_t>(c*7);
    }
    for(auto& frame:channels.frames)frame.metadata.black={64,71,78,85};
    auto bp=channels.prepare();
    check(lunacore::fuse_ser_night(channels.frames,f.w,f.h,bp,out.data(),stats),"CFA black levels failed");
    check(stats.lifted_cells>50,"CFA black levels prevented all evidence");
    for(int y=0;y<f.h;++y)for(int x=0;x<f.w;++x){
        const int p=y*f.w+x;const auto dn=out[p]&65535;
        const auto lo=std::min({channels.pixels[0][p],channels.pixels[1][p],channels.pixels[2][p]});
        const auto hi=std::max({channels.pixels[0][p],channels.pixels[1][p],channels.pixels[2][p]});
        check(dn>=lo&&dn<=hi,"fusion invented out-of-range detail");
    }

    Fixture invalid;auto ip=invalid.prepare();ip.shifts[0]=1;
    check(!lunacore::fuse_ser_night(invalid.frames,f.w,f.h,ip,out.data(),stats),"odd CFA shift accepted");
    ip=invalid.prepare();ip.tile_bounds[0]=std::nanf("");
    check(lunacore::fuse_ser_night(invalid.frames,f.w,f.h,ip,out.data(),stats),"invalid bounds should fall back");
    invalid.frames[0].metadata.white=std::numeric_limits<double>::infinity();
    check(!lunacore::prepare_ser_night(invalid.frames,f.w,f.h,{0,0,f.w,f.h},ip),"invalid white accepted");
    std::cout<<"SER synthetic gates passed\n";
}
