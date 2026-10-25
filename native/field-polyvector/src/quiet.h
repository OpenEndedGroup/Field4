// The reference prints progress with printf as well as std::cout. Force-included into its sources
// (not into callers), this turns those calls into nothing; field_polyvector.cpp silences std::cout.
#pragma once
#include <cstdio>
#define printf(...) ((void)0)
