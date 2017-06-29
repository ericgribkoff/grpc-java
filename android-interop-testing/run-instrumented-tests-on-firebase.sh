#!/bin/bash
git clone --branch android_interop_docker https://github.com/ericgribkoff/grpc-java.git
cd grpc-java/docker/android-interop-testing
docker build -t android_interop_test .

export SERVICE_KEY=~/android-interopsServiceAccountKey.json
docker run --interactive \
  --volume=$SERVICE_KEY:/serviceAccountKey.json:ro \
  android_interop_test \
      gcloud auth activate-service-account --key-file="/serviceAccountKey.json" && \
      gcloud config set project grpc-testing && \
      gcloud firebase test android run \
        --type instrumentation \
        --app /grpc-java/android-interop-testing/app/build/outputs/apk/app-debug.apk \
        --test /grpc-java/android-interop-testing/app/build/outputs/apk/app-debug-androidTest.apk \
        --device model=Nexus6,version=21,locale=en,orientation=portrait
