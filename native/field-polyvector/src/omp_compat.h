// Serial stand-ins for the OpenMP runtime calls in the reference, which never includes <omp.h> and
// is built here without OpenMP (Apple clang has none by default). Force-included by CMake.
#pragma once
#ifdef _OPENMP
#include <omp.h>
#else
inline int omp_get_max_threads() { return 1; }
inline int omp_get_thread_num() { return 0; }
#endif
