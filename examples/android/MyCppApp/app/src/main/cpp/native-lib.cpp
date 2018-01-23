#include <jni.h>

#include <iostream>
#include <memory>
#include <string>
#include <grpc++/grpc++.h>


#include <grpc/support/log.h>

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
//        Status status = stub_->SayHello(&context, request, &reply);

        return "blah";

//        // Act upon its status.
//        if (status.ok()) {
//            return reply.message();
//        } else {
//            std::cout << status.error_code() << ": " << status.error_message()
//                      << std::endl;
//            return "RPC failed";
//        }
    }

private:
    std::unique_ptr<Greeter::Stub> stub_;
};

extern "C"
JNIEXPORT jstring

JNICALL
Java_com_example_ericgribkoff_mycppapp_MainActivity_stringFromJNI(
        JNIEnv *env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    grpc::CreateChannel(
              "localhost:50051", grpc::InsecureChannelCredentials());

    GreeterClient greeter(grpc::CreateChannel(
            "10.0.2.2:50051", grpc::InsecureChannelCredentials()));
    std::string user("world");
    std::string reply = greeter.SayHello(hello);
//    //std::cout << "Greeter received: " << reply << std::endl;
//    return env->NewStringUTF(reply.c_str());

    gpr_log(GPR_ERROR, "yo grpc");
    return env->NewStringUTF(hello.c_str());
}
