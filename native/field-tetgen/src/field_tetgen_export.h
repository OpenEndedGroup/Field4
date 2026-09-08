// Whether a symbol leaves the shared library, spelled the same way ken-ceres spells it.
//
// ELF and Mach-O export every non-static symbol by default, so this is decoration there. Windows
// exports nothing unless asked, and a DLL that exports nothing loads fine and then fails at the
// first SymbolLookup.find, so on that platform the macro is the difference between working and not.

#pragma once

#if defined(_WIN32)
#if defined(FIELD_TETGEN_BUILDING_SHARED)
#define FIELD_TETGEN_API __declspec(dllexport)
#else
#define FIELD_TETGEN_API __declspec(dllimport)
#endif
#else
#define FIELD_TETGEN_API __attribute__((visibility("default")))
#endif
