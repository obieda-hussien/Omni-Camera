#pragma once
#include <cstdint>
#include <array>
#include <vector>

namespace omni {
struct Image {
    uint8_t* rgba;
    int width, height, stride;
};
struct Mask {
    const float* data = nullptr;
    int width = 0, height = 0;
};
struct Face { float left, top, right, bottom; };
struct Options {
    float strength = .7f, faceLight = .65f, background = 0.f;
    float denoise = .35f, detail = .25f, warmth = 0.f;
    float subjectLuma = .5f, backgroundLuma = .5f;
    int workers = 2;
};
struct Result { float noise; int pixels; };
struct Registration { int dx, dy; float error; };
Result develop(Image image, Mask mask, const std::vector<Face>& faces, Options options);
Registration registerFrame(Image base, Image candidate);
bool fuse(Image base, Image candidate, float stops, Registration alignment);
// Each candidate is registered and motion-tested against the immutable capture anchor.
// Linear colour sums and total weights are retained until the whole burst is complete.
class Burst {
    int width_, height_, accepted_ = 0;
    std::vector<uint8_t> anchor_;
    std::vector<std::array<float, 4>> sums_;
    Image anchorImage();
public:
    explicit Burst(Image anchor);
    bool add(Image candidate, float stops);
    bool add(Image candidate, float stops, Registration alignment);
    void finish(Image destination);
};
}
