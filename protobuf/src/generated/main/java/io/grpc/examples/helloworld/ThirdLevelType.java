// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: third_level.proto

package io.grpc.examples.helloworld;

/**
 * Protobuf type {@code helloworld.ThirdLevelType}
 */
public  final class ThirdLevelType extends
    com.google.protobuf.GeneratedMessageV3.ExtendableMessage<
      ThirdLevelType> implements
    // @@protoc_insertion_point(message_implements:helloworld.ThirdLevelType)
    ThirdLevelTypeOrBuilder {
  // Use ThirdLevelType.newBuilder() to construct.
  private ThirdLevelType(com.google.protobuf.GeneratedMessageV3.ExtendableBuilder<io.grpc.examples.helloworld.ThirdLevelType, ?> builder) {
    super(builder);
  }
  private ThirdLevelType() {
  }

  @java.lang.Override
  public final com.google.protobuf.UnknownFieldSet
  getUnknownFields() {
    return this.unknownFields;
  }
  private ThirdLevelType(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    this();
    com.google.protobuf.UnknownFieldSet.Builder unknownFields =
        com.google.protobuf.UnknownFieldSet.newBuilder();
    try {
      boolean done = false;
      while (!done) {
        int tag = input.readTag();
        switch (tag) {
          case 0:
            done = true;
            break;
          default: {
            if (!parseUnknownField(input, unknownFields,
                                   extensionRegistry, tag)) {
              done = true;
            }
            break;
          }
        }
      }
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      throw e.setUnfinishedMessage(this);
    } catch (java.io.IOException e) {
      throw new com.google.protobuf.InvalidProtocolBufferException(
          e).setUnfinishedMessage(this);
    } finally {
      this.unknownFields = unknownFields.build();
      makeExtensionsImmutable();
    }
  }
  public static final com.google.protobuf.Descriptors.Descriptor
      getDescriptor() {
    return io.grpc.examples.helloworld.ThirdLevelProto.internal_static_helloworld_ThirdLevelType_descriptor;
  }

  protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internalGetFieldAccessorTable() {
    return io.grpc.examples.helloworld.ThirdLevelProto.internal_static_helloworld_ThirdLevelType_fieldAccessorTable
        .ensureFieldAccessorsInitialized(
            io.grpc.examples.helloworld.ThirdLevelType.class, io.grpc.examples.helloworld.ThirdLevelType.Builder.class);
  }

  private byte memoizedIsInitialized = -1;
  public final boolean isInitialized() {
    byte isInitialized = memoizedIsInitialized;
    if (isInitialized == 1) return true;
    if (isInitialized == 0) return false;

    if (!extensionsAreInitialized()) {
      memoizedIsInitialized = 0;
      return false;
    }
    memoizedIsInitialized = 1;
    return true;
  }

  public void writeTo(com.google.protobuf.CodedOutputStream output)
                      throws java.io.IOException {
    com.google.protobuf.GeneratedMessageV3
      .ExtendableMessage<io.grpc.examples.helloworld.ThirdLevelType>.ExtensionWriter
        extensionWriter = newExtensionWriter();
    extensionWriter.writeUntil(200, output);
    unknownFields.writeTo(output);
  }

  public int getSerializedSize() {
    int size = memoizedSize;
    if (size != -1) return size;

    size = 0;
    size += extensionsSerializedSize();
    size += unknownFields.getSerializedSize();
    memoizedSize = size;
    return size;
  }

  private static final long serialVersionUID = 0L;
  @java.lang.Override
  public boolean equals(final java.lang.Object obj) {
    if (obj == this) {
     return true;
    }
    if (!(obj instanceof io.grpc.examples.helloworld.ThirdLevelType)) {
      return super.equals(obj);
    }
    io.grpc.examples.helloworld.ThirdLevelType other = (io.grpc.examples.helloworld.ThirdLevelType) obj;

    boolean result = true;
    result = result && unknownFields.equals(other.unknownFields);
    result = result &&
        getExtensionFields().equals(other.getExtensionFields());
    return result;
  }

  @java.lang.Override
  public int hashCode() {
    if (memoizedHashCode != 0) {
      return memoizedHashCode;
    }
    int hash = 41;
    hash = (19 * hash) + getDescriptorForType().hashCode();
    hash = hashFields(hash, getExtensionFields());
    hash = (29 * hash) + unknownFields.hashCode();
    memoizedHashCode = hash;
    return hash;
  }

  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      com.google.protobuf.ByteString data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      com.google.protobuf.ByteString data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(byte[] data)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      byte[] data,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws com.google.protobuf.InvalidProtocolBufferException {
    return PARSER.parseFrom(data, extensionRegistry);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseDelimitedFrom(java.io.InputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseDelimitedFrom(
      java.io.InputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseDelimitedWithIOException(PARSER, input, extensionRegistry);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      com.google.protobuf.CodedInputStream input)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input);
  }
  public static io.grpc.examples.helloworld.ThirdLevelType parseFrom(
      com.google.protobuf.CodedInputStream input,
      com.google.protobuf.ExtensionRegistryLite extensionRegistry)
      throws java.io.IOException {
    return com.google.protobuf.GeneratedMessageV3
        .parseWithIOException(PARSER, input, extensionRegistry);
  }

  public Builder newBuilderForType() { return newBuilder(); }
  public static Builder newBuilder() {
    return DEFAULT_INSTANCE.toBuilder();
  }
  public static Builder newBuilder(io.grpc.examples.helloworld.ThirdLevelType prototype) {
    return DEFAULT_INSTANCE.toBuilder().mergeFrom(prototype);
  }
  public Builder toBuilder() {
    return this == DEFAULT_INSTANCE
        ? new Builder() : new Builder().mergeFrom(this);
  }

  @java.lang.Override
  protected Builder newBuilderForType(
      com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
    Builder builder = new Builder(parent);
    return builder;
  }
  /**
   * Protobuf type {@code helloworld.ThirdLevelType}
   */
  public static final class Builder extends
      com.google.protobuf.GeneratedMessageV3.ExtendableBuilder<
        io.grpc.examples.helloworld.ThirdLevelType, Builder> implements
      // @@protoc_insertion_point(builder_implements:helloworld.ThirdLevelType)
      io.grpc.examples.helloworld.ThirdLevelTypeOrBuilder {
    public static final com.google.protobuf.Descriptors.Descriptor
        getDescriptor() {
      return io.grpc.examples.helloworld.ThirdLevelProto.internal_static_helloworld_ThirdLevelType_descriptor;
    }

    protected com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
        internalGetFieldAccessorTable() {
      return io.grpc.examples.helloworld.ThirdLevelProto.internal_static_helloworld_ThirdLevelType_fieldAccessorTable
          .ensureFieldAccessorsInitialized(
              io.grpc.examples.helloworld.ThirdLevelType.class, io.grpc.examples.helloworld.ThirdLevelType.Builder.class);
    }

    // Construct using io.grpc.examples.helloworld.ThirdLevelType.newBuilder()
    private Builder() {
      maybeForceBuilderInitialization();
    }

    private Builder(
        com.google.protobuf.GeneratedMessageV3.BuilderParent parent) {
      super(parent);
      maybeForceBuilderInitialization();
    }
    private void maybeForceBuilderInitialization() {
      if (com.google.protobuf.GeneratedMessageV3
              .alwaysUseFieldBuilders) {
      }
    }
    public Builder clear() {
      super.clear();
      return this;
    }

    public com.google.protobuf.Descriptors.Descriptor
        getDescriptorForType() {
      return io.grpc.examples.helloworld.ThirdLevelProto.internal_static_helloworld_ThirdLevelType_descriptor;
    }

    public io.grpc.examples.helloworld.ThirdLevelType getDefaultInstanceForType() {
      return io.grpc.examples.helloworld.ThirdLevelType.getDefaultInstance();
    }

    public io.grpc.examples.helloworld.ThirdLevelType build() {
      io.grpc.examples.helloworld.ThirdLevelType result = buildPartial();
      if (!result.isInitialized()) {
        throw newUninitializedMessageException(result);
      }
      return result;
    }

    public io.grpc.examples.helloworld.ThirdLevelType buildPartial() {
      io.grpc.examples.helloworld.ThirdLevelType result = new io.grpc.examples.helloworld.ThirdLevelType(this);
      onBuilt();
      return result;
    }

    public Builder clone() {
      return (Builder) super.clone();
    }
    public Builder setField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        Object value) {
      return (Builder) super.setField(field, value);
    }
    public Builder clearField(
        com.google.protobuf.Descriptors.FieldDescriptor field) {
      return (Builder) super.clearField(field);
    }
    public Builder clearOneof(
        com.google.protobuf.Descriptors.OneofDescriptor oneof) {
      return (Builder) super.clearOneof(oneof);
    }
    public Builder setRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        int index, Object value) {
      return (Builder) super.setRepeatedField(field, index, value);
    }
    public Builder addRepeatedField(
        com.google.protobuf.Descriptors.FieldDescriptor field,
        Object value) {
      return (Builder) super.addRepeatedField(field, value);
    }
    public <Type> Builder setExtension(
        com.google.protobuf.GeneratedMessage.GeneratedExtension<
            io.grpc.examples.helloworld.ThirdLevelType, Type> extension,
        Type value) {
      return (Builder) super.setExtension(extension, value);
    }
    public <Type> Builder setExtension(
        com.google.protobuf.GeneratedMessage.GeneratedExtension<
            io.grpc.examples.helloworld.ThirdLevelType, java.util.List<Type>> extension,
        int index, Type value) {
      return (Builder) super.setExtension(extension, index, value);
    }
    public <Type> Builder addExtension(
        com.google.protobuf.GeneratedMessage.GeneratedExtension<
            io.grpc.examples.helloworld.ThirdLevelType, java.util.List<Type>> extension,
        Type value) {
      return (Builder) super.addExtension(extension, value);
    }
    public <Type> Builder clearExtension(
        com.google.protobuf.GeneratedMessage.GeneratedExtension<
            io.grpc.examples.helloworld.ThirdLevelType, ?> extension) {
      return (Builder) super.clearExtension(extension);
    }
    public Builder mergeFrom(com.google.protobuf.Message other) {
      if (other instanceof io.grpc.examples.helloworld.ThirdLevelType) {
        return mergeFrom((io.grpc.examples.helloworld.ThirdLevelType)other);
      } else {
        super.mergeFrom(other);
        return this;
      }
    }

    public Builder mergeFrom(io.grpc.examples.helloworld.ThirdLevelType other) {
      if (other == io.grpc.examples.helloworld.ThirdLevelType.getDefaultInstance()) return this;
      this.mergeExtensionFields(other);
      this.mergeUnknownFields(other.unknownFields);
      onChanged();
      return this;
    }

    public final boolean isInitialized() {
      if (!extensionsAreInitialized()) {
        return false;
      }
      return true;
    }

    public Builder mergeFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws java.io.IOException {
      io.grpc.examples.helloworld.ThirdLevelType parsedMessage = null;
      try {
        parsedMessage = PARSER.parsePartialFrom(input, extensionRegistry);
      } catch (com.google.protobuf.InvalidProtocolBufferException e) {
        parsedMessage = (io.grpc.examples.helloworld.ThirdLevelType) e.getUnfinishedMessage();
        throw e.unwrapIOException();
      } finally {
        if (parsedMessage != null) {
          mergeFrom(parsedMessage);
        }
      }
      return this;
    }
    public final Builder setUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.setUnknownFields(unknownFields);
    }

    public final Builder mergeUnknownFields(
        final com.google.protobuf.UnknownFieldSet unknownFields) {
      return super.mergeUnknownFields(unknownFields);
    }


    // @@protoc_insertion_point(builder_scope:helloworld.ThirdLevelType)
  }

  // @@protoc_insertion_point(class_scope:helloworld.ThirdLevelType)
  private static final io.grpc.examples.helloworld.ThirdLevelType DEFAULT_INSTANCE;
  static {
    DEFAULT_INSTANCE = new io.grpc.examples.helloworld.ThirdLevelType();
  }

  public static io.grpc.examples.helloworld.ThirdLevelType getDefaultInstance() {
    return DEFAULT_INSTANCE;
  }

  @java.lang.Deprecated public static final com.google.protobuf.Parser<ThirdLevelType>
      PARSER = new com.google.protobuf.AbstractParser<ThirdLevelType>() {
    public ThirdLevelType parsePartialFrom(
        com.google.protobuf.CodedInputStream input,
        com.google.protobuf.ExtensionRegistryLite extensionRegistry)
        throws com.google.protobuf.InvalidProtocolBufferException {
        return new ThirdLevelType(input, extensionRegistry);
    }
  };

  public static com.google.protobuf.Parser<ThirdLevelType> parser() {
    return PARSER;
  }

  @java.lang.Override
  public com.google.protobuf.Parser<ThirdLevelType> getParserForType() {
    return PARSER;
  }

  public io.grpc.examples.helloworld.ThirdLevelType getDefaultInstanceForType() {
    return DEFAULT_INSTANCE;
  }

}

