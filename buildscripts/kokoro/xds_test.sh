#!/bin/bash

set -ex

./create_mig.sh

GCP_ID=$(gcloud projects describe ericgribkoff-grpcz --format='value(projectNumber)')

sed -i "s/TO_REPLACE/${GCP_ID}/" bootstrap.json
./run_java_client.sh

sudo apt install python3-pip
python3 -m pip install grpcio grpcio-tools

python3 xds_test.py --test_case=ping_pong
python3 xds_test.py --test_case=round_robin
python3 xds_test.py --test_case=backends_restart

./delete_mig.sh
