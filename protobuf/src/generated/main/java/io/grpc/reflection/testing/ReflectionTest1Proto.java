// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: reflection_test_1.proto

package io.grpc.reflection.testing;

public final class ReflectionTest1Proto {
  private ReflectionTest1Proto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_testing_HelloRequest_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_testing_HelloRequest_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_grpc_reflection_testing_HelloReply_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_grpc_reflection_testing_HelloReply_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n\027reflection_test_1.proto\022\027grpc.reflecti" +
      "on.testing\"\034\n\014HelloRequest\022\014\n\004name\030\001 \002(\t" +
      "\"\035\n\nHelloReply\022\017\n\007message\030\001 \002(\t2n\n\022Refle" +
      "ctableService\022X\n\010SayHello\022%.grpc.reflect" +
      "ion.testing.HelloRequest\032#.grpc.reflecti" +
      "on.testing.HelloReply\"\000B4\n\032io.grpc.refle" +
      "ction.testingB\024ReflectionTest1ProtoP\001"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
        }, assigner);
    internal_static_grpc_reflection_testing_HelloRequest_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_grpc_reflection_testing_HelloRequest_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_testing_HelloRequest_descriptor,
        new java.lang.String[] { "Name", });
    internal_static_grpc_reflection_testing_HelloReply_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_grpc_reflection_testing_HelloReply_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_grpc_reflection_testing_HelloReply_descriptor,
        new java.lang.String[] { "Message", });
  }

  // @@protoc_insertion_point(outer_class_scope)
}
