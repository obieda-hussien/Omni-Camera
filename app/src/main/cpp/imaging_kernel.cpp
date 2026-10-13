#include "imaging_kernel.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <stdexcept>
#include <thread>

namespace omni {
namespace {
float clip(float v, float lo = 0.f, float hi = 1.f) { return std::isfinite(v) ? std::max(lo, std::min(hi, v)) : lo; }
void validate(Image i) {
    if (!i.rgba || i.width <= 0 || i.height <= 0 || i.width > 16384 || i.height > 16384 ||
        i.stride < i.width * 4) throw std::invalid_argument("Invalid RGBA image");
}
struct Tables {
    std::array<float, 256> linear{}, logLuma{}, fusionWeight{};
    std::array<uint8_t, 65536> srgb{};
    std::array<float, 1025> exposure{};
    Tables() {
        for (int n = 0; n < 256; ++n) {
            float x = n / 255.f;
            linear[n] = x <= .04045f ? x / 12.92f : std::pow((x + .055f) / 1.055f, 2.4f);
            logLuma[n] = (std::log2(std::max(linear[n], .004f)) + 8.f) / 8.f;
            fusionWeight[n] = std::exp(-std::pow((x - .5f) / .28f, 2.f)) + .025f;
        }
        for (int n = 0; n < 65536; ++n) {
            float x = n / 65535.f;
            float v = x <= .0031308f ? x * 12.92f : 1.055f * std::pow(x, 1.f / 2.4f) - .055f;
            srgb[n] = static_cast<uint8_t>(std::lround(clip(v) * 255));
        }
        for (int n = 0; n <= 1024; ++n) exposure[n] = std::exp2(-2.f + n / 256.f);
    }
};
const Tables& tables() { static const Tables t; return t; }
int luma(const uint8_t* p) { return (54 * p[0] + 183 * p[1] + 19 * p[2] + 128) >> 8; }
float linearLuma(const uint8_t* p) {
    const auto& t = tables(); return .2126f * t.linear[p[0]] + .7152f * t.linear[p[1]] + .0722f * t.linear[p[2]];
}
uint8_t encode(float x) { return tables().srgb[static_cast<int>(clip(x) * 65535.f + .5f)]; }

struct Field {
    int w, h;
    std::vector<float> v;
    Field(int width, int height): w(width), h(height), v(static_cast<size_t>(width) * height) {}
    float sample(float x, float y) const {
        x = clip(x, 0.f, static_cast<float>(w - 1)); y = clip(y, 0.f, static_cast<float>(h - 1));
        int x0 = static_cast<int>(x), y0 = static_cast<int>(y), x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
        float fx = x - x0, fy = y - y0;
        return (v[y0 * w + x0] * (1 - fx) + v[y0 * w + x1] * fx) * (1 - fy) +
            (v[y1 * w + x0] * (1 - fx) + v[y1 * w + x1] * fx) * fy;
    }
};
Field box(const Field& source, int radius) {
    Field out(source.w, source.h);
    const int pitch = source.w + 1;
    std::vector<double> integral(static_cast<size_t>(pitch) * (source.h + 1), 0);
    for (int y = 0; y < source.h; ++y) {
        double row = 0;
        for (int x = 0; x < source.w; ++x) {
            row += source.v[y * source.w + x];
            integral[(y + 1) * pitch + x + 1] = integral[y * pitch + x + 1] + row;
        }
    }
    for (int y = 0; y < source.h; ++y) for (int x = 0; x < source.w; ++x) {
        int x0 = std::max(0, x - radius), x1 = std::min(source.w, x + radius + 1);
        int y0 = std::max(0, y - radius), y1 = std::min(source.h, y + radius + 1);
        out.v[y * source.w + x] = static_cast<float>((integral[y1 * pitch + x1] - integral[y0 * pitch + x1] -
            integral[y1 * pitch + x0] + integral[y0 * pitch + x0]) / ((x1 - x0) * (y1 - y0)));
    }
    return out;
}
struct Guided { Field a, b; };
Guided guide(const Field& i, const Field& p, int radius, float epsilon) {
    Field ii(i.w, i.h), ip(i.w, i.h);
    for (size_t n = 0; n < i.v.size(); ++n) { ii.v[n] = i.v[n] * i.v[n]; ip.v[n] = i.v[n] * p.v[n]; }
    auto mi = box(i, radius), mp = box(p, radius), mii = box(ii, radius), mip = box(ip, radius);
    Field a(i.w, i.h), b(i.w, i.h);
    for (size_t n = 0; n < i.v.size(); ++n) {
        float variance = std::max(0.f, mii.v[n] - mi.v[n] * mi.v[n]);
        a.v[n] = (mip.v[n] - mi.v[n] * mp.v[n]) / (variance + epsilon);
        b.v[n] = mp.v[n] - a.v[n] * mi.v[n];
    }
    return {box(a, radius), box(b, radius)};
}
float maskAt(Mask m, float nx, float ny) {
    if (!m.data || m.width <= 0 || m.height <= 0) return 0;
    float x = clip(nx * m.width - .5f, 0.f, static_cast<float>(m.width - 1));
    float y = clip(ny * m.height - .5f, 0.f, static_cast<float>(m.height - 1));
    int x0 = static_cast<int>(x), y0 = static_cast<int>(y), x1 = std::min(x0 + 1, m.width - 1), y1 = std::min(y0 + 1, m.height - 1);
    float fx = x - x0, fy = y - y0;
    return clip((clip(m.data[y0 * m.width + x0]) * (1 - fx) + clip(m.data[y0 * m.width + x1]) * fx) * (1 - fy) +
        (clip(m.data[y1 * m.width + x0]) * (1 - fx) + clip(m.data[y1 * m.width + x1]) * fx) * fy);
}
Field thumbnail(Image image, int longest) {
    float scale = std::min(1.f, longest / static_cast<float>(std::max(image.width, image.height)));
    Field f(std::max(1, static_cast<int>(image.width * scale)), std::max(1, static_cast<int>(image.height * scale)));
    for (int y = 0; y < f.h; ++y) for (int x = 0; x < f.w; ++x) {
        int sx = std::min(image.width - 1, static_cast<int>((x + .5f) * image.width / f.w));
        int sy = std::min(image.height - 1, static_cast<int>((y + .5f) * image.height / f.h));
        f.v[y * f.w + x] = luma(image.rgba + sy * image.stride + sx * 4) / 255.f;
    }
    return f;
}
float noiseEstimate(Image image) {
    std::array<int, 64> histogram{}; int count = 0;
    for (int y = 2; y < image.height - 2; y += 4) for (int x = 2; x < image.width - 2; x += 4) {
        const auto* p = image.rgba + y * image.stride + x * 4;
        int left = luma(p - 4), right = luma(p + 4), top = luma(p - image.stride), bottom = luma(p + image.stride);
        if (std::abs(left - right) + std::abs(top - bottom) > 24) continue;
        int diff = std::abs(luma(p) - (left + right + top + bottom) / 4);
        histogram[std::min(diff, 63)]++; count++;
    }
    int acc = 0;
    for (int n = 0; n < 64; ++n) { acc += histogram[n]; if (acc >= std::max(1, count / 2)) return clip(n * 1.48f, 1.f, 14.f); }
    return 1.f;
}
}

Result develop(Image image, Mask mask, const std::vector<Face>& faces, Options o) {
    validate(image);
    o.strength = clip(o.strength); o.denoise = clip(o.denoise); o.detail = clip(o.detail);
    o.faceLight = clip(o.faceLight); o.background = clip(o.background, -1.f, 1.f); o.warmth = clip(o.warmth, -1.f, 1.f);
    if (o.strength <= 0) return {0, image.width * image.height};
    const auto& t = tables();
    auto luminance = thumbnail(image, 192);
    Field log(luminance.w, luminance.h), portrait(luminance.w, luminance.h), faceMap(luminance.w, luminance.h);
    std::vector<float> sorted;
    sorted.reserve(luminance.v.size());
    for (int y = 0; y < log.h; ++y) for (int x = 0; x < log.w; ++x) {
        size_t n = static_cast<size_t>(y) * log.w + x;
        log.v[n] = t.logLuma[static_cast<int>(clip(luminance.v[n]) * 255)]; sorted.push_back(log.v[n]);
        float nx = (x + .5f) / log.w, ny = (y + .5f) / log.h;
        portrait.v[n] = maskAt(mask, nx, ny);
        for (const auto& f : faces) {
            float rx = std::max((f.right - f.left) * .65f, .01f), ry = std::max((f.bottom - f.top) * .7f, .01f);
            float dx = (nx - (f.left + f.right) / 2) / rx, dy = (ny - (f.top + f.bottom) / 2) / ry;
            float weight = clip(1 - dx * dx - dy * dy);
            faceMap.v[n] = std::max(faceMap.v[n], weight * weight);
        }
    }
    std::nth_element(sorted.begin(), sorted.begin() + sorted.size() / 2, sorted.end());
    float median = sorted[sorted.size() / 2];
    std::vector<float> brightness = luminance.v;
    std::nth_element(brightness.begin(), brightness.begin() + brightness.size() / 2, brightness.end());
    const float medianBrightness = brightness[brightness.size() / 2];
    const float globalEv = medianBrightness < .32f ? clip(std::log2(.42f / std::max(.06f, medianBrightness)) * .55f, 0.f, 1.f) : 0.f;
    auto illumination = guide(log, log, 9, .018f);
    auto foreground = guide(log, portrait, 3, .004f);
    const float noise = noiseEstimate(image);
    std::array<float, 256> range{};
    float sigma = std::max(3.f, noise * 1.8f);
    for (int n = 0; n < 256; ++n) range[n] = std::exp(-n * n / (2 * sigma * sigma));
    // Immutable source prevents row/thread races and halo seams during in-place processing.
    std::vector<uint8_t> source(static_cast<size_t>(image.width) * image.height * 4);
    for (int y = 0; y < image.height; ++y) std::memcpy(source.data() + static_cast<size_t>(y) * image.width * 4,
        image.rgba + y * image.stride, static_cast<size_t>(image.width) * 4);
    float backlight = clip((o.backgroundLuma - o.subjectLuma - .08f) * 2.f);
    float faceEv = clip((.48f - o.subjectLuma) * 2.5f + backlight * .7f, 0.f, 1.5f) * o.faceLight;
    auto rows = [&](int begin, int end) {
        for (int y = begin; y < end; ++y) {
            float gy = (y + .5f) * log.h / image.height - .5f;
            for (int x = 0; x < image.width; ++x) {
                const uint8_t* p = source.data() + (static_cast<size_t>(y) * image.width + x) * 4;
                uint8_t* out = image.rgba + y * image.stride + x * 4;
                float gx = (x + .5f) * log.w / image.width - .5f;
                const int lum = luma(p);
                float guideValue = t.logLuma[lum];
                float base = illumination.a.sample(gx, gy) * guideValue + illumination.b.sample(gx, gy);
                float person = clip(foreground.a.sample(gx, gy) * guideValue + foreground.b.sample(gx, gy));
                float face = faceMap.sample(gx, gy);
                float region = std::max(face, person * .4f);
                float localEv = clip((median - base) * 8.f * .48f, -.55f, 1.1f);
                // Portrait exposure follows detected faces, while background adapts to local light.
                float ev = (globalEv + localEv * (1 - region * .4f) + faceEv * region + o.background * (1 - person) * .65f) * o.strength;
                ev = clip(ev, -2.f, 2.f);
                float gain = t.exposure[static_cast<int>((ev + 2.f) * 256.f + .5f)];
                float rgb[3] = {static_cast<float>(p[0]), static_cast<float>(p[1]), static_cast<float>(p[2])};
                if (o.denoise > 0 || o.detail > 0) {
                    float filtered[3] = {}; float total = 0;
                    for (int yy = std::max(0, y - 1); yy <= std::min(image.height - 1, y + 1); ++yy) {
                        for (int xx = std::max(0, x - 1); xx <= std::min(image.width - 1, x + 1); ++xx) {
                            const uint8_t* q = source.data() + (static_cast<size_t>(yy) * image.width + xx) * 4;
                            int chroma = std::max({std::abs(p[0] - q[0]), std::abs(p[1] - q[1]), std::abs(p[2] - q[2])}) / 3;
                            int delta = std::max(std::abs(lum - luma(q)), chroma);
                            float weight = range[delta] * (xx == x ? 2.f : 1.f) * (yy == y ? 2.f : 1.f);
                            for (int c = 0; c < 3; ++c) filtered[c] += q[c] * weight;
                            total += weight;
                        }
                    }
                    float denoise = o.denoise * o.strength * clip(noise / 8.f) * (1 - lum / 255.f * .65f) * (1 - face * .35f);
                    float sharpen = o.detail * o.strength * .32f * (1 - face * .45f);
                    for (int c = 0; c < 3; ++c) {
                        float residual = rgb[c] - filtered[c] / std::max(total, .001f);
                        rgb[c] = clip(rgb[c] - residual * denoise + clip(residual, -12.f, 12.f) * sharpen, 0.f, 255.f);
                    }
                }
                float r = t.linear[static_cast<int>(rgb[0] + .5f)], g = t.linear[static_cast<int>(rgb[1] + .5f)], b = t.linear[static_cast<int>(rgb[2] + .5f)];
                float linearY = .2126f * r + .7152f * g + .0722f * b;
                // A bounded rational shoulder raises shadows without clipping highlights.
                float mappedGain = gain / (1 + (gain - 1) * linearY);
                r *= mappedGain * (1 + o.warmth * o.strength * .07f);
                g *= mappedGain; b *= mappedGain * (1 - o.warmth * o.strength * .07f);
                float peak = std::max({r, g, b, 1.f}); r /= peak; g /= peak; b /= peak;
                out[0] = encode(r); out[1] = encode(g); out[2] = encode(b); out[3] = p[3];
            }
        }
    };
    const int workers = std::max(1, std::min({o.workers, 2, image.height}));
    if (workers == 2) {
        std::thread worker([&] { rows(0, image.height / 2); });
        rows(image.height / 2, image.height); worker.join();
    } else rows(0, image.height);
    return {noise, image.width * image.height};
}

Registration registerFrame(Image base, Image candidate) {
    validate(base); validate(candidate);
    if (base.width != candidate.width || base.height != candidate.height) return {0, 0, 1};
    int dx = 0, dy = 0; float best = 1;
    // Coarse-to-fine translation is estimated in thumbnails and mapped into image coordinates.
    for (int level : {96, 192, 384}) {
        auto a = thumbnail(base, level), b = thumbnail(candidate, level);
        const float sx = a.w / static_cast<float>(base.width), sy = a.h / static_cast<float>(base.height);
        int centerX = std::lround(dx * sx), centerY = std::lround(dy * sy), radius = level == 96 ? 5 : 2;
        int chosenX = centerX, chosenY = centerY; best = 1e9f;
        for (int oy = centerY - radius; oy <= centerY + radius; ++oy) for (int ox = centerX - radius; ox <= centerX + radius; ++ox) {
            double sum = 0; int count = 0;
            for (int y = 8; y < a.h - 8; y += 2) for (int x = 8; x < a.w - 8; x += 2) {
                int xx = x + ox, yy = y + oy;
                if (xx < 1 || yy < 1 || xx >= b.w - 1 || yy >= b.h - 1) continue;
                auto gradient = [](const Field& f, int x, int y) {
                    float left = f.v[y * f.w + x - 1], right = f.v[y * f.w + x + 1];
                    return (right - left) / (right + left + .1f);
                };
                sum += std::abs(gradient(a, x, y) - gradient(b, xx, yy)); count++;
            }
            float score = count > 16 ? static_cast<float>(sum / count) + (std::abs(ox) + std::abs(oy)) * .00001f : 1.f;
            if (score < best) { best = score; chosenX = ox; chosenY = oy; }
        }
        dx = std::lround(chosenX / sx); dy = std::lround(chosenY / sy);
    }
    return {dx, dy, best};
}

bool fuse(Image base, Image candidate, float stops, Registration offset) {
    validate(base); validate(candidate);
    if (base.width != candidate.width || base.height != candidate.height || !std::isfinite(offset.error) || offset.error > .14f) return false;
    const auto& t = tables(); const float normalization = std::exp2(clip(stops, -4.f, 4.f));
    for (int y = 0; y < base.height; ++y) for (int x = 0; x < base.width; ++x) {
        int xx = x + offset.dx, yy = y + offset.dy;
        if (xx < 0 || yy < 0 || xx >= candidate.width || yy >= candidate.height) continue;
        uint8_t* p = base.rgba + y * base.stride + x * 4;
        const uint8_t* q = candidate.rgba + yy * candidate.stride + xx * 4;
        float y0 = linearLuma(p), y1 = linearLuma(q) / normalization;
        const int lum = luma(p);
        float confidence = lum < 12 || lum > 242 ? 1.f : clip(1 - std::abs(y0 - y1) / (.10f + y0 * .32f));
        float baseWeight = t.fusionWeight[lum], newWeight = t.fusionWeight[luma(q)] * confidence * confidence;
        for (int c = 0; c < 3; ++c) p[c] = encode((t.linear[p[c]] * baseWeight + t.linear[q[c]] * newWeight) / (baseWeight + newWeight));
    }
    return true;
}
}
