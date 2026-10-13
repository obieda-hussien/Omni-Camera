#pragma once
#include <cstdint>
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
}
