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


if args.test_case == "ping_pong":
    PingPong()
elif args.test_case == "round_robin":
    RoundRobin()
elif args.test_case == "backends_restart":
    BackendsRestart()
else:
    print("Unknown test case")
    sys.exit(1)
