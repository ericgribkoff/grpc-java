#!/bin/bash
 
set -ex

gcloud -q compute forwarding-rules delete grpc-td-forwarding-rule --global
gcloud -q compute target-http-proxies delete grpc-td-proxy
gcloud -q compute url-maps delete grpc-td-url-map 
gcloud -q compute backend-services delete grpc-td-service --global
gcloud -q compute firewall-rules delete fw-allow-grpc-td-health-check
gcloud -q compute health-checks delete grpc-td-health-check
gcloud -q compute instance-groups managed delete grpc-td-server-ig \
  --zone us-central1-a
gcloud -q compute instance-templates delete grpc-td-template
