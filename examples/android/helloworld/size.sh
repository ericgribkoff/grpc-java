#!/bin/bash

set -e
set -x

export ANDROID_HOME=/usr/local/google/home/ericgribkoff/Android/Sdk

git clone -b examples_with_minify_apk_size https://github.com/ericgribkoff/grpc-java
cd grpc-java/
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r preBytes preKilobytes preMegabytes < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r preMethods preFields preClasses < <(sed -n 2p app/build/outputs/dexcount/release.csv)
echo "Original release APK size in bytes: $preBytes"
echo "Original release method count: $preMethods"
echo "Original release field count: $preFields"

cd ../../../
git checkout disabled_minify
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r postBytes postKilobytes postMegabytes < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r postMethods postFields postClasses < <(sed -n 2p app/build/outputs/dexcount/release.csv)

bytesDelta=$((postBytes-preBytes))
methodsDelta=$((postMethods-preMethods))
fieldsDelta=$((postFields-preFields))

echo "New release APK size in bytes: $postBytes (delta: $bytesDelta)"
echo "New release method count: $postMethods (delta: $methodsDelta)"
echo "New release field count: $postFields (delta: $fieldsDelta)"
