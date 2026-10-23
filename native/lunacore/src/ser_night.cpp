#include "lunacore/ser_night.h"
#include <new>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>

namespace lunacore {
namespace {

constexpr int kSearch = 8;  // Bayer cells: shifts always preserve the CFA phase.

struct WorkingFrame : RawFrame {
    std::array<double, 4> scale{};
    std::array<double, 4> noise_slope{};
    std::array<double, 4> noise_intercept{};
};

double signal(const WorkingFrame& f, int p, int c) {
    return (f.pixels[p] - f.metadata.black[c]) * f.scale[c];
}


bool usable(const RawFrame& f, int p, int c) {
    const auto& m = f.metadata;
    const double margin = std::max(4.0, (m.white - m.black[c]) * 0.005);
    return f.pixels[p] > m.black[c] + 1 && f.pixels[p] < m.white - margin;
}

struct Guide {
    const float* values;
    int width;
    int height;
};

// Sample small boxes at two spatial scales. Missing/saturated boxes cannot vote.
double box(const Guide& g, int x, int y, int radius) {
    double sum = 0;
    for (int j = -radius; j <= radius; ++j) {
        for (int i = -radius; i <= radius; ++i) {
            const float v = g.values[(y + j) * g.width + x + i];
            if (!std::isfinite(v)) return std::numeric_limits<double>::quiet_NaN();
            sum += v;
        }
    }
    const int side = radius * 2 + 1;
    return sum / (side * side);
}

double cost(const Guide& a, const Guide& b, int dx, int dy, int radius) {
    const int border = kSearch + 3;
    const int step = std::max(2, std::min(a.width, a.height) / 28);
    double sum = 0;
    int count = 0;
    for (int y = border; y < a.height - border; y += step) {
        for (int x = border; x < a.width - border; x += step) {
            const double va = box(a, x, y, radius);
            const double vb = box(b, x + dx, y + dy, radius);
            if (!std::isfinite(va) || !std::isfinite(vb)) continue;
            const double d = va - vb;
            // Robust loss limits local movers' influence on global alignment.
            sum += std::min(d * d, 0.0025);
            ++count;
        }
    }
    return count >= 32 ? sum / count : std::numeric_limits<double>::infinity();
}

struct Alignment {
    int x = 0;
    int y = 0;
    bool valid = false;
};

Alignment align(const Guide& a, const Guide& b) {
    Alignment best;
    double best_cost = std::numeric_limits<double>::infinity();
    std::array<double, 81> coarse_costs{};
    int coarse_index = 0;
    // Coarse box-smoothed exhaustive translations, then full-resolution guide.
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            const double c = cost(a, b, dx, dy, 1);
            coarse_costs[coarse_index++] = c;
            if (c < best_cost) { best_cost = c; best.x = dx; best.y = dy; }
        }
    }
    const int coarse_x = best.x;
    const int coarse_y = best.y;
    double distant_cost = std::numeric_limits<double>::infinity();
    coarse_index = 0;
    for (int dy = -kSearch; dy <= kSearch; dy += 2) {
        for (int dx = -kSearch; dx <= kSearch; dx += 2) {
            if (std::abs(dx - coarse_x) > 2 || std::abs(dy - coarse_y) > 2)
                distant_cost = std::min(distant_cost, coarse_costs[coarse_index]);
            ++coarse_index;
        }
    }
    const bool unique_coarse = distant_cost > best_cost * 1.1 + 1e-8;
    best_cost = std::numeric_limits<double>::infinity();
    double second = best_cost;
    for (int dy = std::max(-kSearch, coarse_y - 2);
         dy <= std::min(kSearch, coarse_y + 2); ++dy) {
        for (int dx = std::max(-kSearch, coarse_x - 2);
             dx <= std::min(kSearch, coarse_x + 2); ++dx) {
            const double c = cost(a, b, dx, dy, 0);
            if (c < best_cost) {
                second = best_cost; best_cost = c; best.x = dx; best.y = dy;
            } else if (c < second) { second = c; }
        }
    }
    // Ambiguous flat/repeated patterns and search-boundary optima fall back.
    best.valid = unique_coarse && std::isfinite(best_cost) && best_cost < 0.0004 &&
                 second > best_cost * 1.15 + 1e-8 &&
                 std::abs(best.x) < kSearch && std::abs(best.y) < kSearch;
    return best;
}

bool valid_input(const std::array<RawFrame, 3>& frames, int w, int h,
                 const std::array<int, 4>& area) {
    if (w < 64 || h < 64 || w % 2 || h % 2 || static_cast<std::uint64_t>(w)*h > 16000000 ||
        area[0] < 0 || area[1] < 0 || area[2] > w || area[3] > h ||
        area[2]-area[0] < 64 || area[3]-area[1] < 64) return false;
    for (const auto& f : frames) {
        const auto& m = f.metadata;
        if (!f.pixels || !std::isfinite(m.white) || m.white < 64 || m.white > 65535 ||
            !std::isfinite(m.exposure) || m.exposure < .5 || m.exposure > 2) return false;
        for (double b : m.black) if (!std::isfinite(b) || b < 0 || m.white-b < 32) return false;
    }
    return true;
}

bool noise_valid(const RawMetadata& m) {
    for (int c=0;c<4;++c) {
        if (!std::isfinite(m.slope[c]) || !std::isfinite(m.intercept[c]) ||
            m.slope[c] < 0 || m.slope[c] > .1 || m.intercept[c] < 0 || m.intercept[c] > .1 ||
            (m.slope[c] == 0 && m.intercept[c] == 0)) return false;
    }
    return true;
}

std::array<WorkingFrame,3> working(const std::array<RawFrame,3>& input) {
    std::array<WorkingFrame,3> result{};
    for (int i=0;i<3;++i) {
        result[i].pixels=input[i].pixels; result[i].metadata=input[i].metadata;
        for (int c=0;c<4;++c) {
            const auto& m=input[i].metadata;
            result[i].scale[c]=1/((m.white-m.black[c])*m.exposure);
            result[i].noise_slope[c]=m.slope[c]/m.exposure;
            result[i].noise_intercept[c]=m.intercept[c]/(m.exposure*m.exposure);
        }
    }
    return result;
}

// Float operations intentionally match the GLES highp policy; alignment stays double.
float fs(const RawFrame& f,int p,int c) {
    return (static_cast<float>(f.pixels[p])-static_cast<float>(f.metadata.black[c])) /
        (static_cast<float>(f.metadata.white)-static_cast<float>(f.metadata.black[c])) /
        static_cast<float>(f.metadata.exposure);
}
float fv(const RawFrame& f,float s,int c) {
    const float range=static_cast<float>(f.metadata.white)-static_cast<float>(f.metadata.black[c]);
    const float e=static_cast<float>(f.metadata.exposure);
    return std::max(static_cast<float>(f.metadata.slope[c])*std::max(s*e,0.0F)+
                    static_cast<float>(f.metadata.intercept[c]),1.0F/(range*range))/(e*e);
}
bool local_match(const RawFrame& a,const RawFrame& b,int p,int q,int w) {
    const std::array<int,5> offsets{0,-2,2,-2*w,2*w};
    for (int c=0;c<4;++c) {
        const int cp=(c/2)*w+c%2;
        float signed_sum=0,variance_sum=0;
        for (int o:offsets) {
            if (!usable(a,p+cp+o,c)||!usable(b,q+cp+o,c)) return false;
            const float x=fs(a,p+cp+o,c),y=fs(b,q+cp+o,c);
            const float v=fv(a,x,c)+fv(b,y,c),d=x-y;
            if (d*d>=16.0F*v) return false;
            signed_sum+=d; variance_sum+=v;
        }
        if (signed_sum*signed_sum>9.0F*variance_sum) return false;
        for(int radius:{2,4}) for(int stride:{1,w}) {
            const int o=radius*stride;
            if (!usable(a,p+cp-o,c)||!usable(a,p+cp+o,c)||
                !usable(b,q+cp-o,c)||!usable(b,q+cp+o,c)) return false;
            const float a0=fs(a,p+cp-o,c),a1=fs(a,p+cp+o,c);
            const float b0=fs(b,q+cp-o,c),b1=fs(b,q+cp+o,c);
            const float va=fv(a,a0,c)+fv(a,a1,c),vb=fv(b,b0,c)+fv(b,b1,c);
            const float ga=a1-a0,gb=b1-b0,error=ga-gb;
            if (ga*ga>16.0F*va && gb*gb>16.0F*vb && ga*gb<0) return false;
            if (error*error>16.0F*(va+vb)) return false;
        }
    }
    return true;
}

bool inside(int x,int y,const std::array<int,4>& a) {
    return x>=a[0]+4 && y>=a[1]+4 && x+5<a[2] && y+5<a[3];
}

void estimate_tiles(const std::array<RawFrame,3>& frames,int w,int h,SerNightPlan& plan) {
    for(int ty=0;ty<plan.tile_rows;++ty) for(int tx=0;tx<plan.tile_columns;++tx) {
        for(int source_parity=0;source_parity<2;++source_parity) {
            double sums[3]{},squares[3]{},cross[3][3]{},cross2[3][3]{};
            double diff[3]{}; int count=0;
            for(int y=ty*64+4;y<std::min((ty+1)*64-4,h-5);y+=4) {
                for(int x=tx*64+4+source_parity*2;x<std::min((tx+1)*64-4,w-5);x+=4) {
                    if (!inside(x,y,plan.active_area)) continue;
                    int positions[3]{}; bool good=true;
                    for(int f=0;f<3;++f) if(plan.accepted_mask&(1<<f)) {
                        const int qx=x+plan.shifts[2*f],qy=y+plan.shifts[2*f+1];
                        if(!inside(qx,qy,plan.active_area)){good=false;break;}
                        positions[f]=qy*w+qx;
                    }
                    if(!good)continue;
                    for(int c=0;c<4;++c) {
                        const int cp=(c/2)*w+c%2;
                        double residual[3]{},signals[3]{},variances[3]{};
                        good=true;
                        for(int f=0;f<3;++f) if(plan.accepted_mask&(1<<f)) {
                            const int p=positions[f]+cp;
                            if(!usable(frames[f],p,c)||!usable(frames[f],p-4,c)||!usable(frames[f],p+4,c)) {
                                good=false;break;
                            }
                            const float s=fs(frames[f],p,c),v=fv(frames[f],s,c);
                            signals[f]=s;variances[f]=v;
                            residual[f]=(s-.5*(fs(frames[f],p-4,c)+fs(frames[f],p+4,c)))/std::sqrt(1.5*v);
                        }
                        if(!good)continue;
                        ++count;
                        for(int f=0;f<3;++f) if(plan.accepted_mask&(1<<f)) {
                            sums[f]+=residual[f];squares[f]+=residual[f]*residual[f];
                            diff[f]+=(signals[f]-signals[plan.baseline])/
                                std::sqrt(variances[f]+variances[plan.baseline]);
                            for(int j=f+1;j<3;++j)if(plan.accepted_mask&(1<<j)) {
                                const double p=residual[f]*residual[j];cross[f][j]+=p;cross2[f][j]+=p*p;
                            }
                        }
                    }
                }
            }
            const int target_parity=1-source_parity;
            const int index=(ty*plan.tile_columns+tx)*4+target_parity*2;
            if(count<64)continue;
            double rho=.05,model=.0025; bool plausible=true;
            for(int f=0;f<3;++f)if(plan.accepted_mask&(1<<f)) {
                const double actual=squares[f]/count-(sums[f]/count)*(sums[f]/count);
                // If observed energy disagrees with the sensor model, do not spend reserve.
                if(actual<.35 || actual>1.8)plausible=false;
                const double bias=std::abs(diff[f]/count)+2/std::sqrt(static_cast<double>(count));
                model=std::max(model,bias*bias*.25);
                for(int j=f+1;j<3;++j)if(plan.accepted_mask&(1<<j)) {
                    const double mean=cross[f][j]/count;
                    const double covariance=mean-(sums[f]/count)*(sums[j]/count);
                    const double uncertainty=3*std::sqrt(std::max(0.0,cross2[f][j]/count-mean*mean)/count);
                    rho=std::max(rho,covariance+uncertainty);
                }
            }
            if(plausible && std::isfinite(rho) && std::isfinite(model)) {
                plan.tile_bounds[index]=static_cast<float>(std::clamp(rho,0.0,1.0));
                plan.tile_bounds[index+1]=static_cast<float>(model);
            }
        }
    }
}

} // namespace

bool prepare_ser_night(const std::array<RawFrame,3>& input,int width,int height,
                       std::array<int,4> area,SerNightPlan& output) {
    if(!valid_input(input,width,height,area))return false;
    try {
        SerNightPlan plan;plan.active_area=area;
        plan.tile_columns=(width+63)/64;plan.tile_rows=(height+63)/64;
        plan.tile_bounds.assign(static_cast<std::size_t>(plan.tile_columns)*plan.tile_rows*4,1.0F);
        if(input[0].pixels==input[1].pixels && input[0].pixels==input[2].pixels) {
            output=std::move(plan);return true;
        }
        if(!noise_valid(input[0].metadata)||!noise_valid(input[1].metadata)||!noise_valid(input[2].metadata)) {
            output=std::move(plan);return true;
        }
        const auto frames=working(input);
        const int gw=width/2,gh=height/2;
        const auto cells=static_cast<std::size_t>(gw)*gh;
        std::vector<float> memory(cells*3);
        std::array<Guide,3> guides{};std::array<double,3> scores{};
        for(int f=0;f<3;++f) {
            float* g=memory.data()+cells*f;guides[f]={g,gw,gh};std::uint64_t valid=0;
            for(int y=0;y<gh;++y)for(int x=0;x<gw;++x) {
                const int p=2*y*width+2*x;double sum=0;
                bool good=2*x>=area[0]&&2*y>=area[1]&&2*x+1<area[2]&&2*y+1<area[3];
                for(int c=0;c<4;++c){const int pos=p+(c/2)*width+c%2;
                    good=good&&usable(frames[f],pos,c);sum+=signal(frames[f],pos,c);}
                g[y*gw+x]=good?static_cast<float>(sum/4):std::numeric_limits<float>::quiet_NaN();
                if(good)++valid;
            }
            double sharp=0;int count=0;
            for(int y=2;y<gh-2;y+=4)for(int x=2;x<gw-2;x+=4) {
                const double a=box(guides[f],x-1,y,1),b=box(guides[f],x+1,y,1);
                if(std::isfinite(a)&&std::isfinite(b)){sharp+=std::min(std::abs(a-b),.1);++count;}
            }
            scores[f]=(count?sharp/count:0)*static_cast<double>(valid)/cells;
        }
        for(int f=1;f<3;++f)if(scores[f]>scores[plan.baseline]*1.1+1e-6)plan.baseline=f;
        plan.accepted_mask=1<<plan.baseline;
        for(int f=0;f<3;++f)if(f!=plan.baseline) {
            bool duplicate=false;
            for(int j=0;j<3;++j)if(plan.accepted_mask&(1<<j)) {
                if(input[f].pixels==input[j].pixels||std::memcmp(input[f].pixels,input[j].pixels,
                    static_cast<std::size_t>(width)*height*2)==0)duplicate=true;
            }
            if(duplicate)continue;
            const auto a=align(guides[plan.baseline],guides[f]),r=align(guides[f],guides[plan.baseline]);
            if(a.valid&&r.valid&&a.x+r.x==0&&a.y+r.y==0) {
                plan.accepted_mask|=1<<f;plan.shifts[2*f]=a.x*2;plan.shifts[2*f+1]=a.y*2;
            }
        }
        if(plan.accepted_mask!=(1<<plan.baseline))estimate_tiles(input,width,height,plan);
        output=std::move(plan);return true;
    } catch(const std::bad_alloc&) {return false;}
}

bool fuse_ser_night(const std::array<RawFrame,3>& frames,int w,int h,const SerNightPlan& plan,
                    std::uint32_t* output,SerNightStats& stats,bool enabled) {
    if(!output||!valid_input(frames,w,h,plan.active_area)||plan.baseline<0||plan.baseline>2||
        plan.accepted_mask<1||plan.accepted_mask>7||!(plan.accepted_mask&(1<<plan.baseline))||
        plan.tile_size!=64||plan.tile_columns!=(w+63)/64||plan.tile_rows!=(h+63)/64||
        plan.tile_bounds.size()!=static_cast<std::size_t>(plan.tile_columns)*plan.tile_rows*4)return false;
    if(plan.shifts[2*plan.baseline]!=0||plan.shifts[2*plan.baseline+1]!=0)return false;
    for(int f=0;f<3;++f) {
        if(reinterpret_cast<const void*>(output)==frames[f].pixels)return false;
        if(std::abs(plan.shifts[2*f])>16||std::abs(plan.shifts[2*f+1])>16||
            plan.shifts[2*f]%2||plan.shifts[2*f+1]%2)return false;
    }
    const auto& base=frames[plan.baseline];stats={};
    const int size=w*h,cw=w/2,ch=h/2;
    for(int p=0;p<size;++p)output[p]=base.pixels[p];
    if(!enabled||plan.accepted_mask==(1<<plan.baseline))return true;
    for(const auto& f:frames)if(!noise_valid(f.metadata))return true;
    try {
        std::vector<std::uint16_t> ev(static_cast<std::size_t>(cw)*ch);
        for(int y=0;y<h;y+=2)for(int x=0;x<w;x+=2) {
            if(!inside(x,y,plan.active_area))continue;
            const int p=y*w+x;
            int pos[3]{},n=0;bool valid=true;
            for(int f=0;f<3;++f)if(plan.accepted_mask&(1<<f)) {
                const int qx=x+plan.shifts[2*f],qy=y+plan.shifts[2*f+1];
                if(!inside(qx,qy,plan.active_area)){valid=false;break;}
                pos[f]=qy*w+qx;
                if(f!=plan.baseline) {
                    ++n;if(!local_match(base,frames[f],p,pos[f],w)){valid=false;break;}
                }
            }
            if(!valid||n==0)continue;
            const int bi=((y/64)*plan.tile_columns+x/64)*4+((x/2+y/2)&1)*2;
            const float rho=plan.tile_bounds[bi],model=plan.tile_bounds[bi+1];
            if(!std::isfinite(rho)||!std::isfinite(model)||rho<0||rho>1||model<0)continue;
            float shared_ev=.35F;std::uint16_t values[4]{};
            for(int c=0;c<4;++c) {
                const int cp=(c/2)*w+c%2;
                float s[3]{},v[3]{},a[3]{};
                for(int f=0;f<3;++f)if(plan.accepted_mask&(1<<f)) {
                    s[f]=fs(frames[f],pos[f]+cp,c);v[f]=fv(frames[f],s[f],c);
                    a[f]=f==plan.baseline?(n==2?.5F:.625F):(n==2?.25F:.375F);
                }
                float variance=0,z=0;
                for(int f=0;f<3;++f){variance+=a[f]*a[f]*v[f];z+=a[f]*s[f];}
                for(int f=0;f<3;++f)for(int j=f+1;j<3;++j)
                    variance+=2.0F*rho*a[f]*a[j]*std::sqrt(v[f]*v[j]);
                const float vb=v[plan.baseline],sb=s[plan.baseline];
                const float rms=std::sqrt(variance)+std::sqrt(model*vb),mse=rms*rms;
                if(!(mse>0&&mse<vb)||sb>=.20F||sb-2.0F*std::sqrt(vb)<4.0F*rms){valid=false;break;}
                const float gain=std::min(.35F,.5F*std::log2(vb/mse))*std::clamp((.20F-sb)/.10F,0.0F,1.0F);
                shared_ev=std::min(shared_ev,gain);
                const float raw=z*static_cast<float>(base.metadata.exposure)*
                    (static_cast<float>(base.metadata.white)-static_cast<float>(base.metadata.black[c]))+
                    static_cast<float>(base.metadata.black[c]);
                values[c]=static_cast<std::uint16_t>(std::clamp(std::floor(raw+.5F),0.0F,65535.0F));
            }
            if(!valid)continue;
            const auto quant=static_cast<std::uint16_t>(std::floor(shared_ev/.35F*65535.0F));
            ev[(y/2)*cw+x/2]=quant;
            if(quant)for(int c=0;c<4;++c)output[p+(c/2)*w+c%2]=values[c];
        }
        for(int y=0;y<ch;++y)for(int x=0;x<cw;++x) {
            std::uint16_t minimum=0;
            if(x>0&&y>0&&x+1<cw&&y+1<ch) {
                minimum=65535;
                for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx)
                    minimum=std::min(minimum,ev[(y+dy)*cw+x+dx]);
            }
            if(minimum)++stats.lifted_cells;
            for(int c=0;c<4;++c) {
                const int p=(2*y+c/2)*w+2*x+c%2;
                const std::uint32_t dn=minimum?output[p]:base.pixels[p];
                output[p]=dn|(static_cast<std::uint32_t>(minimum)<<16);
                if(dn!=base.pixels[p])++stats.changed;
            }
        }
        return true;
    }catch(const std::bad_alloc&){return false;}
}
} // namespace lunacore

