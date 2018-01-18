#!/bin/bash

set -e
set -x

export ANDROID_HOME=/usr/local/google/home/ericgribkoff/Android/Sdk

git clone -b disabled_minify https://github.com/ericgribkoff/grpc-java

cd grpc-java/
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r new_bytes new_kb new_mb < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r new_methods new_fields new_classes < <(sed -n 2p app/build/outputs/dexcount/release.csv)

cd ../../../
git checkout examples_with_minify_apk_size
#./gradlew install
cd examples/android/helloworld/
./gradlew build
IFS=, read -r old_bytes old_kb old_mb < <(sed -n 2p app/build/outputs/apksize/release/release.csv)
IFS=, read -r old_methods old_fields old_classes < <(sed -n 2p app/build/outputs/dexcount/release.csv)

bytesDelta=$((new_bytes-old_bytes))
methodsDelta=$((new_methods-old_methods))
fieldsDelta=$((new_fields-old_fields))

echo "Original release APK size in bytes: $old_bytes"
echo "Original release method count: $old_methods"
echo "Original release field count: $old_fields"
echo "New release APK size in bytes: $new_bytes (delta: $bytesDelta)"
echo "New release method count: $new_methods (delta: $methodsDelta)"
echo "New release field count: $new_fields (delta: $fieldsDelta)"

# Kororo URLs are in the form "grpc/job/macos/job/master/job/grpc_build_artifacts"
KOKORO_JOB_PATH=$(echo "${KOKORO_JOB_NAME}" | sed "s|/|/job/|g")

cd ../../../
mkdir -p reports
echo '<html><head></head><body>' > reports/kokoro_index.html
echo '<h1>'${KOKORO_JOB_NAME}', build '#${KOKORO_BUILD_NUMBER}'</h1>' >> reports/kokoro_index.html
echo '<h2><a href="https://kokoro2.corp.google.com/job/'${KOKORO_JOB_PATH}'/'${KOKORO_BUILD_NUMBER}'/">Kokoro build dashboard (internal only)</a></h2>' >> reports/kokoro_index.html
echo '<h2><a href="https://sponge.corp.google.com/invocation?id='${KOKORO_BUILD_ID}'&searchFor=">Test result dashboard (internal only)</a></h2>' >> reports/kokoro_index.html
echo "<p>New Helloworld release APK size in bytes: $new_bytes (delta: $bytesDelta)</p>" >> reports/kokoro_index.html
echo "<p>New Helloworld release method count: $new_methods (delta: $methodsDelta)</p>" >> reports/kokoro_index.html
echo "<p>New Helloworld release field count: $new_fields (delta: $fieldsDelta)</p>" >> reports/kokoro_index.html
echo '</body></html>' >> reports/kokoro_index.html

echo 'Created reports/kokoro_index.html report index'
