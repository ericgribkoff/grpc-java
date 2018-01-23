#include <jni.h>

#include <iostream>
#include <memory>
#include <string>
#include <grpc++/grpc++.h>
#include "src/core/lib/gpr/env.h"


#include <grpc/support/log.h>

//#include <android/asset_manager.h>

#include "helloworld.grpc.pb.h"

using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;
using helloworld::HelloRequest;
using helloworld::HelloReply;
using helloworld::Greeter;

class GreeterClient {
public:
    GreeterClient(std::shared_ptr<Channel> channel)
            : stub_(Greeter::NewStub(channel)) {}

    // Assembles the client's payload, sends it and presents the response back
    // from the server.
    std::string SayHello(const std::string& user) {
        // Data we are sending to the server.
        HelloRequest request;
        request.set_name(user);


        // Container for the data we expect from the server.
        HelloReply reply;

        // Context for the client. It could be used to convey extra information to
        // the server and/or tweak certain RPC behaviors.
        ClientContext context;

        // The actual RPC.
        Status status = stub_->SayHello(&context, request, &reply);

//        return "blah";

        // Act upon its status.
        if (status.ok()) {
            return reply.message();
        } else {
            std::cout << status.error_code() << ": " << status.error_message()
                      << std::endl;
            //return "RPC failed";
            return status.error_message();
        }
    }

private:
    std::unique_ptr<Greeter::Stub> stub_;
};

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_example_ericgribkoff_mycppapp_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */, jstring certRaw) {

    const jsize len = env->GetStringUTFLength(certRaw);
    const char* certChars = env->GetStringUTFChars(certRaw, (jboolean *)0);

    std::string cert(certChars, len);


//    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);
//    AAsset* asset = AAssetManager_open(mgr, "roots.pem", AASSET_MODE_UNKNOWN);
//    long size = AAsset_getLength(asset);
//    char* buffer = (char*) malloc (sizeof(char)*size);
//    AAsset_read (asset,buffer,size);
//    AAsset_close(asset);


    std::string hello = "Hello from C++";
    // Create a default SSL ChannelCredentials object.
    grpc::SslCredentialsOptions opts = grpc::SslCredentialsOptions();
//            {
//                    "",
//                          "",
//                                cert
//                                        };
    gpr_setenv("GRPC_DEFAULT_SSL_ROOTS_FILE_PATH", cert.c_str());
   gpr_setenv("GRPC_TRACE", "all");
    gpr_setenv("GRPC_VERBOSITY", "debug");

    gpr_log(GPR_ERROR, "cert: %s", cert.c_str());

    auto channel_creds = grpc::SslCredentials(opts);
    auto channel = grpc::CreateChannel(
              "grpc-test.sandbox.googleapis.com", channel_creds);

    GreeterClient greeter(
        channel);
//    GreeterClient greeter(grpc::CreateChannel(
//        "10.0.2.2:50051", grpc::InsecureChannelCredentials()));
//    std::string user("world");
    std::string reply = greeter.SayHello(hello);
    //std::cout << "Greeter received: " << reply << std::endl;
    return env->NewStringUTF(reply.c_str());

//    gpr_log(GPR_ERROR, "yo grpc");
//    return env->NewStringUTF(hello.c_str());
}
