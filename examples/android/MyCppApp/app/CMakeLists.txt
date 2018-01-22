# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

set(distribution_DIR ${CMAKE_SOURCE_DIR}/../)

add_library(libgrpc STATIC IMPORTED)
set_target_properties(libgrpc PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/libgrpc/lib/${ANDROID_ABI}/libgrpc.a)

add_library(libgrpc++ STATIC IMPORTED)
set_target_properties(libgrpc++ PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/libgrpc++/lib/${ANDROID_ABI}/libgrpc++.a)

add_library(libgpr STATIC IMPORTED)
set_target_properties(libgpr PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/libgpr/lib/${ANDROID_ABI}/libgpr.a)

add_library(libcares STATIC IMPORTED)
set_target_properties(libcares PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/libcares/lib/${ANDROID_ABI}/libcares.a)

add_library(libzlib STATIC IMPORTED)
set_target_properties(libzlib PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/libzlib/lib/${ANDROID_ABI}/libz.a)

add_library(libcrypto STATIC IMPORTED)
set_target_properties(libcrypto PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/boringssl/lib/${ANDROID_ABI}/libcrypto.a)

add_library(libssl STATIC IMPORTED)
set_target_properties(libssl PROPERTIES IMPORTED_LOCATION
  ${distribution_DIR}/boringssl/lib/${ANDROID_ABI}/libssl.a)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

#add_library(libprotobuf STATIC IMPORTED)
#set_target_properties(libprotobuf PROPERTIES IMPORTED_LOCATION
#  ${distribution_DIR}/libprotobuf/lib/${ANDROID_ABI}/libprotobuf.a)



# Sets libprotobuf_src_DIR to the path of the target CMake project.
#set( libgmock_src_DIR ../gmock )
#
# Sets lib_build_DIR to the path of the desired output directory.
#set( libgmock_build_DIR ../gmock/outputs )
#file(MAKE_DIRECTORY ${libgmock_build_DIR})
#
# Adds the CMakeLists.txt file located in the specified directory
# as a build dependency.
#add_subdirectory( # Specifies the directory of the CMakeLists.txt file.
#                  ${libgmock_src_DIR}
#
#                  # Specifies the directory for the build outputs.
#                  ${libgmock_build_DIR} )


# Works with add_subdirectory

# Sets libprotobuf_src_DIR to the path of the target CMake project.
set( libprotobuf_src_DIR ../protobuf/cmake )

# Sets lib_build_DIR to the path of the desired output directory.
set( libprotobuf_build_DIR ../protobuf/outputs/${ANDROID_ABI} )
file(MAKE_DIRECTORY ${libprotobuf_build_DIR})

# Adds the CMakeLists.txt file located in the specified directory
# as a build dependency.
add_subdirectory( # Specifies the directory of the CMakeLists.txt file.
                  ${libprotobuf_src_DIR}

                  # Specifies the directory for the build outputs.
                  ${libprotobuf_build_DIR} )


get_filename_component(hw_proto "../protos/helloworld.proto" ABSOLUTE)
get_filename_component(hw_proto_path "${hw_proto}" PATH)

set(hw_grpc_srcs "${CMAKE_CURRENT_BINARY_DIR}/helloworld.grpc.pb.cc")
set(hw_grpc_hdrs "${CMAKE_CURRENT_BINARY_DIR}/helloworld.grpc.pb.h")

add_custom_command(
      OUTPUT "some_file4" # "${hw_grpc_hdrs}"
      COMMAND bash ${libprotobuf_build_DIR}/protoc
      #ARGS ${hw_grpc_srcs}
      #ARGS --grpc_out "${CMAKE_CURRENT_BINARY_DIR}" -I "${hw_proto_path}"
        #--plugin=protoc-gen-grpc="${gRPC_CPP_PLUGIN_EXECUTABLE}"
      #  "${hw_proto}"
      DEPENDS ${libprotobuf_build_DIR}
      )
add_custom_target(
  some_file3 ALL
  DEPENDS some_file4
  )
#add_custom_target(some_file2 DEPENDS some_file3)

include_directories("${CMAKE_CURRENT_BINARY_DIR}")

#set ( Protobuf_SRC_ROOT_FOLDER ${libprotobuf_src_DIR})
#set(Protobuf_USE_STATIC_LIBS ON)
set( Protobuf_LIBRARIES ${libprotobuf_src_DIR})
set( Protobuf_INCLUDE_DIR ${libprotobuf_src_DIR})
#set( Protobuf_PROTOC_LIBRARY ${libprotobuf_build_DIR})
#set( PROTOBUF_PROTOC_EXECUTABLE
#set(Protobuf_DIR ../protobuf/cmake)
#set(CMAKE_PREFIX_PATH
#    ${CMAKE_PREFIX_PATH}
#   ${libprotobuf_build_DIR}/lib/cmake/protobuf/
#)

#include(ExternalProject)
#ExternalProject_Add(Protobuf
#    SOURCE_DIR ../protobuf
#    PREFIX ../protobuf/outputs)

#ExternalProject_Get_Property(Protobuf protobuf_install_dir)
#include_directories(${protobuf_install_dir}/include)

#add_dependencies(project_b_exe native-lib)
#target_link_libraries(project_b_exe ${protobuf_install_dir}/lib/alib.lib)

#find_package(Protobuf 3.5.0 CONFIG REQUIRED)
#find_package(Protobuf 3.5.0 REQUIRED)

#add_library(libprotobuf ../protobuf/cmake)

#include(${libprotobuf_build_DIR}/lib/cmake/protobuf/protobuf-config.cmake)

#include_directories(${libprotobuf_src_DIR})
#set(LIBS ${LIBS} ${libprotobuf_build_DIR})
#message(STATUS "Using protobuf ${protobuf_VERSION}")

#include_directories(${libprotobuf_src_DIR})
#include(${libprotobuf_src_DIR}/protobuf-config.cmake.in)
#include(${libprotobuf_src_DIR}/protobuf-module.cmake.in)
# Generated sources

add_library( # Sets the name of the library.
             native-lib

             # Sets the library as a shared library.
             SHARED

             # Provides a relative path to your source file(s).
             src/main/cpp/native-lib.cpp )


# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.


find_library( # Sets the name of the path variable.
              log-lib

              # Specifies the name of the NDK library that
              # you want CMake to locate.
              log )


target_include_directories(native-lib PRIVATE
              ${distribution_DIR}/libgrpc/include
              ${distribution_DIR}/libgrpc++/include)

# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.



target_link_libraries( # Specifies the target library.
                       native-lib
                       libgrpc++
                                              libgrpc
libzlib
                                              libcares
                                              libssl
                                              libcrypto

                                              #libprotobuf libprotobuf-lite libprotoc
                                              libgpr # Order matters
                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib})

add_dependencies( native-lib "some_file3")

#protobuf_generate_cpp(hw_proto_srcs hw_proto_hdrs "${hw_proto}")