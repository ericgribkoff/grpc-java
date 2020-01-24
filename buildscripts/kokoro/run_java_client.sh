#!/bin/bash

SERVICE_NAME=grpc-test
GRPC_PORT=50051

set -ex
sudo apt update
sudo apt install -y git default-jdk
git clone https://github.com/grpc/grpc-java.git
pushd grpc-java
pushd interop-testing
../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
popd
popd

GRPC_XDS_BOOTSTRAP=bootstrap.json nohup grpc-java/interop-testing/build/install/grpc-interop-testing/bin/xds-test-client --server=xds-experimental:///${SERVICE_NAME}:${GRPC_PORT} --stats_port=50052 --qps=5 1>/dev/null &
