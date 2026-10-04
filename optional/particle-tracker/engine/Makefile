CXX ?= c++
CXXFLAGS ?= -O3 -std=c++17 -Wall -Wextra -Wpedantic
# macOS: make OMP= (or install libomp and provide its compiler/linker flags).
OMP ?= -fopenmp
all: particle-video
particle-video: main.cpp flow.hpp tracker.hpp dense.hpp
	$(CXX) $(CXXFLAGS) $(OMP) main.cpp -o $@
tests: tests.cpp flow.hpp tracker.hpp
	$(CXX) $(CXXFLAGS) $(OMP) tests.cpp -o $@
test: tests
	./tests
clean:
	rm -f particle-video tests
.PHONY: all test clean
