#!/usr/bin/env python3
"""Prepare private local account demo configuration; does not start any services."""
import argparse
import os
from pathlib import Path
import secrets
import stat
import sys

API_ROOT = Path(__file__).resolve().parents[2]
SECRET_NAMES = ('spring.datasource.password', 'spring.flyway.password', 'app.local-test.seed-password')


def port_number(value):
    try:
        number = int(value)
    except (TypeError, ValueError):
        raise ValueError('Ports must be integers from 1 through 65535') from None
    if isinstance(value, bool) or str(number) != str(value) or not 1 <= number <= 65535:
        raise ValueError('Ports must be integers from 1 through 65535')
    return number


def safe_path(value):
    path = Path(os.path.abspath(os.path.expanduser(str(value))))
    if len(str(path)) > 1024 or any(ord(char) < 32 or ord(char) == 127 for char in str(path)):
        raise ValueError('Output paths must be bounded and contain no control characters')
    return path


def validate_directory_chain(path):
    for item in reversed((path, *path.parents)):
        try:
            mode = item.lstat().st_mode
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(mode) or not stat.S_ISDIR(mode):
            raise ValueError('Configuration directories must be real directories, never symlinks')


def ensure_directory(path):
    validate_directory_chain(path)
    missing = []
    current = path
    while not current.exists():
        missing.append(current)
        current = current.parent
    for item in reversed(missing):
        item.mkdir(mode=0o700)
    validate_directory_chain(path)
    path.chmod(0o700)


def read_existing(path):
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return None
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError('Configuration and secret files must be regular files, never symlinks')
    if metadata.st_size > 1024 * 1024:
        raise ValueError('An existing configuration or secret file exceeds the size limit')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as handle:
        return handle.read(1024 * 1024 + 1)


def write_new(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as handle:
        handle.write(value)


def property_value(value):
    return str(value).replace('\\', '\\\\').replace(' ', '\\ ').replace(':', '\\:').replace('=', '\\=').replace('#', '\\#').replace('!', '\\!')


def prepare(directory=None, port=8081, database_port=5432):
    port = port_number(port)
    database_port = port_number(database_port)
    directory = safe_path(directory if directory is not None else API_ROOT / '.local/accounts')
    secret_directory = directory / 'secrets'
    config_path = directory / 'application.properties'
    validate_directory_chain(secret_directory)
    settings = [
        ('spring.profiles.active', 'local-accounts'),
        ('spring.datasource.url', f'jdbc:postgresql://127.0.0.1:{database_port}/lookahead_accounts'),
        ('spring.datasource.username', 'lookahead_app'),
        ('LOOKAHEAD_SECRETS_DIRECTORY', str(secret_directory) + '/'),
        ('spring.config.import', 'configtree:' + str(secret_directory) + '/'),
        ('app.accounts.catalog-path', str(API_ROOT / 'src/test/resources/accounts/catalog.json')),
        ('app.local-test.seed-enabled', 'true'),
        ('server.address', '127.0.0.1'),
        ('server.port', str(port)),
    ]
    config = ''.join(name + '=' + property_value(value) + '\n' for name, value in settings).encode('utf-8')
    existing_config = read_existing(config_path)
    if existing_config is not None and existing_config != config:
        raise ValueError('Existing application.properties has different settings; choose a new directory or review it manually')
    existing_secrets = {}
    for name in SECRET_NAMES:
        value = read_existing(secret_directory / name)
        if value is not None and not value.strip():
            raise ValueError('An existing secret is empty; restore it explicitly before preparing the demo')
        existing_secrets[name] = value
    # Validate every existing file before creating anything or changing permissions.
    ensure_directory(directory)
    ensure_directory(secret_directory)
    for name, value in existing_secrets.items():
        path = secret_directory / name
        if value is None:
            write_new(path, (secrets.token_urlsafe(32) + '\n').encode('ascii'))
        path.chmod(0o600, follow_symlinks=False)
    if existing_config is None:
        write_new(config_path, config)
    config_path.chmod(0o600, follow_symlinks=False)
    return config_path, secret_directory


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--port', default='8081')
    parser.add_argument('--database-port', default='5432')
    args = parser.parse_args(argv)
    try:
        config, secret_directory = prepare(args.directory, args.port, args.database_port)
    except ValueError as error:
        print('Cannot prepare local demo: ' + str(error), file=sys.stderr)
        return 2
    except OSError:
        print('Cannot prepare local demo: filesystem operation failed; inspect directory permissions and existing files', file=sys.stderr)
        return 2
    print('Configuration: ' + str(config))
    print('Secret directory: ' + str(secret_directory))
    print('Synthetic usernames: learner01 through learner10')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
