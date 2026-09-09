cmake_minimum_required(VERSION 3.22)

project(global_agent LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(GLOBAL_AGENT_BUILD_TESTS "Build host-side tests" ON)
option(GLOBAL_AGENT_ENABLE_SANITIZERS "Enable address and undefined behavior sanitizers" OFF)

add_library(global_agent_core STATIC
    src/agent_loop.cpp
    src/bezier.cpp
    src/crc32.cpp
    src/dumpsys_parser.cpp
    src/gesture_validation.cpp
    src/session_context.cpp
    src/state_graph.cpp
    src/state_store.cpp
    src/subprocess.cpp
    src/platform/shell/shell_backend.cpp
)

target_include_directories(global_agent_core PUBLIC include)
target_compile_options(global_agent_core PRIVATE -Wall -Wextra -Wpedantic)

if(GLOBAL_AGENT_ENABLE_SANITIZERS AND NOT ANDROID)
    target_compile_options(global_agent_core PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
endif()

add_executable(global-agentd
    src/main.cpp
    src/platform/synthetic_platform.cpp
)
target_link_libraries(global-agentd PRIVATE global_agent_core)
target_compile_options(global-agentd PRIVATE -Wall -Wextra -Wpedantic)
if(GLOBAL_AGENT_ENABLE_SANITIZERS AND NOT ANDROID)
    target_compile_options(global-agentd PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
    target_link_options(global-agentd PRIVATE -fsanitize=address,undefined)
endif()

if(GLOBAL_AGENT_BUILD_TESTS)
    enable_testing()
    add_executable(global_agent_tests
        tests/test_main.cpp
    )
    target_link_libraries(global_agent_tests PRIVATE global_agent_core)
    target_compile_options(global_agent_tests PRIVATE -Wall -Wextra -Wpedantic)
    if(GLOBAL_AGENT_ENABLE_SANITIZERS AND NOT ANDROID)
        target_compile_options(global_agent_tests PRIVATE -fsanitize=address,undefined -fno-omit-frame-pointer)
        target_link_options(global_agent_tests PRIVATE -fsanitize=address,undefined)
    endif()
    add_test(NAME global_agent_tests COMMAND global_agent_tests)
endif()
