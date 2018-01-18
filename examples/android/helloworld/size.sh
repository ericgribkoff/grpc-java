#!/bin/bash

set -e
set -x

export ANDROID_HOME=/usr/local/google/home/ericgribkoff/Android/Sdk

git clone -b disabled_minify https://github.com/ericgribkoff/grpc-java

cd grpc-java/
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r postBytes postKilobytes postMegabytes < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r postMethods postFields postClasses < <(sed -n 2p app/build/outputs/dexcount/release.csv)

cd ../../../
git checkout examples_with_minify_apk_size
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r preBytes preKilobytes preMegabytes < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r preMethods preFields preClasses < <(sed -n 2p app/build/outputs/dexcount/release.csv)

bytesDelta=$((postBytes-preBytes))
methodsDelta=$((postMethods-preMethods))
fieldsDelta=$((postFields-preFields))

echo "Original release APK size in bytes: $preBytes"
echo "Original release method count: $preMethods"
echo "Original release field count: $preFields"
echo "New release APK size in bytes: $postBytes (delta: $bytesDelta)"
echo "New release method count: $postMethods (delta: $methodsDelta)"
echo "New release field count: $postFields (delta: $fieldsDelta)"

# Kororo URLs are in the form "grpc/job/macos/job/master/job/grpc_build_artifacts"
KOKORO_JOB_PATH=$(echo "${KOKORO_JOB_NAME}" | sed "s|/|/job/|g")

cd ../../../
mkdir -p reports
echo '<html><head></head><body>' > reports/kokoro_index.html
echo '<h1>'${KOKORO_JOB_NAME}', build '#${KOKORO_BUILD_NUMBER}'</h1>' >> reports/kokoro_index.html
echo '<h2><a href="https://kokoro2.corp.google.com/job/'${KOKORO_JOB_PATH}'/'${KOKORO_BUILD_NUMBER}'/">Kokoro build dashboard (internal only)</a></h2>' >> reports/kokoro_index.html
echo '<h2><a href="https://sponge.corp.google.com/invocation?id='${KOKORO_BUILD_ID}'&searchFor=">Test result dashboard (internal only)</a></h2>' >> reports/kokoro_index.html
echo "<p>New Helloworld release APK size in bytes: $postBytes (delta: $bytesDelta)</p>" >> reports/kokoro_index.html
echo "<p>New Helloworld release method count: $postMethods (delta: $methodsDelta)</p>" >> reports/kokoro_index.html
echo "<p>New Helloworld release field count: $postFields (delta: $fieldsDelta)</p>" >> reports/kokoro_index.html
echo '</body></html>' >> reports/kokoro_index.html

echo 'Created reports/kokoro_index.html report index'

