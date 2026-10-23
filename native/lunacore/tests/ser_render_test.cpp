#include "lunacore/ser_render.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <vector>

namespace {
void require(bool ok,const char* message) { if(!ok){std::cerr<<message<<'\n';std::exit(1);} }
constexpr std::array<float,18> color{64,64,64,64,4095,1,1,1,1,1,0,0,0,1,0,0,0,1};
int encoded(float value) {
    const float s=value<=.0031308f?12.92f*value:1.055f*std::pow(value,1.0f/2.4f)-.055f;
    return static_cast<int>(std::floor(s*255+.5f));
}
double decoded(int value) {
    const double s=value/255.0;
    return s<=.04045 ? s/12.92 : std::pow((s+.055)/1.055,2.4);
}
void natural_tone_tests() {
    constexpr int side=4,stride=side*4+8;
    std::array<std::uint32_t,side*side> input{};
    std::array<std::uint8_t,stride*side> pixels{};
    const auto render=[&](int dn,const lunacore::SerNaturalTone& tone,
                          const std::array<float,18>& model=color) {
        input.fill(static_cast<unsigned>(dn));
        pixels.fill(17);
        const auto before=input;
        require(lunacore::ser_render_natural(input.data(),side,side,0,model.data(),
                pixels.data(),stride,tone),"natural render");
        require(input==before,"natural input mutation");
        for(int y=0;y<side;++y) {
            for(int x=side*4;x<stride;++x)require(pixels[y*stride+x]==17,"natural stride");
            for(int x=0;x<side;++x)require(pixels[y*stride+x*4+3]==255,"natural alpha");
        }
        return pixels[0];
    };
    // Sweep every input code, including the smooth shoulder and existing sensor clipping guard.
    // This tests the quantized result, not only the isolated transfer formula.
    for(float exposure:{1.0f,2.0f,4.0f,6.35f,16.0f})for(float strength:{0.0f,.35f,.6f,1.0f})
        for(float noise:{0.0f,.01f,.1f}) {
            lunacore::SerNaturalTone tone{exposure,strength,noise};
            int previous=0;
            for(int dn=64;dn<=4095;++dn) {
                const int current=render(dn,tone);
                require(current>=previous,"natural nonmonotone gray ramp");
                for(int c=1;c<3;++c)require(pixels[c]==current,"natural neutral color cast");
                if(dn==64)require(current==0,"natural lifted black");
                previous=current;
            }
            require(previous==255,"natural lost all-clipped white guard");
        }
    const lunacore::SerNaturalTone natural{1,.35f,.01f},identity{1,0,.01f};
    for(int dn:{100,200,384,800}) {
        const int baseline=render(dn,identity);
        const int lifted=render(dn,natural);
        require(lifted>baseline,"natural shadow not lifted");
        const double linear=(dn-64)/4031.0;
        require(decoded(lifted)<=linear+.35*.18+.005,"natural shadow overshoot");
    }
    require(render(70,{1,1,.1f})<render(70,{1,1,0}),"natural noise-floor suppression");
    // Above linear one, the old peak normalization collapses the gray ramp. The new shoulder
    // retains distinct bright values, without affecting the physically clipped endpoint policy.
    int previous=0;
    for(int dn:{1400,2200,3200,4000}) {
        const int current=render(dn,{4,.35f,.01f});
        require(current>previous && current<255,"natural highlight gradation");
        previous=current;
    }
    // Independent double-precision formula and linearized color ratios after 8-bit quantization.
    auto ray=color;ray[9]=2;ray[13]=.75f;ray[17]=.25f;
    for(int dn:{384,1024,2048,4000}) {
        render(dn,{2,.35f,.01f},ray);
        const double peak=2.0*(dn-64)/4031.0,t=peak*2;
        const double lift=t+.35*.18*(t/(t+.01))*(t/(t+.1));
        const double mapped=lift<=.65?lift:.65+.35*((lift-.65)/(lift-.30));
        for(int c=0;c<3;++c) {
            const double ratio=c==0?1:(c==1?.375:.125);
            require(std::abs(pixels[c]-encoded(static_cast<float>(mapped*ratio)))<=1,
                    "natural independent tone reference");
            require(std::abs(decoded(pixels[c])/decoded(pixels[0])-ratio)<.012,
                    "natural hue ratio");
        }
    }
    render(384,natural);
    const auto noNightLift=pixels;
    for(auto& value:input)value|=65535u<<16;
    require(lunacore::ser_render_natural(input.data(),side,side,0,color.data(),pixels.data(),
        stride,natural),"natural ignores Night tone");
    require(pixels==noNightLift,"natural unexpectedly stacks Night lift");
    for(const auto invalid:std::array<lunacore::SerNaturalTone,9>{{
        {NAN,.35f,.01f},{.99f,.35f,.01f},{16.01f,.35f,.01f},
        {1,NAN,.01f},{1,-.01f,.01f},{1,1.01f,.01f},
        {1,.35f,NAN},{1,.35f,-.01f},{1,.35f,.101f}}}) {
        pixels.fill(73);const auto sentinel=pixels;
        require(!lunacore::ser_render_natural(input.data(),side,side,0,color.data(),
            pixels.data(),stride,invalid),"natural accepted invalid tone");
        require(pixels==sentinel,"natural invalid tone mutated output");
    }
    auto invalidColor=color;invalidColor[9]=NAN;
    pixels.fill(73);const auto sentinel=pixels;
    require(!lunacore::ser_render_natural(input.data(),side,side,0,invalidColor.data(),
        pixels.data(),stride,natural),"natural invalid color");
    require(pixels==sentinel,"natural invalid metadata mutated output");
    std::cout<<"Natural RAW tone: monotone gray, fixed black, bounded shadows, noise floor, "
        "highlight gradation, hue ratios, Night isolation, invalid-input immutability passed\n";
}
}
int main() {
    constexpr int width=128,height=96;
    constexpr std::size_t stride=width*4+16;
    std::vector<std::uint32_t> raw(width*height,64);
    std::vector<std::uint8_t> output(stride*height,17);
    for(int cfa=0;cfa<4;++cfa) for(int dn:{64,100,384,1024,4095}) {
        std::fill(raw.begin(),raw.end(),static_cast<std::uint32_t>(dn));
        const auto before=raw;
        require(lunacore::ser_render(raw.data(),width,height,cfa,color.data(),output.data(),stride),"render");
        const int expected=encoded(static_cast<float>(dn-64)/4031);
        for(int y=0;y<height;++y) {
            for(int x=0;x<width;++x) {
                const auto p=static_cast<std::size_t>(y)*stride+x*4;
                for(int channel=0;channel<3;++channel)
                    require(output[p+channel]==expected,"neutral/CFA/border mismatch");
                require(output[p+3]==255,"alpha mismatch");
            }
            for(std::size_t x=width*4;x<stride;++x)require(output[y*stride+x]==17,"padding touched");
        }
        require(raw==before,"source mutation");
    }
    for(int lift:{0,65535}) {
        for(int y=0;y<height;++y)for(int x=0;x<width;++x)
            raw[y*width+x]=static_cast<unsigned>(64+(x/2)*50)|(static_cast<unsigned>(lift)<<16);
        require(lunacore::ser_render(raw.data(),width,height,0,color.data(),output.data(),stride),"ramp");
        for(int x=4;x<width-4;++x) {
            const auto p=40*stride+x*4;
            if(lift==0)for(int c=0;c<3;++c)
                require(output[p+c]>=output[p-4+c],"nonmonotone baseline channel");
            const int luma=output[p]+2*output[p+1]+output[p+2];
            const int previous=output[p-4]+2*output[p-3]+output[p-2];
            require(luma>=previous,"nonmonotone rendered luma ramp");
        }
    }
    // Both cardinal gradients are 10 DN, but their means differ by 100 DN.
    // Strict float comparisons selected one direction after normalization rounding.
    // The explicit numerical tie must average both, without changing clear-edge selection.
    for(int cfa=0;cfa<4;++cfa) for(int extra:{0,4}) {
        std::fill(raw.begin(),raw.end(),384u);
        const int x=16+((cfa==1 || cfa==2)?1:0),y=16;
        raw[y*width+x-1]=300;raw[y*width+x+1]=310;
        raw[(y-1)*width+x]=400;raw[(y+1)*width+x]=410+extra;
        require(lunacore::ser_render(raw.data(),width,height,cfa,color.data(),
                                    output.data(),stride),"gradient tie render");
        const int expected=encoded((extra==0?291.0f:241.0f)/4031);
        require(output[y*stride+x*4+1]==expected,"gradient tie/clear direction mismatch");
    }
    // A tiny smooth change in residual green shading must not cause a visible direction jump.
    // This scans across the former hard epsilon boundary and the new transition's far boundary.
    {
        constexpr int side=8;
        std::vector<std::uint32_t> input(side*side,384u);
        std::vector<std::uint8_t> rendered(side*side*4);
        input[4*side+3]=300;input[4*side+5]=310;
        input[3*side+4]=400;input[5*side+4]=410;
        float gains[4]={1,1,1,1};
        lunacore::SerRenderShading shade{gains,1,1,0,0,side,side};
        int previous=-1,largest=0;
        for(int step=0;step<=2000;++step) {
            gains[2]=1+static_cast<float>(step)*.0001f;
            require(lunacore::ser_render(input.data(),side,side,0,color.data(),
                                        rendered.data(),side*4,&shade),"near tie sweep");
            const int current=rendered[(4*side+4)*4+1];
            if(previous>=0)largest=std::max(largest,std::abs(current-previous));
            previous=current;
        }
        std::cout<<"near-tie shading sweep max adjacent output change="<<largest<<" DN\n";
        require(largest<=1,"discontinuous shading/direction transition");
    }
    // Even a faint 2-DN line is a clear direction relative to this narrow numerical band.
    for(int contrast:{2,4,8}) {
        std::fill(raw.begin(),raw.end(),384u);
        raw[17*width+16]=static_cast<unsigned>(384+contrast);
        require(lunacore::ser_render(raw.data(),width,height,0,color.data(),
                                    output.data(),stride),"faint line");
        require(output[16*stride+16*4+1]==encoded(320.0f/4031),"faint clear direction changed");
    }
    // Constant color checks RGGB gains separately from the sensor-to-sRGB row-major matrix.
    const std::array<float,18> colored{64,64,64,64,4095,2,1,1,3,0,0,1,0,1,0,1,0,0};
    std::fill(raw.begin(),raw.end(),384u);
    for(int cfa=0;cfa<4;++cfa) {
        require(lunacore::ser_render(raw.data(),width,height,cfa,colored.data(),output.data(),stride),"color");
        require(output[0]==encoded(3*320.0f/4031),"matrix red");
        require(output[1]==encoded(320.0f/4031),"matrix green");
        require(output[2]==encoded(2*320.0f/4031),"matrix blue");
    }
    // A positive out-of-gamut ray retains RGB ratios while RAW still has color headroom.
    // An in-gamut ray is unchanged. Neutral ramps above already cover black and white endpoints.
    auto ray=color;ray[9]=2;ray[13]=.75f;ray[17]=.25f;
    for(int dn=64;dn<=4074;dn+=31) {
        std::fill(raw.begin(),raw.end(),static_cast<unsigned>(dn));
        require(lunacore::ser_render(raw.data(),width,height,0,ray.data(),output.data(),stride),"ray");
        const float peak=std::min(1.0f,2.0f*static_cast<float>(dn-64)/4031);
        require(std::abs(output[0]-encoded(peak))<=1,"highlight red");
        require(std::abs(output[1]-encoded(peak*.375f))<=1,"highlight hue green");
        require(std::abs(output[2]-encoded(peak*.125f))<=1,"highlight hue blue");
    }
    // [-.25,1,2] has negative red. Neutral-axis compression has a fixed G/B ratio;
    // independently clamping red would produce the wrong ratio (0.5 instead of this value).
    ray[9]=-.25f;ray[13]=1;ray[17]=2;
    constexpr double yRay=-.25*.2126+.7152+2*.0722;
    constexpr double chroma=yRay/(yRay+.25);
    constexpr double green=yRay+(1-yRay)*chroma,blue=yRay+(2-yRay)*chroma;
    for(int dn=64;dn<=4074;dn+=31) {
        std::fill(raw.begin(),raw.end(),static_cast<unsigned>(dn));
        require(lunacore::ser_render(raw.data(),width,height,0,ray.data(),output.data(),stride),"negative ray");
        const float peak=std::min(1.0f,static_cast<float>(blue*(dn-64)/4031));
        require(output[0]==0,"negative gamut red");
        require(std::abs(output[1]-encoded(peak*static_cast<float>(green/blue)))<=1,"negative hue");
        require(std::abs(output[2]-encoded(peak))<=1,"negative gamut blue");
    }
    // Actual capture metadata, but entirely generated RAW. Fully clipped samples contain no hue.
    const std::array<float,18> actual{64,64,64,64,1023,2.0976562f,1,1,1.5957031f,
        1.609375f,-.6015625f,0,-.3203125f,1.453125f,-.1328125f,
        .0390625f,-.9140625f,1.8671875f};
    for(int cfa=0;cfa<4;++cfa) {
        std::fill(raw.begin(),raw.end(),1023u);
        const auto before=raw;
        require(lunacore::ser_render(raw.data(),width,height,cfa,actual.data(),
                                    output.data(),stride),"actual all clipped");
        for(int y=0;y<height;++y)for(int x=0;x<width;++x)for(int c=0;c<4;++c)
            require(output[y*stride+x*4+c]==255,"clipped actual metadata must be white");
        require(raw==before,"clip guard source mutation");
    }
    auto dimTransform=actual;
    for(int i=9;i<18;++i)dimTransform[i]*=.1f;
    require(lunacore::ser_render(raw.data(),width,height,2,dimTransform.data(),
                                output.data(),stride),"clipped low-gain matrix");
    require(output[0]==255 && output[1]==255 && output[2]==255,
            "fully clipped must reach white rather than a dim neutral peak");
    int previousGreen=0;
    constexpr int nearClipExpected[6][3]={{255,125,228},{255,145,231},{255,193,239},
        {255,233,249},{255,252,254},{255,255,255}};
    for(int dn=1010;dn<=1023;++dn) {
        std::fill(raw.begin(),raw.end(),static_cast<unsigned>(dn));
        require(lunacore::ser_render(raw.data(),width,height,2,actual.data(),
                                    output.data(),stride),"clip shoulder");
        const int index=std::max(0,dn-1018);
        for(int c=0;c<3;++c)require(std::abs(output[c]-nearClipExpected[index][c])<=1,
                                   "combined clip confidence formula");
        require(output[1]>=previousGreen,"nonmonotone neutral convergence");
        previousGreen=output[1];
    }
    // Independent double-precision reference values at an R site in a constant GBRG field.
    // Partial clipping now bounds camera RGB before the CCM, but must not force colored rays white.
    constexpr int singleExpected[4][3]={{137,231,102},{145,110,255},{255,88,154},{137,231,102}};
    constexpr int threeExpected[4][3]={{247,171,255},{250,255,111},{193,255,244},{247,171,255}};
    for(int three=0;three<2;++three)for(int slot=0;slot<4;++slot) {
        int values[4]={400,300,250,400};
        if(three) {for(int& v:values)v=1023;values[slot]=400;} else values[slot]=1023;
        for(int y=0;y<height;++y)for(int x=0;x<width;++x)
            raw[y*width+x]=static_cast<unsigned>(values[(y&1)*2+(x&1)]);
        require(lunacore::ser_render(raw.data(),width,height,2,actual.data(),
                                    output.data(),stride),"partial clip");
        for(int c=0;c<3;++c)require(std::abs(output[17*stride+16*4+c]-
            (three?threeExpected[slot][c]:singleExpected[slot][c]))<=1,"partial clip policy");
        require(std::min(output[17*stride+16*4],std::min(output[17*stride+16*4+1],
            output[17*stride+16*4+2]))<245,"colored clipping forced white");
    }
    constexpr int channels[4][4]={{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
    // Physical neutral exposure ramps: RAW clips before WB, rather than assuming equal RAW DN.
    int largestNeutralSpread=0;
    for(int cfa=0;cfa<4;++cfa)for(float corner:{1.0f,2.0f})for(int wbCase=0;wbCase<2;++wbCase) {
        constexpr int side=16;
        auto model=actual;
        if(wbCase) {
            model[5]=.8f;model[6]=.9f;model[7]=1.1f;model[8]=1.5f;
            for(int i=9;i<18;++i)model[i]=(i==9 || i==13 || i==17)?1.0f:0.0f;
        }
        float shadeValues[4]={corner,corner,corner,corner};
        lunacore::SerRenderShading correction{shadeValues,1,1,0,0,side,side};
        std::vector<std::uint32_t> input(side*side);
        std::vector<std::uint8_t> rendered(side*side*4);
        for(int step=0;step<=36;++step) {
            const double exposure=.7+step*.05;
            for(int y=0;y<side;++y)for(int x=0;x<side;++x) {
                const int ch=channels[cfa][(y&1)*2+(x&1)];
                input[y*side+x]=static_cast<unsigned>(64+std::floor(959*
                    std::min(1.0,exposure/(model[5+ch]*corner))+.5));
            }
            const auto before=input;
            require(lunacore::ser_render(input.data(),side,side,cfa,model.data(),
                                        rendered.data(),side*4,&correction),"neutral exposure ramp");
            for(int y=2;y<side-2;++y)for(int x=2;x<side-2;++x) {
                const auto* p=rendered.data()+(y*side+x)*4;
                const int spread=std::max(p[0],std::max(p[1],p[2]))-
                    std::min(p[0],std::min(p[1],p[2]));
                largestNeutralSpread=std::max(largestNeutralSpread,spread);
                require(spread<=3,"neutral ramp acquired a color cast");
                if(wbCase && corner==1 && step==4)for(int c=0;c<3;++c)
                    require(std::abs(p[c]-encoded(.8f))<=1,"low WB ceiling changed exposure");
            }
            require(before==input,"neutral ramp mutated RAW");
        }
    }
    std::cout<<"neutral exposure .7..2.5 / 4 CFA / center+corner / 2 WB models max spread="
        <<largestNeutralSpread<<" DN\n";
    std::fill(raw.begin(),raw.end(),384u);
    const float constantGains[4]={2,1.25f,1.5f,1.75f};
    lunacore::SerRenderShading shade{constantGains,1,1,12,8,112,88};
    for(int cfa=0;cfa<4;++cfa) {
        require(lunacore::ser_render(raw.data(),width,height,cfa,color.data(),
                                    output.data(),stride,&shade),"constant shading");
        for(int y=0;y<2;++y)for(int x=0;x<2;++x) {
            const int ch=channels[cfa][y*2+x],rgb=ch==0?0:(ch==3?2:1);
            require(output[y*stride+x*4+rgb]==encoded(320.0f/4031*constantGains[ch]),
                    "shading RGGB/CFA mapping");
        }
    }
    float grid[16];
    for(int c=0;c<4;++c) {grid[c]=1;grid[4+c]=2;grid[8+c]=1.5f;grid[12+c]=2.5f;}
    shade={grid,2,2,12,8,112,88};
    for(int cfa=0;cfa<4;++cfa) {
        require(lunacore::ser_render(raw.data(),width,height,cfa,color.data(),
                                    output.data(),stride,&shade),"bilinear shading");
        for(int y:{0,8,40,86,95})for(int x:{0,12,48,110,127}) {
            const float gain=1+std::clamp((x-12)/99.0f,0.0f,1.0f)+
                .5f*std::clamp((y-8)/79.0f,0.0f,1.0f);
            const int ch=channels[cfa][(y&1)*2+(x&1)],rgb=ch==0?0:(ch==3?2:1);
            require(std::abs(output[y*stride+x*4+rgb]-encoded(320.0f/4031*gain))<=1,
                    "shading active origin/interpolation/edge clamp");
        }
    }
    // An independently constructed, color-dependent inverse vignette should become neutral.
    // Quantized RAW inputs allow one output DN; no frame pixels or spatial means are substituted.
    for(int c=0;c<4;++c)for(int corner=0;corner<4;++corner)
        grid[corner*4+c]=1+(corner%2)*.5f+(corner/2)*.25f+c*.125f;
    for(int cfa=0;cfa<4;++cfa) {
        for(int y=0;y<height;++y)for(int x=0;x<width;++x) {
            const int ch=channels[cfa][(y&1)*2+(x&1)];
            const double gain=1+std::clamp((x-12)/99.0,0.0,1.0)*.5+
                std::clamp((y-8)/79.0,0.0,1.0)*.25+ch*.125;
            raw[y*width+x]=static_cast<unsigned>(64+std::floor(320/gain+.5));
        }
        const auto before=raw;
        require(lunacore::ser_render(raw.data(),width,height,cfa,color.data(),
                                    output.data(),stride,&shade),"inverse vignette");
        for(int y=0;y<height;++y)for(int x=0;x<width;++x)for(int c=0;c<3;++c)
            require(std::abs(output[y*stride+x*4+c]-encoded(320.0f/4031))<=1,
                    "inverse vignette not neutral");
        require(raw==before,"shading mutated source");
    }
    grid[0]=NAN;
    require(!lunacore::ser_render(raw.data(),width,height,0,color.data(),output.data(),stride,&shade),
            "invalid shading gain");
    grid[0]=1;shade.right=width+1;
    require(!lunacore::ser_render(raw.data(),width,height,0,color.data(),output.data(),stride,&shade),
            "invalid shading area");
    require(!lunacore::ser_render(raw.data(),width,height,4,color.data(),output.data(),stride),"invalid CFA");
    require(!lunacore::ser_render(raw.data(),width,height,0,color.data(),output.data(),width*4-1),"short stride");
    auto invalid=color;invalid[9]=NAN;
    require(!lunacore::ser_render(raw.data(),width,height,0,invalid.data(),output.data(),stride),"invalid matrix");
    invalid=color;
    for(int i=9;i<18;++i) invalid[i]=0;
    require(!lunacore::ser_render(raw.data(),width,height,0,invalid.data(),output.data(),stride),"missing matrix");
    natural_tone_tests();
    std::cout<<"SER renderer: CFA, black/white, color, gradient ties, monotone ramp, gamut rays, clipped white, bounded partial clip color, shading, alpha, stride, immutability passed\n";
}
