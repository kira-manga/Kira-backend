#!/usr/bin/env python3
"""Linux/Python 3.11 selected-byte backup tools. No production restore authority.

Public ABI: create, verify, publish-directory. Underscore commands are plumbing
for the three reviewed shell wrappers, with the same guards enforced here.
Filesystem ownership checks assume exclusive operator custody, not hostile root.
"""

import argparse
import bisect
import contextlib
import ctypes
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import uuid
import zlib


SCHEMA = 'kira.backup-bundle.v1'
CHUNK = 1024 * 1024
MANIFEST_LIMIT = 4096
LEGACY_LIMIT = 8192
RECORD_LIMIT = 32768
HISTORY_LIMIT = 1024 * 1024
ENTRY_LIMIT = 100_000
PATH_LIMIT = 1024
DEPTH_LIMIT = 32
FILE_LIMIT = 512 * 1024 * 1024
TOTAL_LIMIT = 32 * 1024 * 1024 * 1024
METADATA_LIMIT = 64 * 1024
HEX = re.compile(r'[0-9a-f]{64}\Z')
STEM = re.compile(r'[A-Za-z0-9][A-Za-z0-9_-]{0,95}\Z')
VERSION = re.compile(r'[0-9]+(?:[._][0-9]+)*\Z')
NOFOLLOW = os.O_NOFOLLOW | os.O_CLOEXEC


class Refused(Exception):
    pass


class PublishedButUnconfirmed(Refused):
    pass


def require(condition, message='unsupported or inconsistent backup state'):
    if not condition:
        raise Refused(message)


def supported_runtime():
    require(sys.platform == 'linux' and sys.version_info >= (3, 11), 'Linux/Python 3.11+ required')


def canonical(value):
    return (json.dumps(value, ensure_ascii=True, sort_keys=True,
                       separators=(',', ':'), allow_nan=False) + '\n').encode('ascii')


def digest(value):
    return hashlib.sha256(value).hexdigest()


def pairs(values):
    result = {}
    for key, value in values:
        require(key not in result, 'duplicate JSON key')
        result[key] = value
    return result


def parse_json(raw, limit):
    require(0 < len(raw) <= limit, 'JSON size refused')
    try:
        return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Refused('invalid JSON number')))
    except (UnicodeError, ValueError, RecursionError) as failure:
        raise Refused('invalid JSON document') from failure


def keys(value, expected):
    require(type(value) is dict and set(value) == set(expected), 'unexpected record shape')


def integer(value, low, high):
    require(type(value) is int and low <= value <= high, 'integer outside supported profile')


def text_value(value, maximum):
    require(type(value) is str and 0 < len(value.encode('utf-8')) <= maximum
            and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), 'invalid text field')
    return value


def hex_value(value):
    require(type(value) is str and HEX.fullmatch(value) is not None, 'SHA-256 pin required')
    return value


def source_version(value):
    require(type(value) is str and len(value) <= 64 and VERSION.fullmatch(value) is not None,
            'source version must be at most 64 ASCII numeric/dot/underscore characters')
    return value


def absolute(value):
    text_value(os.fspath(value), 4096)
    return Path(os.path.abspath(value))


def same_stat(before, after):
    return (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) == (
        after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns)


def owned(info, directory=False, private=False):
    require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
            and info.st_uid == os.geteuid() and info.st_mode & 0o022 == 0,
            'directory/file custody refused')
    if private:
        require(stat.S_IMODE(info.st_mode) == (0o700 if directory else 0o600),
                'private directory/file mode required')


@contextlib.contextmanager
def directory(path, private=False, owner=False):
    """Walk without following ANY symlink component, retaining the final directory FD."""
    path = absolute(path)
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW)
    try:
        for component in path.parts[1:]:
            next_fd = os.open(component, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = next_fd
        if private or owner:
            owned(os.fstat(fd), directory=True, private=private)
        yield fd
    finally:
        os.close(fd)


@contextlib.contextmanager
def locked(fd):
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as failure:
        raise Refused('concurrent operation refused') from failure
    try:
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)


@contextlib.contextmanager
def regular(parent, name, private=False):
    require(name and '/' not in name and name not in ('.', '..'), 'invalid selected basename')
    fd = os.open(name, os.O_RDONLY | os.O_NONBLOCK | NOFOLLOW, dir_fd=parent)
    try:
        info = os.fstat(fd)
        require(stat.S_ISREG(info.st_mode), 'regular nonsymlink input required')
        if private:
            owned(info, private=True)
        with os.fdopen(fd, 'rb', closefd=False) as stream:
            yield stream, info
    finally:
        os.close(fd)


def bounded_read(stream, limit):
    info = os.fstat(stream.fileno())
    require(0 < info.st_size <= limit, 'record size refused')
    raw = stream.read(limit + 1)
    require(len(raw) == info.st_size and same_stat(info, os.fstat(stream.fileno())),
            'record changed during read')
    return raw


def member(stream, name, destination=None):
    before = os.fstat(stream.fileno())
    integer(before.st_size, 1, 2**63 - 1)
    stream.seek(0)
    sha = hashlib.sha256()
    length = 0
    while True:
        block = stream.read(CHUNK)
        if not block:
            break
        length += len(block)
        require(length <= before.st_size, 'input grew during read')
        sha.update(block)
        if destination is not None:
            destination.write(block)
    require(length == before.st_size and same_stat(before, os.fstat(stream.fileno())),
            'input changed during read')
    if destination is not None:
        destination.flush()
        os.fsync(destination.fileno())
    return {'name': name, 'bytes': length, 'sha256': sha.hexdigest()}


def absent(parent, name):
    try:
        os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        return
    raise Refused('destination already exists; no overwrite or same-attempt retry')


def exact_unlink(parent, name, identity):
    try:
        current = os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        return
    require((current.st_dev, current.st_ino) == identity, 'cleanup ownership changed; STOP')
    os.unlink(name, dir_fd=parent)


def publish_bytes(parent, name, raw):
    """Exclusive temp + no-clobber hard link; never replace a prior receipt."""
    owned(os.fstat(parent), directory=True)
    require(name and '/' not in name and name not in ('.', '..'), 'invalid publication basename')
    absent(parent, name)
    temporary = '.write-' + uuid.uuid4().hex
    identity, link_attempted = None, False
    try:
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600, dir_fd=parent)
        with os.fdopen(fd, 'wb') as stream:
            info = os.fstat(stream.fileno())
            identity = (info.st_dev, info.st_ino)
            os.fchmod(stream.fileno(), 0o600)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        link_attempted = True
        os.link(temporary, name, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        os.fsync(parent)
        exact_unlink(parent, temporary, identity)
        os.fsync(parent)
    except BaseException:
        # Include failures AFTER linking (even the last directory fsync). Remove
        # only our own inode, never a raced/prior receipt. Cleanup can itself fail:
        # a visible link then is NOT a successful receipt or durable negative proof.
        if identity is not None:
            for candidate in ([name] if link_attempted else []) + [temporary]:
                try:
                    exact_unlink(parent, candidate, identity)
                except (OSError, Refused):
                    pass
            try:
                os.fsync(parent)
            except OSError:
                pass
        raise


def record(parent, name, value):
    raw = canonical(value)
    require(len(raw) <= RECORD_LIMIT, 'receipt too large')
    publish_bytes(parent, name, raw)
    return digest(raw)


def load_record(parent, name):
    with regular(parent, name, private=True) as (stream, _):
        raw = bounded_read(stream, RECORD_LIMIT)
    return parse_json(raw, RECORD_LIMIT), digest(raw)


def paths(bundle, dump, media, legacy=False):
    bundle, dump, media = map(absolute, (bundle, dump, media))
    require(bundle.parent == dump.parent == media.parent, 'selected files must be siblings')
    require(dump.name.endswith('.dump'), 'custom dump name required')
    stem = dump.name[:-5]
    require(STEM.fullmatch(stem) is not None, 'unsupported bundle stem')
    require(media.name == stem + '.media.tar.gz'
            and bundle.name == stem + ('.bundle.sha256' if legacy else '.bundle.json'),
            'selected names do not identify one bundle')
    return bundle, dump, media


def member_record(value, name):
    keys(value, ('name', 'bytes', 'sha256'))
    require(value['name'] == name, 'manifest member does not match selected argument')
    integer(value['bytes'], 1, 2**63 - 1)
    hex_value(value['sha256'])


def manifest(raw, dump_name, media_name, legacy=False):
    if not legacy:
        value = parse_json(raw, MANIFEST_LIMIT)
        keys(value, ('schema', 'dump', 'media'))
        require(value['schema'] == SCHEMA, 'unsupported manifest schema')
        member_record(value['dump'], dump_name)
        member_record(value['media'], media_name)
        return value
    require(0 < len(raw) <= LEGACY_LIMIT, 'legacy manifest size refused')
    try:
        value = raw.decode('ascii')
    except UnicodeError as failure:
        raise Refused('legacy manifest must be ASCII') from failure
    lines = value.splitlines(keepends=True)
    require(len(lines) == 2, 'legacy manifest must contain exactly two records')
    result = {'schema': 'legacy-two-record'}
    for role, name, line in zip(('dump', 'media'), (dump_name, media_name), lines):
        match = re.fullmatch(r'([0-9a-f]{64}) (?: |\*)(/[^\n]+)\n', line)
        require(match is not None, 'unsupported legacy checksum record')
        old = match[2]
        require('\\' not in old and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in old)
                and '..' not in old.split('/') and old.rsplit('/', 1)[-1] == name,
                'unsafe or mismatched legacy pathname')
        result[role] = {'name': name, 'sha256': match[1]}
    return result


def selected(bundle, dump, media, expected, legacy=False, snapshot=None):
    hex_value(expected)
    bundle, dump, media = paths(bundle, dump, media, legacy)
    with directory(bundle.parent) as parent:
        with regular(parent, bundle.name) as (stream, _):
            raw = bounded_read(stream, LEGACY_LIMIT if legacy else MANIFEST_LIMIT)
        require(digest(raw) == expected, 'selected manifest pin mismatch')
        value = manifest(raw, dump.name, media.name, legacy)
        facts = {'profile': 'legacy-two-record' if legacy else SCHEMA,
                 'manifest': {'name': bundle.name, 'bytes': len(raw), 'sha256': expected}}
        # Open both originals once. Hash/copy those descriptors, not a second
        # pathname lookup after verification; only the private copies are restored.
        with contextlib.ExitStack() as opened:
            inputs = {role: opened.enter_context(regular(parent, path.name))
                      for role, path in (('dump', dump), ('media', media))}
            for role, (_, info) in inputs.items():
                integer(info.st_size, 1, 2**63 - 1)
                require(legacy or info.st_size == value[role]['bytes'], 'selected member length mismatch')
            if snapshot is not None:
                owned(os.fstat(snapshot), directory=True, private=True)
                available(snapshot, sum(info.st_size for _, info in inputs.values()) + len(raw), 3)
            for role, path in (('dump', dump), ('media', media)):
                source, _ = inputs[role]
                if snapshot is None:
                    actual = member(source, path.name)
                else:
                    fd = os.open(path.name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW,
                                 0o600, dir_fd=snapshot)
                    with os.fdopen(fd, 'wb') as output:
                        os.fchmod(output.fileno(), 0o600)
                        actual = member(source, path.name, output)
                require(actual['sha256'] == value[role]['sha256']
                        and (legacy or actual['bytes'] == value[role]['bytes']), 'selected member mismatch')
                facts[role] = actual
        if snapshot is not None:
            publish_bytes(snapshot, bundle.name, raw)
            os.fsync(snapshot)
        return facts


def pax_fields(raw):
    """Parse already-bounded PAX payload; never delegate declared reads to tarfile."""
    result = {}
    offset = 0
    allowed = {'path', 'size', 'mtime', 'atime', 'ctime', 'uid', 'gid', 'uname', 'gname'}
    while offset < len(raw):
        space = raw.find(b' ', offset, min(offset + 8, len(raw)))
        require(space > offset and raw[offset:space].isdigit(), 'invalid PAX length')
        length = int(raw[offset:space])
        require(space - offset + 3 <= length <= len(raw) - offset, 'invalid PAX record')
        body = raw[space + 1:offset + length]
        require(body.endswith(b'\n') and b'=' in body, 'invalid PAX record')
        key, value = body[:-1].split(b'=', 1)
        try:
            key, value = key.decode('ascii'), value.decode('utf-8')
        except UnicodeError as failure:
            raise Refused('unsupported PAX encoding') from failure
        require(key in allowed and key not in result and '\x00' not in value, 'unsupported PAX key')
        result[key] = value
        offset += length
    return result


def tar_name(value, is_directory):
    require(type(value) is str and '\\' not in value and not value.startswith('/')
            and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), 'unsafe archive path')
    root = value in ('.', './')
    while value.startswith('./'):
        value = value[2:]
    if is_directory and value.endswith('/'):
        value = value[:-1]
    if root:
        require(is_directory, 'archive root must be a directory')
        return ''
    components = value.split('/')
    require(value and all(part not in ('', '.', '..') for part in components)
            and len(components) <= DEPTH_LIMIT and len(value.encode('utf-8')) <= PATH_LIMIT,
            'archive path exceeds supported profile')
    return value


class TarReader:
    def __init__(self, stream):
        self.stream = stream
        self.read_bytes = 0
        self.maximum = TOTAL_LIMIT + ENTRY_LIMIT * (512 + METADATA_LIMIT + 511) + 10240

    def read(self, size):
        require(0 <= size <= CHUNK, 'unbounded archive read refused')
        block = self.stream.read(size)
        self.read_bytes += len(block)
        require(self.read_bytes <= self.maximum, 'expanded archive budget exceeded')
        return block

    def exact(self, size, sink=None):
        pieces = [] if sink is None else None
        require(sink is not None or size <= METADATA_LIMIT, 'unbounded metadata read refused')
        while size:
            block = self.read(min(size, CHUNK))
            require(block, 'truncated archive')
            size -= len(block)
            if sink is None:
                pieces.append(block)
            else:
                sink(block)
        return b''.join(pieces) if pieces is not None else b''


def child_directory(parent, components):
    fd = os.dup(parent)
    try:
        for component in components:
            try:
                os.mkdir(component, 0o700, dir_fd=fd)
                os.fsync(fd)
            except FileExistsError:
                pass
            next_fd = os.open(component, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=fd)
            owned(os.fstat(next_fd), directory=True, private=True)
            os.close(fd)
            fd = next_fd
        return fd
    except BaseException:
        os.close(fd)
        raise


def safe_tar(stream, extract=None):
    """Bound headers BEFORE metadata reads; consume CRC/trailer; never extractall."""
    before = os.fstat(stream.fileno())
    stream.seek(0)
    seen, ordered = {}, []
    global_pax, local_pax, long_name = {}, None, None
    entries = total = files = nodes = 0
    with gzip.GzipFile(fileobj=stream, mode='rb') as uncompressed:
        reader = TarReader(uncompressed)
        while True:
            header = reader.exact(512)
            if header == bytes(512):
                require(reader.exact(512) == bytes(512), 'incomplete tar end marker')
                require(local_pax is None and long_name is None, 'orphan archive metadata')
                padding = 0
                while True:
                    trailer = reader.read(10240)
                    if not trailer:
                        break
                    padding += len(trailer)
                    require(padding <= 10240 and not any(trailer), 'invalid or excessive tar end padding')
                break
            entries += 1
            require(entries <= ENTRY_LIMIT, 'archive entry limit exceeded')
            info = tarfile.TarInfo.frombuf(header, 'utf-8', 'strict')
            require(type(info.size) is int and info.size >= 0, 'invalid tar size')
            if info.type in (tarfile.XHDTYPE, tarfile.XGLTYPE, tarfile.GNUTYPE_LONGNAME):
                require(info.size <= METADATA_LIMIT, 'archive metadata limit exceeded')
                raw = reader.exact(info.size)
                reader.exact((-info.size) % 512)
                if info.type == tarfile.GNUTYPE_LONGNAME:
                    require(long_name is None and raw.endswith(b'\x00') and b'\x00' not in raw[:-1],
                            'invalid GNU long name')
                    long_name = raw[:-1].decode('utf-8')
                elif info.type == tarfile.XGLTYPE:
                    global_pax.update(pax_fields(raw))
                else:
                    require(local_pax is None, 'duplicate local PAX header')
                    local_pax = pax_fields(raw)
                continue
            require(info.type in (tarfile.REGTYPE, tarfile.AREGTYPE, tarfile.DIRTYPE)
                    and info.sparse is None, 'links/sparse/special archive entries refused')
            attributes = dict(global_pax)
            attributes.update(local_pax or {})
            local_pax = None
            name = tar_name(attributes.get('path', long_name if long_name is not None else info.name), info.isdir())
            long_name = None
            size = info.size
            if 'size' in attributes:
                require(re.fullmatch(r'[0-9]{1,20}', attributes['size']) is not None, 'invalid PAX size')
                size = int(attributes['size'])
            require(size <= FILE_LIMIT and (not info.isdir() or size == 0), 'archive member size refused')
            require(name not in seen, 'duplicate normalized archive entry')
            components = name.split('/') if name else []
            # Conservative inode budget includes implicit parent directories;
            # metadata headers do not create filesystem nodes.
            nodes += len(components)
            for index in range(1, len(components)):
                require(seen.get('/'.join(components[:index])) != 'file', 'file is an archive parent')
            if not info.isdir():
                index = bisect.bisect_left(ordered, name + '/')
                require(index == len(ordered) or not ordered[index].startswith(name + '/'),
                        'file is an archive parent')
                total += size
                files += 1
                require(total <= TOTAL_LIMIT, 'archive expanded-byte limit exceeded')
            seen[name] = 'directory' if info.isdir() else 'file'
            bisect.insort(ordered, name)
            if extract is None:
                reader.exact(size, sink=lambda _: None)
            else:
                space = os.fstatvfs(extract)
                require(size <= space.f_bavail * space.f_frsize, 'insufficient media staging space')
                parent = child_directory(extract, components if info.isdir() else components[:-1])
                try:
                    if not info.isdir():
                        fd = os.open(components[-1], os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW,
                                     0o600, dir_fd=parent)
                        with os.fdopen(fd, 'wb') as output:
                            os.fchmod(output.fileno(), 0o600)
                            reader.exact(size, sink=output.write)
                            output.flush()
                            os.fsync(output.fileno())
                    os.fsync(parent)
                finally:
                    os.close(parent)
            reader.exact((-size) % 512)
    require(same_stat(before, os.fstat(stream.fileno())), 'archive changed during inspection')
    return {'entries': entries, 'files': files, 'bytes': total, 'nodes': nodes}


def create(bundle, dump, media):
    bundle, dump, media = paths(bundle, dump, media)
    with directory(bundle.parent, owner=True) as parent:
        with regular(parent, dump.name) as (stream, _):
            dump_info = member(stream, dump.name)
            os.fsync(stream.fileno())
        with regular(parent, media.name) as (stream, _):
            media_info = member(stream, media.name)
            safe_tar(stream)
            os.fsync(stream.fileno())
        raw = canonical({'schema': SCHEMA, 'dump': dump_info, 'media': media_info})
        require(len(raw) <= MANIFEST_LIMIT, 'manifest too large')
        publish_bytes(parent, bundle.name, raw)
        return digest(raw)


def sync_tree(parent, depth=0, count=None):
    count = [0] if count is None else count
    require(depth <= DEPTH_LIMIT, 'publication tree depth refused')
    with os.scandir(parent) as entries:
        for entry in entries:
            count[0] += 1
            require(count[0] <= ENTRY_LIMIT * DEPTH_LIMIT, 'publication tree node limit exceeded')
            info = os.stat(entry.name, dir_fd=parent, follow_symlinks=False)
            if stat.S_ISDIR(info.st_mode):
                fd = os.open(entry.name, os.O_RDONLY | os.O_DIRECTORY | NOFOLLOW, dir_fd=parent)
                try:
                    owned(os.fstat(fd), directory=True, private=True)
                    sync_tree(fd, depth + 1, count)
                finally:
                    os.close(fd)
            else:
                with regular(parent, entry.name, private=True) as (stream, _):
                    os.fsync(stream.fileno())
    os.fsync(parent)


def publish_directory(stage, target):
    stage, target = absolute(stage), absolute(target)
    require(stage != target and stage.parent != stage and target.parent != target, 'invalid publication paths')
    with directory(stage.parent, owner=True) as origin, directory(target.parent, owner=True) as destination:
        absent(destination, target.name)
        with directory(stage, private=True) as source:
            info = os.fstat(source)
            require(info.st_dev == os.fstat(destination).st_dev, 'cross-device publication refused')
            sync_tree(source)
            current = os.stat(stage.name, dir_fd=origin, follow_symlinks=False)
            require((info.st_dev, info.st_ino) == (current.st_dev, current.st_ino), 'stage ownership changed')
            libc = ctypes.CDLL(None, use_errno=True)
            rename = getattr(libc, 'renameat2', None)
            require(rename is not None, 'renameat2 NOREPLACE unavailable')
            rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
            rename.restype = ctypes.c_int
            if rename(origin, os.fsencode(stage.name), destination, os.fsencode(target.name), 1) != 0:
                raise OSError(ctypes.get_errno(), 'no-clobber publication refused')
            try:
                os.fsync(destination)
                os.fsync(origin)
            except OSError as failure:
                raise PublishedButUnconfirmed('directory published but fsync failed; STOP and retain target') from failure


def pg_environment(restore=False):
    environment = dict(os.environ)
    for key in ('PGHOST', 'PGDATABASE', 'PGUSER'):
        text_value(environment.get(key), 1024)
    port = environment.setdefault('PGPORT', '5432')
    require(re.fullmatch(r'[0-9]{1,5}', port) is not None and 1 <= int(port) <= 65535,
            'invalid PostgreSQL port')
    environment['PGPORT'] = str(int(port))
    environment.setdefault('PGSSLMODE', 'verify-full')
    environment['PGCLIENTENCODING'] = 'UTF8'
    if restore:
        require(environment['PGDATABASE'].startswith('kira_restore_')
                and len(environment['PGDATABASE']) > len('kira_restore_'), 'disposable database name required')
        require(environment.get('KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST') == 'yes'
                and environment.get('KIRA_ENVIRONMENT') != 'production', 'destructive restore not authorized')
        require(environment.get('KIRA_RESTORE_CUSTODY_CONFIRMED') == 'yes',
                'exclusive DB/attempt/target-parent custody confirmation required')
    return environment


def endpoint(environment):
    return {name: environment['PG' + name.upper()] for name in ('host', 'port', 'database', 'user')}


def pg_arguments(environment):
    return ['--host=' + environment['PGHOST'], '--port=' + environment['PGPORT'],
            '--username=' + environment['PGUSER'], '--dbname=' + environment['PGDATABASE'], '--no-password']


def command(arguments, environment, stdin=None, stdout=None):
    completed = subprocess.run(arguments, env=environment, stdin=stdin,
                               stdout=subprocess.DEVNULL if stdout is None else stdout,
                               stderr=subprocess.DEVNULL, check=False)
    require(completed.returncode == 0, 'client/archive command failed; no verified outcome')


def captured(arguments, environment, limit):
    # Bound disk AND memory, including an unexpectedly large server response.
    # There is no shell, inherited stderr transcript, or unbounded communicate().
    with subprocess.Popen(arguments, env=environment, stdout=subprocess.PIPE,
                          stderr=subprocess.DEVNULL) as child:
        try:
            raw = bytearray()
            while len(raw) <= limit:
                block = child.stdout.read(min(CHUNK, limit + 1 - len(raw)))
                if not block:
                    break
                raw.extend(block)
            require(len(raw) <= limit, 'client response exceeds supported profile')
            require(child.wait() == 0, 'client command failed; no verified outcome')
            return bytes(raw)
        except BaseException:
            child.kill()
            child.wait()
            raise


def clients17(environment, names):
    for name in names:
        raw = captured([name, '--version'], environment, 4096)
        require(re.match(rb'^' + name.encode('ascii') + rb' \(PostgreSQL\) 17\.[0-9]+(?:[ \n]|$)', raw),
                'PostgreSQL 17 clients required')


def available(parent, size, entries=0):
    space = os.fstatvfs(parent)
    require(size <= space.f_bavail * space.f_frsize, 'insufficient available storage')
    if space.f_files:
        require(entries <= space.f_favail, 'insufficient available inodes')


def remove_stage(parent, name, identity):
    try:
        current = os.stat(name, dir_fd=parent, follow_symlinks=False)
    except FileNotFoundError:
        return
    require(stat.S_ISDIR(current.st_mode) and (current.st_dev, current.st_ino) == identity,
            'stage cleanup ownership changed; STOP')
    require(shutil.rmtree.avoids_symlink_attacks, 'safe owned-tree cleanup unavailable')
    shutil.rmtree(name, dir_fd=parent)
    os.fsync(parent)


def portable_backup(output, media_directory):
    supported_runtime()
    require(Path(output).is_absolute() and Path(media_directory).is_absolute(), 'absolute backup/media paths required')
    require(os.environ.get('KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED') == 'yes',
            'writer exclusion AND drain attestation required')
    environment = pg_environment()
    clients17(environment, ('pg_dump', 'pg_restore'))
    output = absolute(output)
    bundle, output, media_output = paths(output.with_suffix('.bundle.json'), output,
                                         output.with_suffix('.media.tar.gz'))
    with directory(media_directory):
        pass
    names = [output.name, output.name + '.manifest', output.name + '.sha256',
             media_output.name, media_output.name + '.sha256', bundle.name]
    with directory(output.parent, owner=True) as parent, locked(parent):
        for name in names + [output.stem + '.bundle.sha256']:
            absent(parent, name)
        stage_name = '.kira-backup-' + uuid.uuid4().hex
        os.mkdir(stage_name, 0o700, dir_fd=parent)
        os.fsync(parent)
        stage = output.parent / stage_name
        published = []
        with directory(stage, private=True) as stage_fd:
            info = os.fstat(stage_fd)
            identity = (info.st_dev, info.st_ino)
            try:
                command(['pg_dump', '--format=custom', '--compress=9', '--no-owner', '--no-acl',
                         '--file=' + str(stage / output.name), *pg_arguments(environment)], environment)
                with regular(stage_fd, output.name) as (dump_stream, _):
                    os.fchmod(dump_stream.fileno(), 0o600)
                    fd = os.open(output.name + '.manifest', os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW,
                                 0o600, dir_fd=stage_fd)
                    with os.fdopen(fd, 'wb') as listing:
                        command(['pg_restore', '--list'], environment, stdin=dump_stream, stdout=listing)
                        listing.flush()
                        os.fsync(listing.fileno())
                command(['tar', '-C', str(absolute(media_directory)), '-czf', str(stage / media_output.name), '.'],
                        environment)
                with regular(stage_fd, media_output.name) as (media_stream, _):
                    os.fchmod(media_stream.fileno(), 0o600)
                pin = create(stage / bundle.name, stage / output.name, stage / media_output.name)
                facts = selected(stage / bundle.name, stage / output.name, stage / media_output.name, pin)
                for role in ('dump', 'media'):
                    line = (facts[role]['sha256'] + '  ' + facts[role]['name'] + '\n').encode('ascii')
                    publish_bytes(stage_fd, facts[role]['name'] + '.sha256', line)
                for name in names:  # Manifest LAST. Prior files alone are not selectable.
                    with regular(stage_fd, name, private=True) as (stream, file_info):
                        os.fsync(stream.fileno())
                        published.append((name, (file_info.st_dev, file_info.st_ino)))
                        os.link(name, name, src_dir_fd=stage_fd, dst_dir_fd=parent, follow_symlinks=False)
                        os.fsync(parent)
                remove_stage(parent, stage_name, identity)
            except BaseException:
                # An interruption does not prove an asynchronous producer ended. Keep stage.
                for name, file_identity in reversed(published):
                    try:
                        exact_unlink(parent, name, file_identity)
                    except (OSError, Refused):
                        pass
                try:
                    os.fsync(parent)
                except OSError:
                    pass
                raise Refused('backup incomplete; retain unselectable stage for operator reconciliation')
        return pin


HISTORY_SQL = """SELECT pg_catalog.json_build_object(
 'database', current_database(), 'user', current_user,
 'oid', (SELECT oid::bigint FROM pg_catalog.pg_database WHERE datname=current_database()),
 'address', pg_catalog.inet_server_addr()::text, 'port', pg_catalog.inet_server_port(),
 'history', COALESCE((SELECT pg_catalog.json_agg(pg_catalog.json_build_object(
   'rank', installed_rank, 'version', version, 'type', type, 'script', script,
   'checksum', checksum, 'success', success) ORDER BY installed_rank)
   FROM public.flyway_schema_history), '[]'::json))"""


def database_state(environment, expected_version):
    raw = captured(['psql', '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--tuples-only', '--no-align', '--quiet',
                    *pg_arguments(environment), '--command=' + HISTORY_SQL], environment, HISTORY_LIMIT)
    state = parse_json(raw, HISTORY_LIMIT)
    keys(state, ('database', 'user', 'oid', 'address', 'port', 'history'))
    require(state['database'] == environment['PGDATABASE'] and state['user'] == environment['PGUSER'],
            'actual database identity differs from requested endpoint')
    integer(state['oid'], 1, 2**32 - 1)
    if state['address'] is not None:
        text_value(state['address'], 128)
    if state['port'] is not None:
        integer(state['port'], 1, 65535)
    history = state['history']
    require(type(history) is list and history, 'empty Flyway history')
    prior_rank, version = None, None
    for row in history:
        keys(row, ('rank', 'version', 'type', 'script', 'checksum', 'success'))
        integer(row['rank'], -(2**31), 2**31 - 1)
        require((prior_rank is None or row['rank'] > prior_rank) and row['success'] is True,
                'failed or inconsistent Flyway history')
        prior_rank = row['rank']
        text_value(row['type'], 128)
        text_value(row['script'], 4096)
        if row['checksum'] is not None:
            integer(row['checksum'], -(2**31), 2**31 - 1)
        if row['version'] is not None:
            version = source_version(row['version'])
    require(version is not None and version == expected_version, 'as-restored source version mismatch')
    return {'identity': {name: state[name] for name in ('database', 'user', 'oid', 'address', 'port')},
            'source_version': version, 'history_sha256': digest(canonical(history))}


@contextlib.contextmanager
def attempt_lock(parent, new=False):
    flags = os.O_RDWR | NOFOLLOW | (os.O_CREAT | os.O_EXCL if new else 0)
    fd = os.open('.lock', flags, 0o600, dir_fd=parent)
    try:
        if new:
            os.fchmod(fd, 0o600)
            os.fsync(fd)
            os.fsync(parent)
        owned(os.fstat(fd), private=True)
        with locked(fd):
            yield
    finally:
        os.close(fd)


def stop_attempt(parent):
    # Storage may also reject this negative evidence. An owner MUST NOT advance
    # any failed command, even if a receipt link remains visible.
    try:
        record(parent, 'STOP.json', {'schema': 'kira.restore-stop.v1', 'disposition': 'operator-reconciliation-only'})
    except (OSError, Refused):
        pass


def selection_record(value):
    keys(value, ('profile', 'manifest', 'dump', 'media'))
    require(value['profile'] in (SCHEMA, 'legacy-two-record'), 'unknown selected profile')
    for role in ('manifest', 'dump', 'media'):
        keys(value[role], ('name', 'bytes', 'sha256'))
        name = text_value(value[role]['name'], 128)
        require('/' not in name and '\\' not in name and name not in ('.', '..'),
                'frozen selection must use basenames only')
        integer(value[role]['bytes'], 1, 2**63 - 1)
        hex_value(value[role]['sha256'])
    paths(value['manifest']['name'], value['dump']['name'], value['media']['name'],
          value['profile'] == 'legacy-two-record')


def request_record(value, environment):
    keys(value, ('schema', 'id', 'selection', 'source_version', 'media_target', 'endpoint'))
    require(value['schema'] == 'kira.restore-attempt.v1', 'unknown attempt schema')
    require(type(value['id']) is str and str(uuid.UUID(value['id'])) == value['id'], 'invalid attempt UUID')
    selection_record(value['selection'])
    source_version(value['source_version'])
    require(value['endpoint'] == endpoint(environment), 'attempt endpoint changed')
    require(type(value['media_target']) is str and str(absolute(value['media_target'])) == value['media_target'],
            'invalid intended media target')


def frozen(attempt, parent, selection):
    selection_record(selection)
    actual = selected(attempt / selection['manifest']['name'], attempt / selection['dump']['name'],
                      attempt / selection['media']['name'], selection['manifest']['sha256'],
                      selection['profile'] == 'legacy-two-record')
    require(canonical(actual) == canonical(selection), 'frozen snapshot no longer matches request')
    for role in ('manifest', 'dump', 'media'):
        with regular(parent, selection[role]['name'], private=True):
            pass


def restore_database(bundle, expected, dump, media, version, attempt, target, legacy=False):
    supported_runtime()
    environment = pg_environment(restore=True)
    version = source_version(version)
    clients17(environment, ('pg_restore', 'psql'))
    attempt, target = absolute(attempt), absolute(target)
    require(attempt != target and attempt.parent != attempt and target.parent != target,
            'invalid attempt/target paths')
    with directory(target.parent, owner=True) as target_parent:
        absent(target_parent, target.name)
    with directory(attempt.parent, owner=True) as parent:
        absent(parent, attempt.name)
        os.mkdir(attempt.name, 0o700, dir_fd=parent)
        os.fsync(parent)
    with directory(attempt, private=True) as owned_attempt, attempt_lock(owned_attempt, new=True):
        try:
            attempt_id = str(uuid.uuid4())
            selection = selected(bundle, dump, media, expected, legacy, snapshot=owned_attempt)
            request = {'schema': 'kira.restore-attempt.v1', 'id': attempt_id, 'selection': selection,
                       'source_version': version, 'media_target': str(target), 'endpoint': endpoint(environment)}
            request_hash = record(owned_attempt, 'request.json', request)
            with regular(owned_attempt, selection['dump']['name'], private=True) as (stream, _):
                command(['pg_restore', '--list'], environment, stdin=stream)
            with regular(owned_attempt, selection['media']['name'], private=True) as (stream, _):
                archive = safe_tar(stream)
            with directory(target.parent, owner=True) as target_parent:
                absent(target_parent, target.name)
                available(target_parent, archive['bytes'], archive['nodes'])
            record(owned_attempt, 'db-started.json',
                   {'schema': 'kira.restore-db-started.v1', 'id': attempt_id, 'request_sha256': request_hash})
            with regular(owned_attempt, selection['dump']['name'], private=True) as (stream, _):
                command(['pg_restore', '--exit-on-error', '--clean', '--if-exists', '--no-owner', '--no-acl',
                         '--single-transaction', *pg_arguments(environment)], environment, stdin=stream)
            state = database_state(environment, version)
            record(owned_attempt, 'db.json', {'schema': 'kira.restore-db.v1', 'id': attempt_id,
                   'request_sha256': request_hash, 'selection': selection, 'endpoint': endpoint(environment), **state})
        except BaseException:
            stop_attempt(owned_attempt)
            raise Refused('STOP: database attempt incomplete/unverified; retain attempt and target; no same-attempt retry')


def restore_media(attempt):
    supported_runtime()
    environment = pg_environment(restore=True)
    clients17(environment, ('psql',))
    attempt = absolute(attempt)
    with directory(attempt, private=True) as parent, attempt_lock(parent):
        # A repeated command must not mutate a completed or already stopped
        # history, nor reinterpret a stale receipt as success.
        for name in ('STOP.json', 'media-started.json', 'pair.json'):
            absent(parent, name)
        stage, stage_identity = None, None
        publication_attempted = False
        published = False
        try:
            request, request_hash = load_record(parent, 'request.json')
            request_record(request, environment)
            started, _ = load_record(parent, 'db-started.json')
            require(started == {'schema': 'kira.restore-db-started.v1', 'id': request['id'],
                                'request_sha256': request_hash}, 'database stage correspondence failed')
            receipt, receipt_hash = load_record(parent, 'db.json')
            keys(receipt, ('schema', 'id', 'request_sha256', 'selection', 'endpoint',
                           'identity', 'source_version', 'history_sha256'))
            require(receipt['schema'] == 'kira.restore-db.v1' and receipt['id'] == request['id']
                    and receipt['request_sha256'] == request_hash
                    and canonical(receipt['selection']) == canonical(request['selection'])
                    and canonical(receipt['endpoint']) == canonical(request['endpoint']),
                    'database receipt correspondence failed')
            frozen(attempt, parent, request['selection'])
            state = database_state(environment, request['source_version'])
            require(canonical({key: receipt[key] for key in state}) == canonical(state),
                    'database identity/OID/full history changed; STOP')
            target = absolute(request['media_target'])
            with directory(target.parent, owner=True) as target_parent, locked(target_parent):
                absent(target_parent, target.name)
                with regular(parent, request['selection']['media']['name'], private=True) as (stream, _):
                    archive = safe_tar(stream)
                    available(target_parent, archive['bytes'], archive['nodes'])
                    record(parent, 'media-started.json', {'schema': 'kira.restore-media-started.v1',
                           'id': request['id'], 'request_sha256': request_hash, 'target': str(target)})
                    stage = target.parent / ('.kira-media-' + request['id'])
                    os.mkdir(stage.name, 0o700, dir_fd=target_parent)
                    os.fsync(target_parent)
                    with directory(stage, private=True) as output:
                        info = os.fstat(output)
                        stage_identity = (info.st_dev, info.st_ino)
                        safe_tar(stream, extract=output)
                        sync_tree(output)
                require(database_state(environment, request['source_version']) == state,
                        'database changed before media publication')
                publication_attempted = True
                publish_directory(stage, target)
                published = True
                actual = os.stat(target.name, dir_fd=target_parent, follow_symlinks=False)
                require((actual.st_dev, actual.st_ino) == stage_identity, 'published target identity changed')
                record(parent, 'pair.json', {'schema': 'kira.restore-pair.v1', 'id': request['id'],
                       'request_sha256': request_hash, 'database_receipt_sha256': receipt_hash,
                       'target': {'path': str(target), 'device': actual.st_dev, 'inode': actual.st_ino}})
        except BaseException:
            stop_attempt(parent)
            if stage is not None and stage_identity is not None and not publication_attempted:
                try:
                    with directory(stage.parent, owner=True) as stage_parent:
                        remove_stage(stage_parent, stage.name, stage_identity)
                except (OSError, Refused):
                    pass
            if published:
                raise Refused('STOP: target published but pair receipt failed; retain attempt and target; no rollback/retry')
            if publication_attempted:
                raise Refused('STOP: media publication ambiguous; retain stage, attempt and any target; no rollback/retry')
            raise Refused('STOP: media stage incomplete; retain attempt; no same-attempt retry')


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise Refused('invalid arguments; consult the reviewed recovery runbook')


def main(argv=None):
    os.umask(0o077)
    try:
        supported_runtime()
        parser = Parser(description=__doc__, allow_abbrev=False)
        sub = parser.add_subparsers(dest='operation', required=True, parser_class=Parser)
        for name in ('create', 'verify'):
            item = sub.add_parser(name, allow_abbrev=False)
            for argument in ('bundle', 'dump', 'media'):
                item.add_argument('--' + argument, required=True)
            if name == 'verify':
                item.add_argument('--expected-sha256', required=True)
                item.add_argument('--legacy-two-record', action='store_true')
        item = sub.add_parser('publish-directory', allow_abbrev=False)
        item.add_argument('--stage', required=True)
        item.add_argument('--target', required=True)
        item = sub.add_parser('_backup', allow_abbrev=False)
        item.add_argument('output')
        item.add_argument('media_directory')
        item = sub.add_parser('_restore-db', allow_abbrev=False)
        for name in ('bundle', 'expected', 'dump', 'media', 'version', 'attempt', 'target'):
            item.add_argument(name)
        item.add_argument('--legacy-two-record', action='store_true')
        item = sub.add_parser('_restore-media', allow_abbrev=False)
        item.add_argument('attempt')
        args = parser.parse_args(argv)
        if args.operation == 'create':
            print(create(args.bundle, args.dump, args.media))
        elif args.operation == 'verify':
            selected(args.bundle, args.dump, args.media, args.expected_sha256, args.legacy_two_record)
            print(args.expected_sha256)
        elif args.operation == 'publish-directory':
            publish_directory(args.stage, args.target)
        elif args.operation == '_backup':
            print('backup bundle created; inventory SHA-256: ' + portable_backup(args.output, args.media_directory))
        elif args.operation == '_restore-db':
            restore_database(args.bundle, args.expected, args.dump, args.media, args.version, args.attempt,
                             args.target, args.legacy_two_record)
            print('database-stage receipt recorded; media and release verification still required')
        else:
            restore_media(args.attempt)
            print('pair-stage receipt recorded; release verification still required')
        return 0
    except Refused as failure:
        print('backup_bundle: ' + str(failure), file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError, UnicodeError, tarfile.TarError, EOFError, RecursionError, zlib.error):
        print('backup_bundle: REFUSED/STOP; retain any attempt and published target; '
              'do not retry or infer rollback; consult the recovery runbook', file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('backup_bundle: interrupted/STOP; retain attempt and target for reconciliation', file=sys.stderr)
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
