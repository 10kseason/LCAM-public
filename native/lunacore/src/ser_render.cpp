#include "lunacore/ser_render.h"
#include <algorithm>
#include <cmath>

namespace lunacore {
namespace {
constexpr int channels[4][4] = {{0,1,2,3},{1,0,3,2},{1,3,0,2},{3,1,2,0}};
// Reflect across the outside edge without changing Bayer parity.
int reflect(int x, int size) { return x < 0 ? -x : (x >= size ? 2 * size - 2 - x : x); }
struct Render {
    struct ClipState { float any,all,ceiling; };
    const std::uint32_t* data;
    int width, height, cfa;
    const float* m;
    const SerRenderShading* shading;
    const SerNaturalTone* tone;
    int channel(int x, int y) const { return channels[cfa][(y & 1) * 2 + (x & 1)]; }
    float shading_gain(int x,int y,int ch) const {
        if(!shading) return 1.0f;
        const auto& s=*shading;
        const float gx=std::clamp(static_cast<float>(x-s.left)/
            static_cast<float>(std::max(1,s.right-s.left-1)),0.0f,1.0f)*(s.columns-1);
        const float gy=std::clamp(static_cast<float>(y-s.top)/
            static_cast<float>(std::max(1,s.bottom-s.top-1)),0.0f,1.0f)*(s.rows-1);
        const int ix=static_cast<int>(gx),iy=static_cast<int>(gy);
        const int nx=std::min(ix+1,s.columns-1),ny=std::min(iy+1,s.rows-1);
        const float fx=gx-ix,fy=gy-iy;
        const float a=s.gains[(iy*s.columns+ix)*4+ch],b=s.gains[(iy*s.columns+nx)*4+ch];
        const float c=s.gains[(ny*s.columns+ix)*4+ch],d=s.gains[(ny*s.columns+nx)*4+ch];
        const float top=a+(b-a)*fx,bottom=c+(d-c)*fx;
        return top+(bottom-top)*fy;
    }
    float sample(int x, int y) const {
        x = reflect(x, width); y = reflect(y, height);
        const int position = (y & 1) * 2 + (x & 1);
        const float raw = static_cast<float>(data[y * width + x] & 65535u);
        const int ch=channel(x,y);
        return (std::max(0.0f, (raw - m[position]) / (m[4] - m[position])) *
            shading_gain(x,y,ch))*m[5+ch];
    }
    float pair(float a, float b, float c, float d) const {
        const float ab = std::abs(a-b), cd = std::abs(c-d);
        // Equal RAW gradients can differ after CPU division / GPU reciprocal rounding.
        // A discontinuous direction choice would amplify that tiny error into visible color.
        const float scale=std::max(1.0f,std::max(std::max(std::abs(a),std::abs(b)),
                                               std::max(std::abs(c),std::abs(d))));
        const float tie=0.00000095367431640625f*scale; // 8 * float epsilon.
        const float transition=0.000244140625f*scale; // 2^-12; narrow direction uncertainty band.
        const float average=((a+b)+(c+d))*0.25f;
        const float difference=cd-ab;
        const float weight=std::clamp((std::abs(difference)-tie)/(transition-tie),0.0f,1.0f);
        const float directed=difference>0 ? (a+b)*0.5f : (c+d)*0.5f;
        // Continuous at both ends: merely widening a hard tie would move the same instability.
        return average+(directed-average)*weight;
    }
    float ev(int x, int y) const {
        const unsigned value = data[(y & ~1) * width + (x & ~1)] >> 16;
        return static_cast<float>(value) * (0.35f / 65535.0f);
    }
    ClipState clipped_cell(int x,int y) const {
        x&=~1;y&=~1;
        const int p=y*width+x;
        float closest=1.0f,farthest=0.0f;
        // One RAW scan supplies partial-clip evidence and the separate all-clip white guard.
        for(int c=0;c<4;++c) {
            const float raw=static_cast<float>(data[p+(c/2)*width+c%2]&65535u);
            const float margin=std::max(4.0f,(m[4]-m[c])*0.005f);
            const float h=std::clamp((m[4]-raw)/margin,0.0f,1.0f);
            closest=std::min(closest,h);farthest=std::max(farthest,h);
        }
        if(closest>=1.0f) return {0,0,0}; // No extra shading reads for unsaturated cells.
        float ceiling=256.0f;
        for(int c=0;c<4;++c) {
            const int sx=x+c%2,sy=y+c/2,ch=channel(sx,sy);
            ceiling=std::min(ceiling,shading_gain(sx,sy,ch)*m[5+ch]);
        }
        return {1.0f-closest*closest*(3.0f-2.0f*closest),
                1.0f-farthest*farthest*(3.0f-2.0f*farthest),ceiling};
    }
    void pixel(int x, int y, std::uint8_t* output) const {
        const int ch = channel(x,y);
        const float p = sample(x,y), l=sample(x-1,y), r=sample(x+1,y);
        const float u=sample(x,y-1), d=sample(x,y+1);
        float rgb[3];
        if (ch==0 || ch==3) {
            rgb[ch==0?0:2]=p;
            rgb[1]=pair(l,r,u,d);
            rgb[ch==0?2:0]=pair(sample(x-1,y-1), sample(x+1,y+1),
                                sample(x+1,y-1), sample(x-1,y+1));
        } else {
            rgb[1]=p;
            const bool horizontalRed = channel(x-1,y)==0;
            rgb[horizontalRed?0:2]=(l+r)*0.5f;
            rgb[horizontalRed?2:0]=(u+d)*0.5f;
        }
        const auto clipping=clipped_cell(x,y);
        // Clip only with physical sensor evidence, at the first channel's corrected saturation.
        // This is bounded rendering of lost highlight color, not reconstruction or white detection.
        if(clipping.any>0)for(float& value:rgb)
            value+=(std::min(value,clipping.ceiling)-value)*clipping.any;
        float linear[3];
        for (int c=0;c<3;++c) {
            const int i=9+3*c;
            linear[c]=(m[i]*rgb[0]+m[i+1]*rgb[1])+m[i+2]*rgb[2];
        }
        // Preserve hue direction about the neutral axis when CCM leaves the positive gamut.
        // Then use one highlight scale, rather than independently clipping bright channels.
        const float minimum=std::min(linear[0],std::min(linear[1],linear[2]));
        if(minimum<0) {
            const float luma=(0.2126f*linear[0]+0.7152f*linear[1])+0.0722f*linear[2];
            const float chroma=luma>0 ? luma/(luma-minimum) : 0.0f;
            for(int c=0;c<3;++c)
                linear[c]=luma>0 ? std::max(0.0f,luma+(linear[c]-luma)*chroma) : 0.0f;
        }
        if(tone) {
            // A global, continuous monotone curve on the RGB peak cannot form spatial halos.
            // Both rational factors increase with t, so the shadow addition has nonnegative
            // derivative. Its total contribution is <=0.18. For a positive noise floor its
            // slope at black is zero: do not amplify the weakest samples as much as mid-shadows.
            // The shoulder is C1 at 0.65, strictly increasing and asymptotic to one. Applying
            // one common scale retains positive RGB ratios (apart from final 8-bit rounding).
            // It cannot restore clipped RAW, denoise, or distinguish sky from a bright wall.
            const float peak=std::max(linear[0],std::max(linear[1],linear[2]));
            if(peak>0) {
                const float t=peak*tone->exposureGain;
                const float floorWeight=t/(t+tone->noiseFloor);
                const float lifted=t+tone->shadowStrength*0.18f*floorWeight*(t/(t+0.10f));
                const float mapped=lifted<=0.65f ? lifted :
                    0.65f+0.35f*((lifted-0.65f)/(lifted-0.30f));
                const float scale=mapped/peak;
                for(float& value:linear)value*=scale;
            }
        } else {
            const float gamutPeak=std::max(linear[0],std::max(linear[1],linear[2]));
            if(gamutPeak>1.0f) for(float& value:linear) value/=gamutPeak;
            const float yv=(0.2126f*linear[0]+0.7152f*linear[1])+0.0722f*linear[2];
            float gain=1.0f;
            if (yv>0 && yv<0.25f) {
                const float t=1.0f-yv/0.25f;
                gain=1.0f+(std::exp2(ev(x,y))-1.0f)*t*t;
                const float peak=std::max(linear[0],std::max(linear[1],linear[2]));
                if (peak>0) gain=std::min(gain,std::max(1.0f,1.0f/peak));
            }
            for(float& value:linear)value*=gain;
        }
        for(int c=0;c<3;++c) {
            const float bounded=std::clamp(linear[c],0.0f,1.0f);
            const float v=bounded+(1.0f-bounded)*clipping.all;
            const float encoded=v<=0.0031308f ? 12.92f*v : 1.055f*std::pow(v,1.0f/2.4f)-0.055f;
            output[c]=static_cast<std::uint8_t>(std::clamp(std::floor(encoded*255.0f+0.5f),0.0f,255.0f));
        }
        output[3]=255;
    }
};
bool render_impl(const std::uint32_t* packed,int width,int height,int cfa,
                const float* metadata,std::uint8_t* rgba,std::size_t stride,
                const SerRenderShading* shading,const SerNaturalTone* tone) noexcept {
    if (!packed || !metadata || !rgba || width<4 || height<4 || (width&1) || (height&1) ||
        static_cast<std::int64_t>(width)*height>16000000 || cfa<0 || cfa>3 ||
        stride<static_cast<std::size_t>(width)*4) return false;
    for(int i=0;i<18;++i) if(!std::isfinite(metadata[i])) return false;
    if(metadata[4]<64 || metadata[4]>65535) return false;
    for(int i=0;i<4;++i) if(metadata[i]<0 || metadata[4]-metadata[i]<32 ||
        metadata[5+i]<=0 || metadata[5+i]>16) return false;
    for(int i=9;i<18;++i) if(std::abs(metadata[i])>16) return false;
    const auto* m=metadata+9;
    const float determinant=m[0]*(m[4]*m[8]-m[5]*m[7])-
        m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
    if(std::abs(determinant)<0.000001f) return false;
    if(shading) {
        const auto& s=*shading;
        if(!s.gains || s.columns<1 || s.columns>64 || s.rows<1 || s.rows>64 ||
           s.left<0 || s.top<0 || s.right>width || s.bottom>height ||
           s.right<=s.left || s.bottom<=s.top) return false;
        for(int i=0;i<s.columns*s.rows*4;++i)
            if(!std::isfinite(s.gains[i]) || s.gains[i]<1 || s.gains[i]>16) return false;
    }
    if(tone && (!std::isfinite(tone->exposureGain) || tone->exposureGain<1 ||
        tone->exposureGain>16 || !std::isfinite(tone->shadowStrength) ||
        tone->shadowStrength<0 || tone->shadowStrength>1 || !std::isfinite(tone->noiseFloor) ||
        tone->noiseFloor<0 || tone->noiseFloor>0.1f))return false;
    Render render{packed,width,height,cfa,metadata,shading,tone};
    for(int y=0;y<height;++y) for(int x=0;x<width;++x)
        render.pixel(x,y,rgba+static_cast<std::size_t>(y)*stride+static_cast<std::size_t>(x)*4);
    return true;
}
}
bool ser_render(const std::uint32_t* packed,int width,int height,int cfa,
                const float* metadata,std::uint8_t* rgba,std::size_t stride,
                const SerRenderShading* shading) noexcept {
    return render_impl(packed,width,height,cfa,metadata,rgba,stride,shading,nullptr);
}
bool ser_render_natural(const std::uint32_t* packed,int width,int height,int cfa,
                        const float* metadata,std::uint8_t* rgba,std::size_t stride,
                        const SerNaturalTone& tone,const SerRenderShading* shading) noexcept {
    return render_impl(packed,width,height,cfa,metadata,rgba,stride,shading,&tone);
}
}
