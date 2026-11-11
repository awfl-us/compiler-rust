#!/usr/bin/env python3
"""Opt-in GCP canary of a published pure Rust workflow through Cloud Tasks -> Jobs.

Only the IAM-published NativePilot with *zero external effects* is eligible.
Uses existing project GCP credentials; never prints or persists access tokens.
This is a launcher/worker integration canary, not a substitute for the
end-user-authenticated public /workflows/execute HTTP test.
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import time
import uuid
from pathlib import Path

from google.cloud import firestore, tasks_v2
from google.oauth2.credentials import Credentials
from google.protobuf import duration_pb2

EXPECTED = [2, 3, 9, 9]
REGION = 'us-central1'
QUEUE = 'native-executions'
WORKFLOW = 'NativePilot'


def credentials():
    # The AWFL consumer receives an OAuth *access* token from its authenticated
    # broker. It must not copy a refresh token or a service-account private key.
    path = os.environ.get('CLOUDSDK_AUTH_ACCESS_TOKEN_FILE')
    if not path or not Path(path).is_file():
        raise RuntimeError('authenticated AWFL GCP token file is required')
    return Credentials(token=Path(path).read_text().strip())


def published_release(project):
    path = f'gs://{project}-native-workflows/native-workflows/active/{WORKFLOW}.json'
    release = json.loads(subprocess.check_output(['gcloud', 'storage', 'cat', path], timeout=30))
    sha = release.get('artifactSha256')
    if (release.get('workflowName') != WORKFLOW or
            release.get('protocolVersion') != 2 or
            release.get('runtimeAbi') != 3 or
            release.get('allowedOperations') != [] or
            not isinstance(sha, str) or not re.fullmatch('[a-f0-9]{64}', sha)):
        raise RuntimeError('refusing to test an effectful or unapproved pilot release')
    return sha


def create_state(db, user_id, project_id, digest):
    execution_id = 'native_' + uuid.uuid4().hex
    now = int(time.time() * 1000)
    # Mirrors NativeExecutionStore.create, including JSON serialization of
    # values that Firestore cannot store directly as nested arrays.
    doc = db.collection('nativeWorkflowExecutions').document(execution_id)
    doc.create({
        'schemaVersion': 1, 'executionId': execution_id,
        'userId': user_id, 'projectId': project_id,
        'workflowName': WORKFLOW, 'artifactSha256': digest,
        'protocolVersion': 2, 'allowedOperations': [],
        'input': None,
        'inputJson': json.dumps({'env': {'userId': user_id, 'projectId': project_id}}),
        'status': 'ready', 'epoch': 0, 'owner': None, 'leaseUntil': 0,
        'checkpoint': None, 'pendingEffect': None, 'effectCount': 0,
        'result': None, 'createdAt': now, 'updatedAt': now,
        'canary': 'compiler-rust-live-native-pilot',
    })
    return execution_id, doc


def enqueue(client, project, jobs_url, dispatch_url, task_sa, execution_id, user_id, project_id):
    origin = jobs_url.rstrip('/')
    body = json.dumps({'executionId': execution_id, 'userId': user_id,
                       'projectId': project_id}).encode('utf-8')
    task = tasks_v2.Task(
        dispatch_deadline=duration_pb2.Duration(seconds=1800),
        http_request=tasks_v2.HttpRequest(
            http_method=tasks_v2.HttpMethod.POST,
            url=dispatch_url.rstrip('/') + '/native/execute',
            headers={'Content-Type': 'application/json', 'x-project-id': project_id},
            body=body,
            oidc_token=tasks_v2.OidcToken(
                service_account_email=task_sa, audience=origin),
        ),
    )
    return client.create_task(
        request={'parent': client.queue_path(project, REGION, QUEUE), 'task': task},
        timeout=15)


def wait_for_result(doc, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        data = doc.get(timeout=10).to_dict()
        if not data:
            raise RuntimeError('canary execution disappeared')
        if data.get('status') == 'completed':
            result = json.loads(data['resultJson'])
            if result != EXPECTED:
                raise AssertionError('unexpected native Rust result')
            if data.get('artifactSha256') is None or data.get('effectCount') != 0:
                raise AssertionError('unexpected artifact or external effects')
            return data
        if data.get('status') == 'cancelled':
            raise AssertionError('canary unexpectedly cancelled')
        time.sleep(0.3)
    raise TimeoutError('native execution did not finish before the canary deadline')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true', help='Actually create and enqueue live canary tasks')
    parser.add_argument('--jobs-url', required=True, help='Canonical IAM-protected Jobs Cloud Run URL')
    parser.add_argument('--dispatch-url', help='Optional zero-traffic tagged Jobs URL; OIDC audience remains canonical')
    parser.add_argument('--count', type=int, default=1)
    parser.add_argument('--timeout', type=int, default=60)
    parser.add_argument('--duplicate-delivery', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.count <= 5 or not 5 <= args.timeout <= 180:
        parser.error('count must be 1-5 and timeout 5-180s')
    if not re.fullmatch(r'https://jobs-[a-z0-9-]+\.a\.run\.app', args.jobs_url):
        parser.error('use the canonical jobs-*.a.run.app origin, with no path')
    dispatch_url = args.dispatch_url or args.jobs_url
    if dispatch_url != args.jobs_url and dispatch_url != args.jobs_url.replace('https://jobs-', 'https://native-rust-pilot---jobs-', 1):
        parser.error('dispatch URL must be the native-rust-pilot traffic tag on the same Jobs service')
    project = os.environ.get('CLOUDSDK_CORE_PROJECT', 'awfl-us')
    user_id = os.environ.get('AWFL_USER_ID')
    project_id = os.environ.get('AWFL_PROJECT_ID')
    if not user_id or not project_id:
        raise RuntimeError('AWFL project and authenticated user context are required')
    sha = published_release(project)
    print('Canary release checked:', WORKFLOW, 'protocol=2 abi=3 effects=0', 'sha=' + sha[:12])
    if not args.execute:
        print('Dry run only; pass --execute to create GCP resources')
        return
    auth = credentials()
    db = firestore.Client(project=project, credentials=auth)
    tasks = tasks_v2.CloudTasksClient(credentials=auth)
    sa = f'awfl-native-task-invoker@{project}.iam.gserviceaccount.com'
    executions = []
    for _ in range(args.count):
        execution_id, doc = create_state(db, user_id, project_id, sha)
        try:
            queued_at = time.monotonic()
            task = enqueue(tasks, project, args.jobs_url, dispatch_url, sa, execution_id, user_id, project_id)
        except Exception:
            # No task accepted: don't leave a runnable orphan.
            doc.delete()
            raise
        executions.append((execution_id, doc, queued_at))
        print('Enqueued:', execution_id, 'task=', task.name.split('/')[-1])
    durations = []
    for execution_id, doc, queued_at in executions:
        data = wait_for_result(doc, args.timeout)
        elapsed = (time.monotonic() - queued_at) * 1000
        durations.append(elapsed)
        print('COMPLETED:', execution_id, 'result=', EXPECTED,
              'epoch=', data['epoch'], 'latency_ms=', round(elapsed))
    if args.duplicate_delivery:
        execution_id, doc, _ = executions[0]
        baseline = doc.get().to_dict()
        enqueue(tasks, project, args.jobs_url, dispatch_url, sa, execution_id, user_id, project_id)
        time.sleep(4)
        current = doc.get().to_dict()
        if current.get('status') != 'completed' or current.get('epoch') != baseline.get('epoch'):
            raise AssertionError('duplicate native delivery advanced the completed execution')
        print('Duplicate-delivery invariants verified:', execution_id)
    print('SUMMARY:',len(durations),'completed; p50_ms=',round(statistics.median(durations)),
          'max_ms=',round(max(durations)))


if __name__ == '__main__':
    try:
        main()
    except Exception as err:
        print('CANARY FAILED:',type(err).__name__,str(err)[:300],file=sys.stderr)
        raise SystemExit(1)
