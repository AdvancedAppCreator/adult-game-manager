cmake_minimum_required(VERSION 3.22.1)
project(unrarjni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Sources that build into the RAR DLL/library (OBJECTS + LIB_OBJ from upstream makefile).
set(UNRAR_DIR ${CMAKE_SOURCE_DIR}/unrar)
set(UNRAR_SRC
    ${UNRAR_DIR}/rar.cpp
    ${UNRAR_DIR}/strlist.cpp
    ${UNRAR_DIR}/strfn.cpp
    ${UNRAR_DIR}/pathfn.cpp
    ${UNRAR_DIR}/smallfn.cpp
    ${UNRAR_DIR}/global.cpp
    ${UNRAR_DIR}/file.cpp
    ${UNRAR_DIR}/filefn.cpp
    ${UNRAR_DIR}/filcreat.cpp
    ${UNRAR_DIR}/archive.cpp
    ${UNRAR_DIR}/arcread.cpp
    ${UNRAR_DIR}/unicode.cpp
    ${UNRAR_DIR}/system.cpp
    ${UNRAR_DIR}/crypt.cpp
    ${UNRAR_DIR}/crc.cpp
    ${UNRAR_DIR}/rawread.cpp
    ${UNRAR_DIR}/encname.cpp
    ${UNRAR_DIR}/resource.cpp
    ${UNRAR_DIR}/match.cpp
    ${UNRAR_DIR}/timefn.cpp
    ${UNRAR_DIR}/rdwrfn.cpp
    ${UNRAR_DIR}/consio.cpp
    ${UNRAR_DIR}/options.cpp
    ${UNRAR_DIR}/errhnd.cpp
    ${UNRAR_DIR}/rarvm.cpp
    ${UNRAR_DIR}/secpassword.cpp
    ${UNRAR_DIR}/rijndael.cpp
    ${UNRAR_DIR}/getbits.cpp
    ${UNRAR_DIR}/sha1.cpp
    ${UNRAR_DIR}/sha256.cpp
    ${UNRAR_DIR}/blake2s.cpp
    ${UNRAR_DIR}/hash.cpp
    ${UNRAR_DIR}/extinfo.cpp
    ${UNRAR_DIR}/extract.cpp
    ${UNRAR_DIR}/volume.cpp
    ${UNRAR_DIR}/list.cpp
    ${UNRAR_DIR}/find.cpp
    ${UNRAR_DIR}/unpack.cpp
    ${UNRAR_DIR}/headers.cpp
    ${UNRAR_DIR}/threadpool.cpp
    ${UNRAR_DIR}/rs16.cpp
    ${UNRAR_DIR}/cmddata.cpp
    ${UNRAR_DIR}/ui.cpp
    ${UNRAR_DIR}/largepage.cpp
    ${UNRAR_DIR}/filestr.cpp
    ${UNRAR_DIR}/scantree.cpp
    ${UNRAR_DIR}/dll.cpp
    ${UNRAR_DIR}/qopen.cpp
)

add_definitions(
    -DRARDLL                # build the DLL/library entry points in dll.cpp
    -D_UNIX                 # Unix-style file handling
    -DSILENT                # no stdout chatter from utility prints
    -D_FILE_OFFSET_BITS=64  # large-file (>4 GB) support on Linux/Android
)

# Upstream uses some constructs that newer clang warns on but are intentional.
add_compile_options(
    -Wno-logical-op-parentheses
    -Wno-switch
    -Wno-dangling-else
    -Wno-format
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-unused-private-field
    -Wno-deprecated-declarations
    -fexceptions
    -fPIC
)

add_library(unrarjni SHARED
    ${UNRAR_SRC}
    jni_unrar.cpp
)

target_include_directories(unrarjni PRIVATE ${UNRAR_DIR})
find_library(log-lib log)
target_link_libraries(unrarjni ${log-lib})
