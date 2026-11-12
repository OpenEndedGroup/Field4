// CloudXR SDK process boundary: keep its bundled OpenSSL separate from Python/system OpenSSL.
// Public API declarations are supplied by the locally installed NVIDIA SDK, not vendored here.
#include <cxrServiceAPI.h>
#include <nlohmann/json.hpp>
#include <dlfcn.h>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
using json = nlohmann::json;
static std::string text(const std::string &path) {
    std::ifstream file(path);
    if (!file) throw std::runtime_error("Cannot read runtime identity file");
    return {std::istreambuf_iterator<char>(file), {}};
}
int main(int argc, char **argv) {
    if (argc != 3) return 2;
    const int reply_fd = std::stoi(argv[2]);
    auto lib = dlopen((std::string(argv[1]) + "/libcloudxr.so").c_str(), RTLD_NOW | RTLD_DEEPBIND);
    if (!lib) return 3;
#define API(name) auto name = reinterpret_cast<decltype(&nv_cxr_##name)>(dlsym(lib, "nv_cxr_" #name)); if (!name) return 4
    API(service_create); API(service_set_string_property); API(service_set_boolean_property);
    API(service_set_int64_property);
    API(service_start); API(service_stop); API(service_join); API(service_destroy);
    API(service_poll_event); API(update_client_token);
    nv_cxr_service *service = nullptr;
    bool started = false;
    auto check = [](nv_cxr_result_t result) { if (result != NV_CXR_SUCCESS) throw static_cast<int>(result); };
    auto property = [&](const std::string &key, const std::string &value) {
        check(service_set_string_property(service, key.data(), key.size(), value.data(), value.size()));
    };
    auto stop = [&]() {
        if (started) {
            check(service_stop(service)); check(service_join(service)); started = false;
        }
    };
    std::string line;
    while (std::getline(std::cin, line)) {
        json reply = {{"reply", true}, {"code", 0}};
        bool quit = false;
        try {
            const auto request = json::parse(line);
            const auto command = request.at("command").get<std::string>();
            if (command == "init") {
                if (service) throw -5;
                check(service_create(&service));
                property("device-profile", "auto-native");
                // Render in the client's full unwarped space, then let the runtime apply its warp.
                // Without this, the 1200x960 warped transport dimensions leak into xrEnumerateViewConfigurationViews.
                const std::string foveation = "runtime-foveation";
                check(service_set_boolean_property(service, foveation.data(), foveation.size(), true));
                // Apple secure native clients connect to the standard TLS signaling port.
                const std::string port_name = "server-port";
                check(service_set_int64_property(service, port_name.data(), port_name.size(), 48322));
                property("certificate-pem", text(request.at("certificate").get<std::string>()));
                property("key-pem", text(request.at("key").get<std::string>()));
                property("client-token", request.at("token").get<std::string>());
                const std::string name = "audio-streaming";
                check(service_set_boolean_property(service, name.data(), name.size(), false));
            } else if (command == "token") {
                const auto token = request.at("token").get<std::string>();
                if (started) check(update_client_token(service, token.data(), token.size()));
                else property("client-token", token);
            } else if (command == "start") {
                if (!started) { check(service_start(service)); started = true; }
            } else if (command == "stop") {
                stop();
            } else if (command == "event") {
                nv_cxr_event_t event{};
                if (started) check(service_poll_event(service, &event));
                reply["event"] = static_cast<int>(event.type);
            } else if (command == "close") {
                stop(); quit = true;
            } else throw -8;
        } catch (int code) { reply["code"] = code; }
          catch (...) { reply["code"] = -999; }
        const auto result = reply.dump() + "\n";
        if (dprintf(reply_fd, "%s", result.c_str()) < 0) break;
        if (quit) break;
    }
    try { stop(); } catch (...) {}
    if (service) service_destroy(service);
    dlclose(lib);
}
