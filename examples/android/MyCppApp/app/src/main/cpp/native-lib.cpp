#include <jni.h>

#include <atomic>
#include <iostream>
#include <memory>
#include <string>
#include <grpc++/grpc++.h>
#include "src/core/lib/gpr/env.h"

//#include "test/cpp/interop/client_helper.h"
#include "test/cpp/interop/interop_client.h"

#include <grpc/support/log.h>


#include "helloworld.grpc.pb.h"
#include "src/proto/grpc/testing/messages.pb.h"
#include "src/proto/grpc/testing/test.grpc.pb.h"

using grpc::Channel;
using grpc::ClientContext;
using grpc::Status;
using helloworld::HelloRequest;
using helloworld::HelloReply;
using helloworld::Greeter;

//using grpc::testing::TestService;
//using grpc::testing::Empty;
//using grpc::testing::SimpleRequest;
//using grpc::testing::SimpleResponse;

using grpc::Server;
using grpc::ServerBuilder;
using grpc::ServerContext;


std::atomic<bool> stop_server(false);

// Logic and data behind the server's behavior.
class GreeterServiceImpl final : public Greeter::Service {
    Status SayHello(ServerContext* context, const HelloRequest* request,
                    HelloReply* reply) override {
        std::string prefix("Hello ");
        reply->set_message(prefix + request->name());
        return Status::OK;
    }
};

//std::unique_ptr<Server> StartServer() {
void StartServer(JNIEnv *env, jobject obj, jmethodID mid) {
    std::string server_address("0.0.0.0:50051");
    GreeterServiceImpl service;

    ServerBuilder builder;
    // Listen on the given address without any authentication mechanism.
    builder.AddListeningPort(server_address, grpc::InsecureServerCredentials());
    // Register "service" as the instance through which we'll communicate with
    // clients. In this case it corresponds to an *synchronous* service.
    builder.RegisterService(&service);
    // Finally assemble the server.
    std::unique_ptr<Server> server(builder.BuildAndStart());
    std::cout << "Server listening on " << server_address << std::endl;
    gpr_log(GPR_ERROR, "server listening");
//    return server;
//    // Wait for the server to shutdown. Note that some other thread must be
//    // responsible for shutting down the server for this call to ever return.
//    server->Wait();
    while(!stop_server.load()) {
        jboolean isCancelled = env->CallBooleanMethod(obj, mid);
        if (isCancelled == JNI_TRUE) {
            gpr_log(GPR_ERROR, "cancelled");
            stop_server = true;
        } else {
            gpr_log(GPR_ERROR, "not cancelled");
        }
    }
    gpr_log(GPR_ERROR, "server stopped!");
}


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

//class InteropClient {
//public:
//    InteropClient(std::shared_ptr<Channel> channel)
//            : stub_(TestService::NewStub(channel)) {}
//
//    // Assembles the client's payload, sends it and presents the response back
//    // from the server.
//    std::string doEmptyUnary() {
//        // Data we are sending to the server.
//        Empty request;
//
//
//        // Container for the data we expect from the server.
//        Empty reply;
//
//        // Context for the client. It could be used to convey extra information to
//        // the server and/or tweak certain RPC behaviors.
//        ClientContext context;
//
//        // The actual RPC.
//        Status status = stub_->EmptyCall(&context, request, &reply);
//
////        return "blah";
//
//        // Act upon its status.
//        if (status.ok()) {
//            return "empty unary succeeded";
//        } else {
//            std::cout << status.error_code() << ": " << status.error_message()
//                      << std::endl;
//            //return "RPC failed";
//            return status.error_message();
//        }
//    }
//
//    std::string doLargeUnary() {
//        // Data we are sending to the server.
//        SimpleRequest request;
//        request.set_response_size(1000);
//
//        grpc::string payload(1212, '\0');
//        request.mutable_payload()->set_body(payload.c_str(), 1212);
//
//        // Container for the data we expect from the server.
//        SimpleResponse reply;
//
//        // Context for the client. It could be used to convey extra information to
//        // the server and/or tweak certain RPC behaviors.
//        ClientContext context;
//
//        // The actual RPC.
//        Status status = stub_->UnaryCall(&context, request, &reply);
//
////        return "blah";
//
//        // Act upon its status.
//        if (status.ok()) {
//            std::string s = reply.DebugString();
////            if (reply.SerializeToString(&s)) {
////                return "serialize true";
////            } else {
////                return "serialize false";
////            }
//            gpr_log(GPR_ERROR, "to string: %s", s.c_str());
//            return "large unary succeeded";
//        } else {
//            std::cout << status.error_code() << ": " << status.error_message()
//                      << std::endl;
//            //return "RPC failed";
//            return status.error_message();
//        }
//    }
//
//
//private:
//    std::unique_ptr<TestService::Stub> stub_;
//};

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
//gpr_setenv("GRPC_TRACE", "all");
    gpr_setenv("GRPC_VERBOSITY", "debug");

    gpr_log(GPR_ERROR, "cert: %s", cert.c_str());

    auto channel_creds = grpc::SslCredentials(opts);
    auto channel = grpc::CreateChannel(
              "grpc-test.sandbox.googleapis.com", channel_creds);
    grpc::testing::InteropClient client(channel,
                                      true,
                                      false);
    client.DoLargeUnary();
    // probe doesn't return (supported by ESF? ) 
    // client.DoClientCompressedStreaming();
    client.DoTimeoutOnSleepingServer();
    client.DoPingPong();
    if (client.DoCancelAfterBegin()) {
      gpr_log(GPR_ERROR, "CancelAfterBegin success");
      return env->NewStringUTF("success");
    } else {
      gpr_log(GPR_ERROR, "CancelAfterBegin failure");
      return env->NewStringUTF("failure");
    }
//
////    GreeterClient greeter(
////        channel);
//    GreeterClient greeter(grpc::CreateChannel(
//        "10.0.2.2:50051", grpc::InsecureChannelCredentials()));
//    std::string user("myself on my own server");
//    std::string reply = greeter.SayHello(user);
//    //std::cout << "Greeter received: " << reply << std::endl;
//
////    InteropClient interopClient(channel);
////    std::string reply = interopClient.doLargeUnary();
//
//
////    RunServer();
//
//    return env->NewStringUTF(reply.c_str());

//    gpr_log(GPR_ERROR, "yo grpc");
//    return env->NewStringUTF(hello.c_str());
}

extern "C"
JNIEXPORT void
JNICALL
Java_com_example_ericgribkoff_mycppapp_MainActivity_startServer(
        JNIEnv *env, jobject obj /* this */) {
    gpr_setenv("GRPC_TRACE", "all");
    gpr_setenv("GRPC_VERBOSITY", "debug");

    jclass cls = env->GetObjectClass(obj);
    jmethodID mid = env->GetMethodID(cls, "isRunServerTaskCancelled", "()Z");
    if (mid == 0) {
        gpr_log(GPR_ERROR, "java method not found");
    }
    jboolean isCancelled = env->CallBooleanMethod(obj, mid);
    if (isCancelled == JNI_TRUE) {
        gpr_log(GPR_ERROR, "cancelled");
    } else {
        gpr_log(GPR_ERROR, "not cancelled");
    }

    stop_server = false;

    StartServer(env, obj, mid);
//    std::unique_ptr<Server> server = StartServer();
//
//    jclass thread = env->FindClass("java/lang/Thread");
//    jmethodID mCurThread = env->GetStaticMethodID(thread, "currentThread", "()Ljava/lang/Thread;");
//    jmethodID mIsInterrupted = env->GetMethodID(thread, "isInterrupted", "()Z");
//    jobject curThread = (jobject) env->CallStaticObjectMethod(thread, mCurThread);
//
//    for (;;) {
//        jboolean res = (jboolean) env->CallBooleanMethod(curThread, mIsInterrupted);
//        if (res == JNI_TRUE) {
//            server.get()->Shutdown();
//        }
//    }
}


extern "C"
JNIEXPORT void
JNICALL
Java_com_example_ericgribkoff_mycppapp_MainActivity_stopServer() {
    stop_server = true;
    gpr_log(GPR_ERROR, "set stop_server flag to true");
}
