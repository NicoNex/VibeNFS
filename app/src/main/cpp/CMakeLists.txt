cmake_minimum_required(VERSION 3.22)
project(vibenfs C)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_MULTITHREADING ON CACHE BOOL "" FORCE)
set(ENABLE_TLS OFF CACHE STRING "" FORCE)
set(ENABLE_KRB5 OFF CACHE STRING "" FORCE)
# Bionic has pthreads in libc, so libnfs' "link -lpthread" probe fails; pre-seed it.
set(HAVE_PTHREAD 1 CACHE INTERNAL "")
add_subdirectory(libnfs EXCLUDE_FROM_ALL)

add_library(vibenfs SHARED nfs_jni.c)
target_link_libraries(vibenfs nfs)
target_include_directories(vibenfs PRIVATE libnfs/include libnfs/include/nfsc libnfs/mount)
