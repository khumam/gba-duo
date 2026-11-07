# Host (Mac/Linux) build of the slang shader code and its tests. Run through ../run-slang.sh.
cmake_minimum_required(VERSION 3.16)
project(gbaduo_slang_tests CXX)
set(CMAKE_CXX_STANDARD 17)

get_filename_component(CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../main/cpp" ABSOLUTE)
include("${CPP_DIR}/slang_toolchain.cmake")

# Sources under test (added by later tasks).
set(SLANG_SOURCES "")
foreach(f slang_source slang_preset slang_translate slang_chain_plan)
    if(EXISTS "${CPP_DIR}/${f}.cpp")
        list(APPEND SLANG_SOURCES "${CPP_DIR}/${f}.cpp")
    endif()
endforeach()

file(GLOB TESTS "${CMAKE_CURRENT_LIST_DIR}/*_test.cpp")
foreach(t ${TESTS})
    get_filename_component(name ${t} NAME_WE)
    add_executable(${name} ${t} ${SLANG_SOURCES})
    target_include_directories(${name} PRIVATE "${CPP_DIR}")
    target_compile_definitions(${name} PRIVATE FIXTURES_DIR="${CMAKE_CURRENT_LIST_DIR}/fixtures")
    target_link_libraries(${name} PRIVATE slang_toolchain)
endforeach()
