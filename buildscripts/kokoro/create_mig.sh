#!/bin/bash

set -ex

INSTANCE_GROUP=grpc-td-server-ig
INSTANCE_SIZE=4
SERVICE_NAME=grpc-test
GRPC_PORT=50051

gcloud compute instance-templates create grpc-td-template \
  --scopes=https://www.googleapis.com/auth/cloud-platform \
  --tags=grpc-td-tag \
  --image-family=debian-9 \
  --image-project=debian-cloud \
  --metadata=startup-script="#!/bin/bash

sudo apt update
sudo apt install -y git default-jdk
mkdir java_server
pushd java_server
git clone https://github.com/grpc/grpc-java.git
pushd grpc-java
pushd interop-testing
../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
 
nohup build/install/grpc-interop-testing/bin/xds-test-server --port=${GRPC_PORT} 1>/dev/null &"


gcloud compute instance-groups managed create ${INSTANCE_GROUP} \
    --zone us-central1-a --size=${INSTANCE_SIZE} --template=grpc-td-template

gcloud compute instance-groups managed set-named-ports ${INSTANCE_GROUP} \
    --zone us-central1-a \
    --named-ports=grpc:${GRPC_PORT}

gcloud compute health-checks create tcp grpc-td-health-check --port-name=grpc

gcloud compute firewall-rules create fw-allow-grpc-td-health-check \
  --action ALLOW \
  --direction INGRESS \
  --source-ranges 35.191.0.0/16,130.211.0.0/22 \
  --target-tags grpc-td-tag \
  --rules tcp

gcloud compute backend-services create grpc-td-service \
    --global \
    --load-balancing-scheme=INTERNAL_SELF_MANAGED \
    --health-checks grpc-td-health-check \
    --port-name=grpc \
    --protocol=HTTP2

gcloud compute backend-services add-backend grpc-td-service \
    --instance-group ${INSTANCE_GROUP} \
    --instance-group-zone us-central1-a \
    --global

gcloud compute url-maps create grpc-td-url-map \
   --default-service grpc-td-service
gcloud compute url-maps add-path-matcher grpc-td-url-map \
   --default-service grpc-td-service --path-matcher-name grpc-td-path-matcher
gcloud compute url-maps add-host-rule grpc-td-url-map --hosts ${SERVICE_NAME} \
   --path-matcher-name grpc-td-path-matcher
gcloud compute target-http-proxies create grpc-td-proxy \
   --url-map grpc-td-url-map

gcloud compute forwarding-rules create grpc-td-forwarding-rule \
   --global \
   --load-balancing-scheme=INTERNAL_SELF_MANAGED \
   --address=0.0.0.0 \
   --target-http-proxy=grpc-td-proxy \
   --ports ${GRPC_PORT} \
   --network default
