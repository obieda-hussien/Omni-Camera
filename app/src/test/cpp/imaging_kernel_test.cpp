#include "imaging_kernel.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <vector>

struct Fixture {
    int w, h, stride;
    std::vector<uint8_t> pixels;
    Fixture(int width, int height, int padding = 0): w(width), h(height), stride(width * 4 + padding), pixels(stride * height, 0) {}
    omni::Image image() { return {pixels.data(), w, h, stride}; }
    void gray(int x, int y, int value, int alpha = 255) {
        auto* p = pixels.data() + y * stride + x * 4;
        p[0] = p[1] = p[2] = value; p[3] = alpha;
    }
};
void require(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }
float variance(const Fixture& f) {
    double sum = 0, sq = 0; int count = 0;
    for (int y = 8; y < f.h - 8; ++y) for (int x = 8; x < f.w - 8; ++x) {
        int value = f.pixels[y * f.stride + x * 4]; sum += value; sq += value * value; count++;
    }
    return sq / count - (sum / count) * (sum / count);
}
void tests() {
    omni::Options options;
    Fixture original(72, 60, 16);
    std::mt19937 random(42);
    for (auto& p : original.pixels) p = random() % 256;
    auto copy = original.pixels;
    options.strength = 0;
    omni::develop(original.image(), {}, {}, options);
    require(copy == original.pixels, "Disabled processing must be bit-exact");

    Fixture neutral(80, 80, 12);
    for (int y = 0; y < 80; ++y) for (int x = 0; x < 80; ++x) neutral.gray(x, y, 127, 173);
    copy = neutral.pixels;
    options.strength = .7f;
    omni::develop(neutral.image(), {}, {}, options);
    for (int y = 0; y < 80; ++y) {
        for (int x = 0; x < 80; ++x) {
            auto* p = neutral.pixels.data() + y * neutral.stride + x * 4;
            require(std::abs(p[0] - 127) <= 1 && p[0] == p[1] && p[1] == p[2], "Neutral balance drift");
            require(p[3] == 173, "Alpha must survive");
        }
        for (int x = 80 * 4; x < neutral.stride; ++x) require(neutral.pixels[y * neutral.stride + x] == copy[y * neutral.stride + x], "Stride padding changed");
    }

    Fixture single(1, 1); single.gray(0, 0, 90);
    omni::develop(single.image(), {}, {}, options);
    require(single.pixels[3] == 255, "Tiny images must stay valid");

    Fixture portrait(128, 128);
    for (int y = 0; y < 128; ++y) for (int x = 0; x < 128; ++x)
        portrait.gray(x, y, x > 45 && x < 83 && y > 45 && y < 83 ? 55 : 190);
    Fixture noFace = portrait;
    options.faceLight = 1; options.subjectLuma = .2f; options.backgroundLuma = .8f;
    omni::develop(noFace.image(), {}, {}, options);
    omni::develop(portrait.image(), {}, {{.4f, .4f, .6f, .6f}}, options);
    require(portrait.pixels[64 * portrait.stride + 64 * 4] > noFace.pixels[64 * noFace.stride + 64 * 4] + 5, "Face lighting must be selective");
    require(std::abs(portrait.pixels[10 * portrait.stride + 10 * 4] - noFace.pixels[10 * noFace.stride + 10 * 4]) <= 1, "Face correction leaked into background");

    Fixture noisy(128, 128);
    for (int y = 0; y < 128; ++y) for (int x = 0; x < 128; ++x) noisy.gray(x, y, 85 + static_cast<int>(random() % 17) - 8);
    float before = variance(noisy);
    options = {}; options.denoise = 1; options.detail = 0; options.strength = 1;
    omni::develop(noisy.image(), {}, {}, options);
    require(variance(noisy) < before, "Flat-region noise should fall, not rise");

    Fixture deterministic(128, 96);
    for (int y = 0; y < 96; ++y) for (int x = 0; x < 128; ++x) deterministic.gray(x, y, (x * 3 + y * 7) % 256);
    auto threaded = deterministic;
    options.workers = 1; omni::develop(deterministic.image(), {}, {}, options);
    options.workers = 2; omni::develop(threaded.image(), {}, {}, options);
    require(threaded.pixels == deterministic.pixels, "Thread/chunk seams or read/write races");

    Fixture anchor(128, 96), candidate(128, 96);
    for (int y = 0; y < 96; ++y) for (int x = 0; x < 128; ++x) anchor.gray(x, y, (x / 9 + y / 13) % 2 ? 180 : 50);
    for (int y = 0; y < 96; ++y) for (int x = 0; x < 128; ++x)
        candidate.gray(x, y, anchor.pixels[y * anchor.stride + std::max(0, x - 3) * 4]);
    auto registration = omni::registerFrame(anchor.image(), candidate.image());
    require(registration.dx == 3 && std::abs(registration.dy) <= 1 && registration.error < .02f, "Multi-scale frame alignment failed");
    copy = anchor.pixels;
    require(!omni::fuse(anchor.image(), candidate.image(), 0, {0, 0, 1}), "Unreliable alignment must be rejected");
    require(copy == anchor.pixels, "Rejected frame changed base");

    Fixture highlight(80, 80), exposure(80, 80);
    for (int y = 0; y < 80; ++y) for (int x = 0; x < 80; ++x) { highlight.gray(x, y, 255); exposure.gray(x, y, 153); }
    require(omni::fuse(highlight.image(), exposure.image(), -1.5f, {0, 0, 0}), "Valid exposure rejected");
    require(highlight.pixels[0] < 230, "Fusion must use highlight information from another exposure");
    Fixture burstBase(96, 80, 8), frameA(96, 80), frameB(96, 80);
    for (int y = 0; y < 80; ++y) for (int x = 0; x < 96; ++x) {
        int value = 85 + (x / 9 + y / 11) % 2 * 60;
        burstBase.gray(x, y, value, 173);
        frameA.gray(x, y, value + 12);
        frameB.gray(x, y, value - 10);
    }
    auto forward = burstBase, reverse = burstBase;
    copy = burstBase.pixels;
    omni::Burst ab(forward.image()), ba(reverse.image());
    require(ab.add(frameA.image(), 0) && ab.add(frameB.image(), 0), "Valid night frames rejected");
    require(forward.pixels == copy, "A partially developed burst changed its immutable anchor");
    require(ba.add(frameB.image(), 0) && ba.add(frameA.image(), 0), "Reordered night frames rejected");
    ab.finish(forward.image()); ba.finish(reverse.image());
    require(forward.pixels == reverse.pixels, "Night result depends on frame order");
    for (int y = 0; y < 80; ++y) for (int x = 0; x < 96; ++x)
        require(forward.pixels[y * forward.stride + x * 4 + 3] == 173, "Burst lost anchor alpha");

    Fixture rejected = burstBase;
    omni::Burst invalid(rejected.image());
    require(!invalid.add(frameA.image(), 0, {0, 0, 1}), "Bad burst alignment accepted");
    invalid.finish(rejected.image());
    require(rejected.pixels == copy, "An entirely rejected burst must preserve the original exactly");

    Fixture noiseBase(96, 80), noiseA(96, 80), noiseB(96, 80);
    for (int y = 0; y < 80; ++y) for (int x = 0; x < 96; ++x) {
        noiseBase.gray(x, y, 100 + static_cast<int>(random() % 13) - 6);
        noiseA.gray(x, y, 100 + static_cast<int>(random() % 13) - 6);
        noiseB.gray(x, y, 100 + static_cast<int>(random() % 13) - 6);
    }
    const float burstVariance = variance(noiseBase);
    omni::Burst night(noiseBase.image());
    require(night.add(noiseA.image(), 0, {0, 0, 0}) && night.add(noiseB.image(), 0, {0, 0, 0}), "Noise frames rejected");
    night.finish(noiseBase.image());
    require(variance(noiseBase) < burstVariance * .6f, "Three-frame night fusion should reduce temporal noise");
    std::cout << "Native imaging: 12 regression groups passed\n";
}

void benchmark() {
    Fixture image(2000, 1500);
    std::mt19937 random(71);
    for (int y = 0; y < image.h; ++y) for (int x = 0; x < image.w; ++x) image.gray(x, y, 45 + random() % 170);
    volatile double checksum = 0;
    auto start = std::chrono::steady_clock::now();
    // Legacy hot path: nine range exponentials plus gamma and RGB transfer powers per pixel.
    for (int y = 1; y < image.h - 1; ++y) for (int x = 1; x < image.w - 1; ++x) {
        const int center = image.pixels[y * image.stride + x * 4]; double weight = 0;
        for (int yy = -1; yy <= 1; ++yy) for (int xx = -1; xx <= 1; ++xx) {
            double delta = (image.pixels[(y + yy) * image.stride + (x + xx) * 4] - center) / 255.;
            weight += std::exp(-delta * delta / .0025);
        }
        double tone = std::pow(center / 255., .82);
        checksum += weight + std::pow(tone, 1. / 2.4) + std::pow(tone * .95, 1. / 2.4) + std::pow(tone * .91, 1. / 2.4);
    }
    double legacy = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    omni::Options options;
    start = std::chrono::steady_clock::now();
    omni::develop(image.image(), {}, {}, options);
    double native = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
    std::cout << "3MP host benchmark: legacy scalar math=" << legacy << "ms, native guided pipeline=" << native
        << "ms, ratio=" << legacy / native << "x, checksum=" << checksum + image.pixels[0] << '\n';
    std::cout << "This is a CI-host kernel comparison, not a phone or end-to-end camera benchmark.\n";
}
int main() { try { tests(); benchmark(); } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; } }
