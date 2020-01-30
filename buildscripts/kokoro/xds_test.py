#!/usr/bin/env python
# Copyright 2020 gRPC authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Run xDS integration tests."""

from __future__ import print_function

import argparse
import googleapiclient.discovery
import grpc
import subprocess
import sys
import time

import test_pb2
import test_pb2_grpc

_GET_BACKENDS = 'gcloud compute instance-groups managed list-instances grpc-td-server-ig --zone=us-central1-a --format=value(NAME)'.split(' ')

argp = argparse.ArgumentParser(description='Run xds test.')
argp.add_argument(
    '--test_case',
    default=None,
    type=str)
argp.add_argument(
    '--clean_up',
    default=False,
    type=bool)
args = argp.parse_args()

def GetBackends():
    res = subprocess.run(_GET_BACKENDS, stdout=subprocess.PIPE, check=True, universal_newlines=True)
    backends = res.stdout.split('\n')[:-1]
    return backends

def GetClientStats(num_rpcs, timeout_sec):
    with grpc.insecure_channel('localhost:50052') as channel:
      stub = test_pb2_grpc.LoadBalancerStatsServiceStub(channel)
      request = test_pb2.LoadBalancerStatsRequest()
      request.num_rpcs = num_rpcs
      request.timeout_sec = timeout_sec
      try:
        response = stub.GetClientStats(request, wait_for_ready=True)
        print('Invoked massage client stats RPC: %s', response)
        return response
      except grpc.RpcError as rpc_error:
        print('Failed to get massage client stats, aborting test.')


def WaitUntilOnlyGivenBackendsReceiveLoad(backends, timeout_sec):
    start_time = time.time()
    error_msg = None
    while time.time() - start_time <= timeout_sec:
      error_msg = None
      stats = GetClientStats(max(len(backends), 1), timeout_sec)
      rpcs_by_peer = stats.rpcs_by_peer
      for backend in backends:
        if backend not in rpcs_by_peer:
          error_msg = 'Backend %s did not receive load' % (backend,)
          break
      if not error_msg and len(rpcs_by_peer) > len(backends):
        error_msg = 'Unexpected backend received load: %s' % (rpcs_by_peer,)
      if not error_msg:
        print('wait successful')
        return
    print('test failed', error_msg)
    sys.exit(1)

def PingPong():
    timeout_sec = 10
    backends = GetBackends()
    start_time = time.time()
    error_msg = None
    while time.time() - start_time <= timeout_sec:
      error_msg = None
      stats = GetClientStats(50, timeout_sec)
      rpcs_by_peer = stats.rpcs_by_peer
      for backend in backends:
        if backend not in rpcs_by_peer:
          error_msg = 'Backend %s did not receive load' % (backend,)
          break
      if not error_msg and len(rpcs_by_peer) > len(backends):
        error_msg = 'Unexpected backend received load: %s' % (rpcs_by_peer,)
      if not error_msg:
        print('test passed')
        sys.exit(0) 
    print('test failed', error_msg)
    sys.exit(1)

def RoundRobin():
    timeout_sec = 10
    threshold = 1
    backends = GetBackends()
    WaitUntilOnlyGivenBackendsReceiveLoad(backends, timeout_sec)
    stats = GetClientStats(50, timeout_sec)
    requests_received = [stats.rpcs_by_peer[x] for x in stats.rpcs_by_peer]
    total_requests_received = sum([stats.rpcs_by_peer[x] for x in stats.rpcs_by_peer])
    expected_requests = total_requests_received / len(backends)
    for backend in backends:
      if abs(stats.rpcs_by_peer[backend] - expected_requests) > threshold:
        print('outside of threshold for ', backend, stats)
        sys.exit(1)

def BackendsRestartTest():
    timeout_sec = 10
    backends = GetBackends()
    WaitUntilOnlyGivenBackendsReceiveLoad(backends, timeout_sec)
    stats = GetClientStats(50, timeout_sec)
    prior_distribution = stats.rpcs_by_peer

    _STOP_TEMPLATE = 'gcloud compute instances stop %s --zone=us-central1-a'
    for backend in backends:
        res = subprocess.run((_STOP_TEMPLATE % backend).split(' '), stdout=subprocess.PIPE, check=True)
        print(res)

    timeout_sec=120
    WaitUntilOnlyGivenBackendsReceiveLoad([], timeout_sec)

    _START_TEMPLATE = 'gcloud compute instances start %s --zone=us-central1-a'
    for backend in backends:
        res = subprocess.run((_START_TEMPLATE % backend).split(' '), stdout=subprocess.PIPE, check=True)
        print(res)

    timeout_sec=600
    WaitUntilOnlyGivenBackendsReceiveLoad(backends, timeout_sec)

    timeout_sec = 10
    threshold = 1
    stats = GetClientStats(50, timeout_sec)
    for backend in backends:
      if abs(stats.rpcs_by_peer[backend] - prior_distribution[backend]) > threshold:
        print('outside of threshold for ', backend, stats.rpcs_by_peer[backend], prior_distribution[backend])
        sys.exit(1)

def create_instance_template(compute, name, grpc_port, project_id):
  config = {
        'name': name,
        'properties': {
          'tags': {
            'items': ['grpc-td-tag']
          },
        
          'machineType': 'n1-standard-1',
          # Allow the instance to access cloud storage and logging.
          'serviceAccounts': [{
              'email': 'default',
              'scopes': [
                  'https://www.googleapis.com/auth/cloud-platform',
              ]
          }],
          #'canIpForward': True,
          'networkInterfaces': [
          {
            'accessConfigs': [
              {'type': 'ONE_TO_ONE_NAT'}
            ],
            'network': 'global/networks/default'
          }],

          'disks': [
            {
              'boot': True,
              'initializeParams': {
                'sourceImage': 'projects/debian-cloud/global/images/family/debian-9'
              }
            }
          ],



          # Metadata is readable from the instance and allows you to
          # pass configuration from deployment scripts to instances.
          'metadata': {
              'items': [{
                  # Startup script is automatically executed by the
                  # instance upon startup.
                  'key': 'startup-script',
                  'value': """#!/bin/bash

sudo apt update
sudo apt install -y git default-jdk
mkdir java_server
pushd java_server
git clone https://github.com/grpc/grpc-java.git
pushd grpc-java
pushd interop-testing
../gradlew installDist -x test -PskipCodegen=true -PskipAndroid=true
 
nohup build/install/grpc-interop-testing/bin/xds-test-server --port=%d 1>/dev/null &""" % grpc_port
              }]
          }
      }
  }

  result = compute.instanceTemplates().insert(project=project_id, body=config).execute()
  return result

def create_instance_group(compute, name, size, grpc_port, template_url, project_id, zone):
  config = {
        'name': name,
        'instanceTemplate': template_url,
        'targetSize': size,
        'namedPorts': [
          {
            'name': 'grpc',
            'port': grpc_port
          }
        ]
  }

  result = compute.instanceGroupManagers().insert(project=project_id, zone=zone, body=config).execute()
  return result

def create_health_check(compute, name, project_id):
  config = {
        'name': name,
        'type': 'TCP',
        'tcpHealthCheck': {
          'portName': 'grpc'
        }
  }
  result = compute.healthChecks().insert(project=project_id, body=config).execute()
  return result

def create_health_check_firewall_rule(compute, name, project_id):
  config = {
        'name': name,
        'direction': 'INGRESS',
        'allowed': [
          {
            'IPProtocol': 'tcp'
          }
        ],
        'sourceRanges': [
          '35.191.0.0/16',
          '130.211.0.0/22'
        ],
        'targetTags': ['grpc-td-tag'],
  }
  result = compute.firewalls().insert(project=project_id, body=config).execute()
  return result

def create_backend_service(compute, name, instance_group, health_check, project_id):
  config = {
        'name': name,
        'loadBalancingScheme': 'INTERNAL_SELF_MANAGED',
        'healthChecks': [
          health_check
        ],
        'portName': 'grpc',
        'protocol': 'HTTP2',
        'backends': [
          {
            'group': instance_group,
          }
        ]
  }
  result = compute.backendServices().insert(project=project_id, body=config).execute()
  return result

def create_url_maps(compute, name, backend_service_url, host_name, project_id):
  path_matcher_name = 'path-matcher'
  config = {
        'name': name,
        'defaultService': backend_service_url,  
        'pathMatchers': [{
          'name': path_matcher_name,
          'defaultService': backend_service_url,
        }],
        'hostRules': [{
          'hosts': [host_name],
          'pathMatcher': path_matcher_name
        }]
  }
  result = compute.urlMaps().insert(project=project_id, body=config).execute()
  return result

def create_target_http_proxies(compute, name, url_map_url, project_id):
  config = {
    'name': name,
    'url_map': url_map_url,
  }
  result = compute.targetHttpProxies().insert(project=project_id, body=config).execute()
  return result

def create_global_forwarding_rules(compute, name, grpc_port, target_http_proxy_url, project_id):
  config = {
    'name': name,
    'loadBalancingScheme': 'INTERNAL_SELF_MANAGED',
    'portRange': str(grpc_port),
    'IPAddress': '0.0.0.0',
    # 'IPProtocol': 'TCP',
    'target': target_http_proxy_url,
  }
  result = compute.globalForwardingRules().insert(project=project_id, body=config).execute()
  return result

def wait_for_global_operation(compute, project, operation):
    print('Waiting for operation to finish...')
    while True:
        result = compute.globalOperations().get(
            project=project,
            operation=operation).execute()

        if result['status'] == 'DONE':
            print("done.")
            if 'error' in result:
                raise Exception(result['error'])
            return result

        time.sleep(1)

def wait_for_zone_operation(compute, project, zone, operation):
    print('Waiting for operation to finish...')
    while True:
        result = compute.zoneOperations().get(
            project=project,
            zone=zone,
            operation=operation).execute()

        if result['status'] == 'DONE':
            print("done.")
            if 'error' in result:
                raise Exception(result['error'])
            return result

        time.sleep(1)

PROJECT_ID = '635199449855'  # has to be string
ZONE = 'us-central1-a'
TEMPLATE_NAME = 'test-template'
INSTANCE_GROUP_NAME = 'test-ig'
INSTANCE_GROUP_SIZE = 2
HEALTH_CHECK_NAME = 'test-hc'
FIREWALL_RULE_NAME = 'test-fw-rule'
BACKEND_SERVICE_NAME = 'test-backend-service'
URL_MAP_NAME = 'test-map'
HOST_NAME = 'grpc-test'
TARGET_PROXY_NAME = 'test-target-proxy'
FORWARDING_RULE_NAME = 'test-forwarding-rule2'
GRPC_PORT = 50051

compute = googleapiclient.discovery.build('compute', 'v1')

try:
  result = create_instance_template(compute, TEMPLATE_NAME, GRPC_PORT, PROJECT_ID)
  print(result)
  template_url = result['targetLink']
  wait_for_global_operation(compute, PROJECT_ID, result['name'])

  result = create_instance_group(compute, INSTANCE_GROUP_NAME, INSTANCE_GROUP_SIZE, GRPC_PORT, template_url, PROJECT_ID, ZONE)
  print(result)
  wait_for_zone_operation(compute, PROJECT_ID, ZONE, result['name'])
  result = compute.instanceGroupManagers().get(project=PROJECT_ID, zone=ZONE, instanceGroupManager=INSTANCE_GROUP_NAME).execute()
  instance_group_url = result['instanceGroup']

  result = create_health_check(compute, HEALTH_CHECK_NAME, PROJECT_ID)
  health_check_url = result['targetLink']
  print(result)
  wait_for_global_operation(compute, PROJECT_ID, result['name'])

  result = create_health_check_firewall_rule(compute, FIREWALL_RULE_NAME, PROJECT_ID)
  print(result)
  wait_for_global_operation(compute, PROJECT_ID, result['name'])

  result = create_backend_service(compute, BACKEND_SERVICE_NAME, instance_group_url, health_check_url, PROJECT_ID)
  print(result)
  backend_service_url = result['targetLink']
  wait_for_global_operation(compute, PROJECT_ID, result['name'])

  result = create_url_maps(compute, URL_MAP_NAME, backend_service_url, HOST_NAME, PROJECT_ID)
  print(result)
  url_map_url = result['targetLink']
  wait_for_global_operation(compute, PROJECT_ID, result['name'])

  result = create_target_http_proxies(compute, TARGET_PROXY_NAME, url_map_url, PROJECT_ID)
  print(result)
  target_http_proxy_url = result['targetLink']
  wait_for_global_operation(compute, PROJECT_ID, result['name'])
  
  result = create_global_forwarding_rules(compute, FORWARDING_RULE_NAME, GRPC_PORT, target_http_proxy_url, PROJECT_ID)
  print(result)
  wait_for_global_operation(compute, PROJECT_ID, result['name'])
except googleapiclient.errors.HttpError as http_error:
  print('failed to set up backends', http_error)
finally:
  if args.clean_up:
    try:
      result = compute.globalForwardingRules().delete(project=PROJECT_ID, forwardingRule=FORWARDING_RULE_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
     print('delete failed', http_error)

    try:
      result = compute.targetHttpProxies().delete(project=PROJECT_ID, targetHttpProxy=TARGET_PROXY_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
     print('delete failed', http_error)

    try:
      result = compute.urlMaps().delete(project=PROJECT_ID, urlMap=URL_MAP_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
     print('delete failed', http_error)

    try:
      result = compute.backendServices().delete(project=PROJECT_ID, backendService=BACKEND_SERVICE_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
      print('delete failed', http_error)

    try:
      result = compute.firewalls().delete(project=PROJECT_ID, firewall=FIREWALL_RULE_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
      print('delete failed', http_error)

    try:
      result = compute.healthChecks().delete(project=PROJECT_ID, healthCheck=HEALTH_CHECK_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
      print('delete failed', http_error)

    try:
      result = compute.instanceGroupManagers().delete(project=PROJECT_ID, zone=ZONE, instanceGroupManager=INSTANCE_GROUP_NAME).execute()
      wait_for_zone_operation(compute, PROJECT_ID, ZONE, result['name'])
    except googleapiclient.errors.HttpError as http_error:
      print('delete failed', http_error)

    try:
      result = compute.instanceTemplates().delete(project=PROJECT_ID, instanceTemplate=TEMPLATE_NAME).execute()
      wait_for_global_operation(compute, PROJECT_ID, result['name'])
    except googleapiclient.errors.HttpError as http_error:
     print('delete failed', http_error)


# TODO: Support test_case=all

# if args.test_case == "ping_pong":
#     PingPong()
# elif args.test_case == "round_robin":
#     RoundRobin()
# elif args.test_case == "backends_restart":
#     BackendsRestart()
# else:
#     print("Unknown test case")
#     sys.exit(1)
