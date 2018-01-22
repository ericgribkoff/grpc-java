#include <jni.h>
#include <string>
#include <grpc++/grpc++.h>

//#include "helloworld.grpc.pb.h"

#include <grpc/support/log.h>


extern "C"
JNIEXPORT jstring

JNICALL
Java_com_example_ericgribkoff_mycppapp_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    grpc::CreateChannel(
              "localhost:50051", grpc::InsecureChannelCredentials());
    gpr_log(GPR_ERROR, "yo grpc");
    return env->NewStringUTF(hello.c_str());
}
