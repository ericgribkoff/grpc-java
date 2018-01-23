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




# Works with add_subdirectory

# Sets libprotobuf_src_DIR to the path of the target CMake project.
set( libprotobuf_src_DIR ../protobuf/cmake )

# Sets lib_build_DIR to the path of the desired output directory.
set( libprotobuf_build_DIR ../protobuf/outputs/${ANDROID_ABI} )
#file(MAKE_DIRECTORY ${libprotobuf_build_DIR})

# Adds the CMakeLists.txt file located in the specified directory
# as a build dependency.
add_subdirectory( # Specifies the directory of the CMakeLists.txt file.
                  ${libprotobuf_src_DIR}

                  # Specifies the directory for the build outputs.
                 ${libprotobuf_build_DIR} )
#include_directories
include_directories(../protobuf/src)
include_directories(${distribution_DIR}/libgrpc++/include)
include_directories(${distribution_DIR}/libgrpc/include)
# For well-known .proto files distributed with protobuf
#include_directories("../protobuf/src")

# Use host version of protoc, not cross-compiled version
set(PROTOBUF_PROTOC_EXECUTABLE /usr/local/bin/protoc)
set(GRPC_CPP_PLUGIN_EXECUTABLE /usr/local/bin/grpc_cpp_plugin)

function(android_protobuf_generate_cpp srcs_var hdrs_var)
  if(NOT ARGN)
    message(SEND_ERROR "Error: android_protobuf_generate_cpp() called without any proto files")
    return()
  endif()

  set(${srcs_var})
  set(${hdrs_var})
  foreach(fil ${ARGN})
    get_filename_component(abs_fil ${fil} ABSOLUTE)
    get_filename_component(fil_we ${fil} NAME_WE)

    list(APPEND ${srcs_var} "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.pb.cc")
    list(APPEND ${hdrs_var} "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.pb.h")
    list(APPEND ${srcs_var} "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.grpc.pb.cc")
    list(APPEND ${hdrs_var} "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.grpc.pb.h")

    add_custom_command(
      OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.pb.cc"
             "${CMAKE_CURRENT_BINARY_DIR}/protos/${fil_we}.pb.h"
      WORKING_DIRECTORY ${PROJECT_SOURCE_DIR}
      COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_CURRENT_BINARY_DIR}"
      COMMAND ${PROTOBUF_PROTOC_EXECUTABLE} -I${PROJECT_SOURCE_DIR}/protos --cpp_out    "${PROJECT_BINARY_DIR}/protos" ${abs_fil}
      COMMAND ${PROTOBUF_PROTOC_EXECUTABLE}
            ARGS --grpc_out "${PROJECT_BINARY_DIR}/protos" -I ${PROJECT_SOURCE_DIR}/protos
              --plugin=protoc-gen-grpc=${GRPC_CPP_PLUGIN_EXECUTABLE}
              "${abs_fil}"
      DEPENDS ${PROTOBUF_PROTOC_EXECUTABLE} ${abs_fil}
      COMMENT "Running C++ protocol buffer compiler on ${fil}" VERBATIM )
  endforeach()

  set_source_files_properties(${${srcs_var}} ${${hdrs_var}} PROPERTIES GENERATED TRUE)
  set(${srcs_var} ${${srcs_var}} PARENT_SCOPE)
  set(${hdrs_var} ${${hdrs_var}} PARENT_SCOPE)
endfunction()


#get_filename_component(hw_proto "../protos/helloworld.proto" ABSOLUTE)
#get_filename_component(hw_proto_path "${hw_proto}" PATH)
#set(hw_grpc_srcs "${CMAKE_CURRENT_BINARY_DIR}/helloworld.grpc.pb.cc")
#set(hw_grpc_hdrs "${CMAKE_CURRENT_BINARY_DIR}/helloworld.grpc.pb.h")

file(GLOB Caffe2_PROTOBUF_FILES "./protos/*.proto")

android_protobuf_generate_cpp(Caffe2_PROTO_SRCS Caffe2_PROTO_HEADERS ${Caffe2_PROTOBUF_FILES})

add_library(Caffe2_PROTO SHARED ${Caffe2_PROTO_HEADERS} ${Caffe2_PROTO_SRCS})
target_link_libraries( Caffe2_PROTO libprotobuf libgrpc libgrpc++ android log)

#install(FILES ${Caffe2_PROTO_HEADERS} DESTINATION include/caffe2/proto)

#add_custom_command(
#      OUTPUT "some_file4" # "${hw_grpc_hdrs}"
#      COMMAND bash ${libprotobuf_build_DIR}/protoc
#      #ARGS ${hw_grpc_srcs}
#      #ARGS --grpc_out "${CMAKE_CURRENT_BINARY_DIR}" -I "${hw_proto_path}"
#        #--plugin=protoc-gen-grpc="${GRPC_CPP_PLUGIN_EXECUTABLE}"
#      #  "${hw_proto}"
#      DEPENDS ${libprotobuf_build_DIR}
#      )
#add_custom_target(
#  some_file3 ALL
#  DEPENDS some_file4
#  )
#add_custom_target(some_file2 DEPENDS some_file3)

#include_directories("${CMAKE_CURRENT_BINARY_DIR}")

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

include_directories(${CMAKE_CURRENT_BINARY_DIR}/protos/)

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
#              ${distribution_DIR}/libgrpc++/include
              ${Caffe2_PROTO_HEADERS})

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
Caffe2_PROTO
                                              libprotobuf # libprotobuf-lite
                                              libgpr # Order matters
                       # Links the target library to the log library
                       # included in the NDK.
                       ${log-lib})

#add_dependencies( native-lib "some_file3")
