#!/bin/bash

# Builds the gRPC Android instrumented interop tests inside a docker container
# and runs them on Firebase Test Lab

DOCKER_IMAGE=android_interop_test
SERVICE_KEY=~/android-interops-service-key.json

git clone --branch android_interop_docker https://github.com/ericgribkoff/grpc-java.git
cd grpc-java/docker/android-interop-testing
docker build -t $DOCKER_IMAGE .

docker run --interactive --rm \
  --volume=$SERVICE_KEY:/serviceAccountKey.json:ro \
  $DOCKER_IMAGE \
      /bin/bash -c "gcloud auth activate-service-account --key-file=/serviceAccountKey.json; \
      gcloud config set project grpc-testing; exit 1"
#      gcloud firebase test android run \
#        --type instrumentation \
#        --app /grpc-java/android-interop-testing/app/build/outputs/apk/app-debug.apk \
#        --test /grpc-java/android-interop-testing/app/build/outputs/apk/app-debug-androidTest.apk \
#        --device model=Nexus6,version=21,locale=en,orientation=portrait"
TEST_STATUS=$?
docker rmi $DOCKER_IMAGE
exit $TEST_STATUS
