// field-encode-linux — the Linux (NVENC) sender co-process. Reads newline-delimited JSON
// commands on stdin, streams fragmented HEVC/H.264 over UDP, emits JSON events on stdout.
// See ../field-encode/WIRE_FORMAT.md for the shared protocol and its Linux fd-handoff addendum.
// Mirrors native/field-encode/Sources/field-encode/main.swift's shape exactly (same CLI flags,
// same stdin loop, same stderr log prefix) so a JVM ProcessBuilder driver needs no branching
// beyond which binary path it launches.
#include "Engine.hpp"
#include "Json.hpp"

#include <iostream>
#include <string>
#include <vector>

using namespace field_encode;

namespace {

std::string argOr(const std::vector<std::string>& argv, const std::string& flag, const std::string& fallback) {
    for (size_t i = 0; i + 1 < argv.size(); i++) if (argv[i] == flag) return argv[i + 1];
    return fallback;
}

} // namespace

int main(int argc, char** argv_raw) {
    std::vector<std::string> argv(argv_raw, argv_raw + argc);
    Engine engine;

    bool selftest = false;
    for (auto& a : argv) if (a == "--selftest") selftest = true;

    if (selftest) {
        JsonValue cmd = JsonValue::makeObject();
        cmd.set("cmd", JsonValue::makeString("selftest"));
        cmd.set("width", JsonValue::makeNumber(std::stod(argOr(argv, "--w", "1280"))));
        cmd.set("height", JsonValue::makeNumber(std::stod(argOr(argv, "--h", "720"))));
        cmd.set("fps", JsonValue::makeNumber(std::stod(argOr(argv, "--fps", "60"))));
        cmd.set("bitrateKbps", JsonValue::makeNumber(std::stod(argOr(argv, "--kbps", "20000"))));
        cmd.set("dest", JsonValue::makeString(argOr(argv, "--dest", "127.0.0.1:9000")));
        cmd.set("codec", JsonValue::makeString(argOr(argv, "--codec", "hevc")));
        cmd.set("frames", JsonValue::makeNumber(std::stod(argOr(argv, "--frames", "120"))));
        cmd.set("idrIntervalMs", JsonValue::makeNumber(1000));
        log("selftest -> " + cmd.getString("dest") + " (" + std::to_string(cmd.getInt("width")) + "x" +
            std::to_string(cmd.getInt("height")) + " @" + std::to_string(cmd.getInt("fps")) + ", " +
            std::to_string(cmd.getInt("frames")) + " frames)");
        engine.handle(cmd);
        return 0;
    }

    log("ready (stdin JSON line protocol)");
    std::string line;
    while (std::getline(std::cin, line)) {
        size_t start = line.find_first_not_of(" \t\r");
        if (start == std::string::npos) continue;
        std::string trimmed = line.substr(start);
        try {
            JsonValue cmd = JsonValue::parse(trimmed);
            engine.handle(cmd);
        } catch (const std::exception& e) {
            log(std::string("parse error: ") + e.what() + " — line: " + trimmed);
        }
    }
    log("stdin closed, exiting");
    return 0;
}
