#pragma once
// Minimal JSON value + parser/writer — just enough for field-encode's flat, known-shape
// control protocol (see WIRE_FORMAT.md). No external dependency, mirroring the Mac sender's
// choice to lean on Codable rather than a JSON library: our commands are small, flat objects
// (plus one array of ints-or-objects for `surfaces`), so a hand-rolled parser is a few dozen
// lines, not a library.
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace field_encode {

class JsonValue {
public:
    enum class Kind { Null, Bool, Number, String, Array, Object };

    JsonValue() : kind_(Kind::Null) {}
    static JsonValue makeBool(bool b) { JsonValue v; v.kind_ = Kind::Bool; v.bool_ = b; return v; }
    static JsonValue makeNumber(double d) { JsonValue v; v.kind_ = Kind::Number; v.num_ = d; return v; }
    static JsonValue makeString(std::string s) { JsonValue v; v.kind_ = Kind::String; v.str_ = std::move(s); return v; }
    static JsonValue makeArray() { JsonValue v; v.kind_ = Kind::Array; return v; }
    static JsonValue makeObject() { JsonValue v; v.kind_ = Kind::Object; return v; }

    Kind kind() const { return kind_; }
    bool isObject() const { return kind_ == Kind::Object; }
    bool isArray() const { return kind_ == Kind::Array; }
    bool isNumber() const { return kind_ == Kind::Number; }
    bool isString() const { return kind_ == Kind::String; }

    // --- object access ---
    bool has(const std::string& key) const { return kind_ == Kind::Object && obj_.count(key) > 0; }
    const JsonValue& at(const std::string& key) const { return obj_.at(key); }
    void set(const std::string& key, JsonValue v) { obj_[std::move(key)] = std::move(v); }

    std::string asString(const std::string& fallback = "") const {
        return kind_ == Kind::String ? str_ : fallback;
    }
    double asDouble(double fallback = 0) const { return kind_ == Kind::Number ? num_ : fallback; }
    long long asInt(long long fallback = 0) const { return kind_ == Kind::Number ? (long long)num_ : fallback; }
    bool asBool(bool fallback = false) const { return kind_ == Kind::Bool ? bool_ : fallback; }

    std::string getString(const std::string& key, const std::string& fallback = "") const {
        return has(key) ? obj_.at(key).asString(fallback) : fallback;
    }
    long long getInt(const std::string& key, long long fallback = 0) const {
        return has(key) ? obj_.at(key).asInt(fallback) : fallback;
    }
    bool getBool(const std::string& key, bool fallback = false) const {
        return has(key) ? obj_.at(key).asBool(fallback) : fallback;
    }

    // --- array access ---
    const std::vector<JsonValue>& items() const { return arr_; }
    void push(JsonValue v) { arr_.push_back(std::move(v)); }

    // --- serialize (only what we need for events: flat objects of string/int/bool/array<int>) ---
    std::string dump() const {
        std::ostringstream out;
        write(out);
        return out.str();
    }

    // --- parse a single JSON value from `text`; throws std::runtime_error on malformed input ---
    static JsonValue parse(const std::string& text) {
        size_t i = 0;
        JsonValue v = parseValue(text, i);
        skipWs(text, i);
        return v;
    }

private:
    Kind kind_;
    bool bool_ = false;
    double num_ = 0;
    std::string str_;
    std::vector<JsonValue> arr_;
    std::map<std::string, JsonValue> obj_;

    void write(std::ostream& out) const {
        switch (kind_) {
            case Kind::Null: out << "null"; break;
            case Kind::Bool: out << (bool_ ? "true" : "false"); break;
            case Kind::Number: {
                if (num_ == (long long)num_) out << (long long)num_;
                else out << num_;
                break;
            }
            case Kind::String: writeEscaped(out, str_); break;
            case Kind::Array: {
                out << '[';
                for (size_t i = 0; i < arr_.size(); i++) { if (i) out << ','; arr_[i].write(out); }
                out << ']';
                break;
            }
            case Kind::Object: {
                out << '{';
                bool first = true;
                for (const auto& [k, val] : obj_) {
                    if (!first) out << ','; first = false;
                    writeEscaped(out, k); out << ':'; val.write(out);
                }
                out << '}';
                break;
            }
        }
    }

    static void writeEscaped(std::ostream& out, const std::string& s) {
        out << '"';
        for (char c : s) {
            switch (c) {
                case '"': out << "\\\""; break;
                case '\\': out << "\\\\"; break;
                case '\n': out << "\\n"; break;
                default: out << c;
            }
        }
        out << '"';
    }

    static void skipWs(const std::string& t, size_t& i) {
        while (i < t.size() && (t[i] == ' ' || t[i] == '\t' || t[i] == '\n' || t[i] == '\r')) i++;
    }

    static JsonValue parseValue(const std::string& t, size_t& i) {
        skipWs(t, i);
        if (i >= t.size()) throw std::runtime_error("unexpected end of JSON");
        char c = t[i];
        if (c == '{') return parseObject(t, i);
        if (c == '[') return parseArray(t, i);
        if (c == '"') return JsonValue::makeString(parseString(t, i));
        if (c == 't' && t.compare(i, 4, "true") == 0) { i += 4; return JsonValue::makeBool(true); }
        if (c == 'f' && t.compare(i, 5, "false") == 0) { i += 5; return JsonValue::makeBool(false); }
        if (c == 'n' && t.compare(i, 4, "null") == 0) { i += 4; return JsonValue(); }
        return parseNumber(t, i);
    }

    static JsonValue parseObject(const std::string& t, size_t& i) {
        JsonValue v = JsonValue::makeObject();
        i++; // '{'
        skipWs(t, i);
        if (i < t.size() && t[i] == '}') { i++; return v; }
        while (true) {
            skipWs(t, i);
            std::string key = parseString(t, i);
            skipWs(t, i);
            if (i >= t.size() || t[i] != ':') throw std::runtime_error("expected ':' in object");
            i++;
            JsonValue val = parseValue(t, i);
            v.set(key, std::move(val));
            skipWs(t, i);
            if (i < t.size() && t[i] == ',') { i++; continue; }
            if (i < t.size() && t[i] == '}') { i++; break; }
            throw std::runtime_error("expected ',' or '}' in object");
        }
        return v;
    }

    static JsonValue parseArray(const std::string& t, size_t& i) {
        JsonValue v = JsonValue::makeArray();
        i++; // '['
        skipWs(t, i);
        if (i < t.size() && t[i] == ']') { i++; return v; }
        while (true) {
            JsonValue val = parseValue(t, i);
            v.push(std::move(val));
            skipWs(t, i);
            if (i < t.size() && t[i] == ',') { i++; continue; }
            if (i < t.size() && t[i] == ']') { i++; break; }
            throw std::runtime_error("expected ',' or ']' in array");
        }
        return v;
    }

    static std::string parseString(const std::string& t, size_t& i) {
        if (i >= t.size() || t[i] != '"') throw std::runtime_error("expected string");
        i++;
        std::string out;
        while (i < t.size() && t[i] != '"') {
            char c = t[i++];
            if (c == '\\' && i < t.size()) {
                char e = t[i++];
                switch (e) {
                    case 'n': out += '\n'; break;
                    case 't': out += '\t'; break;
                    case 'r': out += '\r'; break;
                    case '"': out += '"'; break;
                    case '\\': out += '\\'; break;
                    case '/': out += '/'; break;
                    case 'u': i += 4; out += '?'; break; // not needed for our protocol
                    default: out += e;
                }
            } else {
                out += c;
            }
        }
        if (i >= t.size()) throw std::runtime_error("unterminated string");
        i++; // closing quote
        return out;
    }

    static JsonValue parseNumber(const std::string& t, size_t& i) {
        size_t start = i;
        if (i < t.size() && (t[i] == '-' || t[i] == '+')) i++;
        while (i < t.size() && (isdigit((unsigned char)t[i]) || t[i] == '.' || t[i] == 'e' || t[i] == 'E' || t[i] == '+' || t[i] == '-')) i++;
        if (i == start) throw std::runtime_error("expected number");
        return JsonValue::makeNumber(std::stod(t.substr(start, i - start)));
    }
};

} // namespace field_encode
