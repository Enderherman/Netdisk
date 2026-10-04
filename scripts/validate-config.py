"""Read-only deployment checks; never starts Docker or any container/service."""
from pathlib import Path
import argparse
import ast
import json
import os
import re
import shutil
import subprocess
import tempfile
import textwrap

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--javac', help='Optional absolute path to a Java 17+ compiler')
parser.add_argument('--bash', help='Optional existing Bash executable for syntax-only script checks')
arguments = parser.parse_args()

scripts = Path(__file__).resolve().parent
backend = scripts.parent
frontend = backend.parent / 'NetdiskWeb'
checks = []

with tempfile.TemporaryDirectory(prefix='netdisk-deploy-check-') as temporary:
    temp = Path(temporary)
    env = temp / 'compose.env'
    example = (backend / '.env.compose.example').read_text(encoding='utf-8')
    values = {'COMPOSE_PROJECT_NAME': 'netdisk-validation',
              'MYSQL_ROOT_PASSWORD': 'validation-only-not-a-secret-root',
              'MYSQL_PASSWORD': 'validation-only-not-a-secret-app',
              'REDIS_PASSWORD': 'validation-only-not-a-secret-redis'}
    for name, value in values.items():
        example, count = re.subn(rf'^{name}=.*$', f'{name}={value}', example, flags=re.M)
        assert count == 1, f'Missing or duplicate example setting: {name}'
    env.write_text(example, encoding='utf-8')
    command = ['docker', 'compose', '--env-file', str(env), '-f', str(backend / 'compose.yaml'), 'config', '--format', 'json']
    validation_environment = os.environ.copy()
    for variable in list(validation_environment):
        if variable.startswith(('MYSQL_', 'REDIS_', 'NETDISK_', 'COMPOSE_')):
            validation_environment.pop(variable)
    config = json.loads(subprocess.check_output(command, env=validation_environment, text=True, encoding='utf-8'))
    services = config['services']
    assert set(services) == {'mysql', 'redis', 'backend', 'frontend'}
    for service in ('mysql', 'redis', 'backend'):
        assert not services[service].get('ports'), f'{service} must not publish a host port'
    assert services['frontend']['ports'][0]['host_ip'] == '127.0.0.1'
    assert services['backend']['user'] == '10001:10001'
    assert services['backend']['environment']['NETDISK_STORAGE'].endswith('/')
    assert services['backend']['read_only'] and services['frontend']['read_only']
    assert config['networks']['data']['internal'] is True
    assert services['mysql']['image'] == 'mysql:8.4'
    assert services['redis']['image'] == 'redis:7.4-alpine'
    assert services['backend']['depends_on']['mysql']['condition'] == 'service_healthy'
    assert services['frontend']['depends_on']['backend']['condition'] == 'service_healthy'
    assert Path(services['frontend']['build']['context']).resolve() == frontend.resolve()
    assert Path(services['backend']['build']['context']).resolve() == backend.resolve()
    assert services['backend']['build']['dockerfile'] == 'Dockerfile'
    mounted = {item['target']: Path(item['source']).resolve() for item in services['mysql']['volumes'] if item['type'] == 'bind'}
    assert mounted['/opt/netdisk-schema/init.sql'] == (backend / 'sql/init.sql').resolve()
    assert mounted['/docker-entrypoint-initdb.d/010-schema.sh'] == (scripts / 'mysql/010-schema.sh').resolve()
    assert all(path.is_file() for path in mounted.values())
    checks.append('Compose JSON valid; only loopback frontend published; internal data network and non-root backend')
    checks.append('Root Docker context and relocated SQL/init-script bind mounts resolve to existing files')

    missing = subprocess.run(['docker', 'compose', '--env-file', str(backend / '.env.compose.example'), '-f', str(backend / 'compose.yaml'), 'config', '--quiet'],
                             env=validation_environment, text=True, capture_output=True)
    assert missing.returncode != 0, 'Empty example credentials must not create a runnable deployment'
    checks.append('Blank credential example is rejected instead of supplying default production passwords')

    home_compiler = Path(os.environ.get('JAVA_HOME', '')) / 'bin' / ('javac.exe' if os.name == 'nt' else 'javac')
    java = arguments.javac or (str(home_compiler) if home_compiler.is_file() else shutil.which('javac'))
    if java:
        subprocess.run([java, '--release', '17', '-d', str(temp), str(scripts / 'Healthcheck.java')], check=True)
        assert (temp / 'Healthcheck.class').is_file()
        checks.append('Java 17 health probe compiled (not executed against a service)')
    else:
        checks.append('Java health probe compilation skipped: javac not available')

    schema = (backend / 'sql/init.sql').read_text(encoding='utf-8')
    assert 'session_version' in schema and 'purpose' in schema and 'varchar(255)' in schema
    init = (scripts / 'mysql/010-schema.sh').read_text(encoding='utf-8')
    assert b'\r\n' not in (scripts / 'mysql/010-schema.sh').read_bytes(), 'Container init script must retain LF line endings'
    assert '/opt/netdisk-schema/init.sql' in init and 'create database netdisk' in init
    migration = (backend / 'sql/migrations/20261004_account_security.sql').read_text(encoding='utf-8')
    assert 'ADD COLUMN session_version' in migration and 'ADD COLUMN purpose' in migration
    checks.append('Fresh schema includes current account fields; old schema migration remains an explicit upgrade step')

    nginx = (frontend / 'nginx/nginx.conf').read_text(encoding='utf-8')
    proxy = (frontend / 'nginx/proxy-api.conf').read_text(encoding='utf-8')
    for rule in ['proxy_request_buffering off;', 'proxy_buffering off;', 'proxy_set_header Range $http_range;',
                 'proxy_set_header If-Range $http_if_range;', 'proxy_pass $netdisk_backend;',
                 'client_max_body_size 128m;', 'try_files $uri $uri/ /index.html;', 'application/javascript mjs;',
                 'proxy_set_header Host $http_host;', 'proxy_set_header X-Forwarded-For $remote_addr;']:
        assert rule in nginx + proxy, f'Missing required Nginx behavior: {rule}'
    assert nginx.count('{') == nginx.count('}')
    log_format = re.search(r'log_format netdisk_minimal.*?;', nginx, re.S).group(0)
    assert not {'$request', '$request_uri', '$args', '$http_referer'} & set(re.findall(r'\$[A-Za-z_][A-Za-z_0-9]*', log_format))
    callback = re.search(r'location = /api/qqlogin/callback\s*\{([^}]+)\}', nginx).group(1)
    for rule in ('access_log off;', 'include /etc/nginx/proxy-api.conf;', 'add_header Referrer-Policy no-referrer always;'):
        assert rule in callback
    assert 'error_log /dev/null;' in proxy
    for route in ('file/download', 'file/downloadZip', 'showShare/download', 'admin/download'):
        assert f'~^/api/{route}/ "/api/{route}/[redacted]";' in nginx
    assert '$upstream_status' in log_format and '$netdisk_log_uri' in log_format
    assert nginx.count('include /etc/nginx/proxy-api.conf;') == 2
    checks.append('Static Nginx proxy/streaming/Range/SPA/Worker MIME invariants present; nginx -t not executed')
    checks.append('Query/referrer-free access; four download-token routes redacted; QQ access and API upstream error logs suppressed')

    for dockerfile in (backend / 'Dockerfile', frontend / 'Dockerfile'):
        assert dockerfile.is_file() and 'HEALTHCHECK' in dockerfile.read_text(encoding='utf-8')
    assert '**' in (backend / '.dockerignore').read_text(encoding='utf-8')
    assert '**' in (frontend / '.dockerignore').read_text(encoding='utf-8')
    checks.append('Both images have health probes and allowlisted build contexts')

    if arguments.bash:
        subprocess.run([arguments.bash, '-n', str(scripts / 'mysql/010-schema.sh')], check=True)
        block_count = 0
        for workflow in (backend / '.github/workflows/docker-build.yml', frontend / '.github/workflows/docker-build.yml'):
            lines = workflow.read_text(encoding='utf-8').splitlines()
            assert '    runs-on: ubuntu-24.04' in lines and '  contents: read' in lines
            for index, line in enumerate(lines):
                if line.strip() != 'run: |':
                    continue
                body = []
                for nested in lines[index + 1:]:
                    if nested and not nested.startswith('          '):
                        break
                    body.append(nested)
                script = textwrap.dedent('\n'.join(body)) + '\n'
                target = temp / f'ci-block-{block_count}.sh'
                target.write_text(script, encoding='utf-8')
                subprocess.run([arguments.bash, '-n', str(target)], check=True)
                if "python3 - <<'PY'\n" in script:
                    python = script.split("python3 - <<'PY'\n", 1)[1].split('\nPY', 1)[0]
                    ast.parse(python)
                block_count += 1
        checks.append(f'Init shell and {block_count} CI shell blocks pass syntax checks; JAR inspection Python parses')

daemon = subprocess.run(['docker', 'version', '--format', '{{.Server.Version}}'], text=True, capture_output=True)
print(json.dumps({'checks': checks, 'docker_daemon_available': daemon.returncode == 0,
                  'images_built': False, 'containers_started': False, 'nginx_runtime_validated': False}, ensure_ascii=False, indent=2))
