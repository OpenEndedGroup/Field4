// Field Slug preparation prototype. Builds one immutable atlas per request.
#include <slughorn/canvas.hpp>
#include <slughorn/render.hpp>
#include <bit>
#include <cmath>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

using namespace slughorn;

static void check(bool b, const char* message) {
    if (!b) throw std::runtime_error(message);
}

static std::vector<uint32_t> pack(const Atlas& atlas, const Atlas::Shape& s) {
    const auto& c = atlas.getCurveTextureData();
    const auto& b = atlas.getBandTextureData();
    check(c.format == Atlas::TextureData::Format::RGBA32F, "expected RGBA32F curves");
    check(b.format == Atlas::TextureData::Format::RG16UI, "expected RG16UI bands");
    check(c.depth == 0 && b.depth == 0, "array atlases unsupported in prototype");
    check(c.bytes.size() % 4 == 0 && b.bytes.size() % 4 == 0, "invalid packing");
    check(std::endian::native == std::endian::little, "prototype requires little-endian host");
    const auto co = uint32_t(24);
    const auto bo = co + uint32_t(c.bytes.size() / 4);
    std::vector<uint32_t> words(bo + b.bytes.size() / 4, 0);
    words[0] = 0x534c5547; words[1] = 1;
    words[2] = c.width; words[3] = c.height;
    words[4] = b.width; words[5] = b.height;
    words[6] = co; words[7] = bo;
    auto f = [&](size_t i, float v) { words[i] = std::bit_cast<uint32_t>(v); };
    f(8, float(s.bearingX)); f(9, float(s.bearingY - s.height));
    f(10, float(s.bearingX + s.width)); f(11, float(s.bearingY));
    f(12, float(s.bandScaleX)); f(13, float(s.bandScaleY));
    f(14, float(s.bandOffsetX)); f(15, float(s.bandOffsetY));
    words[16] = s.bandTexX; words[17] = s.bandTexY;
    words[18] = s.bandMaxX; words[19] = s.bandMaxY;
    words[20] = uint32_t(s.curves.size());
    words[21] = uint32_t(c.bytes.size()); words[22] = uint32_t(b.bytes.size());
    std::memcpy(words.data() + co, c.bytes.data(), c.bytes.size());
    std::memcpy(words.data() + bo, b.bytes.data(), b.bytes.size());
    return words;
}

static void lifecycleTest() {
    Atlas atlas;
    canvas::Canvas canvas(atlas);
    canvas.setTolerance(TOLERANCE_FINE);
    canvas.rect(0, 0, 1, 1);
    canvas.fill(Color{1, 1, 1, 1}, 1, Key(1u));
    auto composite = canvas.finalize();
    atlas.build();
    auto original = atlas.getCurveTextureData().bytes;
    auto bands = atlas.getBandTextureData().bytes;
    composite[0].transform.x = 4;
    composite[0].color.a = 0.5f;
    atlas.addCompositeShape(Key(2u), composite);
    check(atlas.getCompositeShape(Key(2u)) != nullptr, "composite registration failed");
    check(atlas.getCompositeShape(Key(2u))->layers[0].transform.x == 4, "layer update failed");
    check(atlas.getCurveTextureData().bytes == original, "layer update changed curves");
    check(atlas.getBandTextureData().bytes == bands, "layer update changed bands");
    canvas.beginPath().circle(0, 0, 0.2f);
    canvas.fill(Color{1, 1, 1, 1}, 1, Key(3u));
    check(!atlas.getShape(Key(3u)).has_value(), "post-build geometry unexpectedly registered");
    auto sampler = render::decode(atlas, Key(1u));
    check(sampler.renderSample(0.5f, 0.5f, 100, 100).fill > 0.99f, "rectangle interior wrong");
    check(sampler.renderSample(2, 2, 100, 100).fill < 0.01f, "rectangle exterior wrong");
    std::cerr << "PASS Canvas: layers mutable, curve/band bytes unchanged, post-build geometry ignored\n";
}

int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::string(argv[1]) == "--self-test") {
            lifecycleTest(); return 0;
        }
        check(argc == 1, "usage: field-slug < commands > packed-data (or --self-test)");
        Atlas::Curves curves;
        CurveDecomposer dec(curves);
        dec.tolerance = TOLERANCE_FINE;
        std::vector<size_t> contourStarts;
        bool hasMove = false, open = false;
        char command;
        auto number = []() {
            float x;
            check(bool(std::cin >> x) && std::isfinite(x), "expected finite coordinate");
            return x;
        };
        while (std::cin >> command) {
            if (command == 'T') {
                check(!hasMove, "tolerance must precede geometry");
                dec.tolerance = number();
                check(dec.tolerance > 0, "tolerance must be positive");
                continue;
            }
            if (command == 'M') {
                if (open) dec.close();
                auto x = number(), y = number();
                contourStarts.push_back(curves.size());
                dec.moveTo(x, y); hasMove = true; open = true;
                continue;
            }
            check(hasMove, "path must start with M");
            if (command == 'L') {
                auto x = number(), y = number(); dec.lineTo(x, y); open = true;
            } else if (command == 'Q') {
                auto x1 = number(), y1 = number(), x = number(), y = number();
                dec.quadTo(x1, y1, x, y); open = true;
            } else if (command == 'C') {
                auto x1 = number(), y1 = number(), x2 = number(), y2 = number(), x = number(), y = number();
                dec.cubicTo(x1, y1, x2, y2, x, y); open = true;
            } else if (command == 'Z') {
                if (open) dec.close();
                open = false;
            } else throw std::runtime_error("unknown command");
        }
        check(std::cin.eof(), "malformed input");
        if (open) dec.close();
        check(!curves.empty(), "empty geometry");
        Atlas atlas;
        Atlas::ShapeInfo info;
        info.curves = std::move(curves);
        info.contourStarts = std::move(contourStarts);
        atlas.addShape(Key(1u), info);
        atlas.build();
        auto shape = atlas.getShape(Key(1u));
        check(shape.has_value() && shape->width > 0 && shape->height > 0, "degenerate shape");
        auto words = pack(atlas, *shape);
        std::cout.write(reinterpret_cast<const char*>(words.data()), std::streamsize(words.size() * 4));
        check(bool(std::cout), "output failed");
    } catch (const std::exception& e) {
        std::cerr << "field-slug: " << e.what() << '\n'; return 1;
    }
}
