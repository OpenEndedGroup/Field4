// The one thing the declarations in ken_ceres.h and the definitions in ken_ceres.cpp have to agree
// about across platforms: whether a symbol leaves the shared library.
//
// On ELF and Mach-O every non-static symbol is exported by default, so KEN_CERES_API is decoration
// there (it spells the default out, which also means adding -fvisibility=hidden later would narrow
// the export set to exactly this list rather than breaking it).
//
// On Windows nothing leaves a DLL unless it is asked to, and a DLL that exports nothing loads fine
// and then fails at the first SymbolLookup.find — so this macro is the difference between working
// and not. CMake's WINDOWS_EXPORT_ALL_SYMBOLS is the usual shortcut and is the wrong tool here: the
// redistributable build links a *static* Ceres into this library, and exporting all of Ceres'
// symbols alongside ours would approach the 65535-symbol limit of the PE export table for no
// benefit. Ten functions is the entire public surface; name them.
//
// ken_ceres.cpp deliberately does not include ken_ceres.h (it keeps its own copy of the opcode
// enums), so both files include this header and both spell KEN_CERES_API on every entry point.

#pragma once

#if defined(_WIN32)
#if defined(KEN_CERES_BUILDING_SHARED)
#define KEN_CERES_API __declspec(dllexport)
#else
#define KEN_CERES_API __declspec(dllimport)
#endif
#else
#define KEN_CERES_API __attribute__((visibility("default")))
#endif
