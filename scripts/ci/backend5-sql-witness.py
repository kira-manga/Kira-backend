"""Actual fixed restored_media_rows SQL on one owned PG17.6 fixture; NOT a restore drill."""
import hashlib
import importlib.util
import json
import os
import re
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    backend, report = map(Path, sys.argv[1:])
    source = backend / 'scripts/db/backup_bundle.py'
    expected = os.environ['BACKEND5_BACKUP_SHA256']
    require(hashlib.sha256(source.read_bytes()).hexdigest() == expected, 'SQL module source pin differs')
    spec = importlib.util.spec_from_file_location('backend5_actual_backup_bundle', source)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    environment = module.pg_environment()
    require(environment['PGHOST'] == '/var/run/postgresql'
            and environment['PGDATABASE'] == environment['PGUSER'] == 'backend5_sql',
            'Only the explicitly owned isolated fixture is allowed')
    require(module.TUTORIAL_ROW_LIMIT == 10000, 'Unexpected production query bound')
    module.clients17(environment, ('psql',))
    client = module.captured(['psql', '--version'], environment, 4096).decode().strip()
    require(re.fullmatch(r'psql \(PostgreSQL\) 17\.6(?: .*)?', client), 'Exact PostgreSQL 17.6 client required')

    def sql(statement, limit=4096):
        return module.captured(['psql', '--no-psqlrc', '--set=ON_ERROR_STOP=1', '--tuples-only',
                                '--no-align', '--quiet', *module.pg_arguments(environment),
                                '--command=' + statement], environment, limit).decode().strip()

    version = sql("SELECT current_setting('server_version_num')")
    require(version == '170006', 'Actual server is not PostgreSQL 17.6')
    migration = backend / 'src/main/resources/db/migration/V9__tutorials.sql'
    blocks = re.findall(r'^CREATE TABLE tutorial_media \(\n.*?^\);\n', migration.read_text(), re.M | re.S)
    require(len(blocks) == 1, 'Expected the single actual tutorial_media migration table definition')
    # The actual V9 table DDL (including column types/checks) is used, not a permissive row stub.
    # Only its users FK and the identity/history SELECT's Flyway projection need small fixtures.
    sql("""CREATE TABLE users (id uuid PRIMARY KEY);
CREATE TABLE flyway_schema_history (
 installed_rank integer PRIMARY KEY, version varchar(50), type varchar(20) NOT NULL,
 script varchar(1000) NOT NULL, checksum integer, success boolean NOT NULL);
INSERT INTO flyway_schema_history VALUES (1, '9', 'SQL', 'V9__tutorials.sql', 1234567, true);
""" + blocks[0])
    state = module.database_state(environment, '9')
    cases = []

    def passed(name):
        cases.append({'name': name, 'status': 'PASS'})

    require(module.restored_media_rows(environment, '9', state) == [], 'Empty aggregate must be []')
    passed('empty_real_table')
    ids = ['00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002']
    expected_rows = [dict(id=identity, storage_filename=identity + '.png', content_type='image/png',
                          byte_size=10 + index, width=1, height=2, sha256=str(index + 1) * 64,
                          published=bool(index)) for index, identity in enumerate(ids)]
    for row in reversed(expected_rows):  # Opposite insertion order must not alter the fixed ORDER BY.
        sql("INSERT INTO tutorial_media VALUES ('{id}', '{storage_filename}', '{content_type}', "
            "{byte_size}, {width}, {height}, '{sha256}', {published}, NULL, '2026-01-01T00:00:00Z')"
            .format(**(row | {'published': str(row['published']).lower()})))
    rows = module.restored_media_rows(environment, '9', state)
    require(rows == expected_rows, 'Mixed publication rows/projection/order differ')
    for row in rows:
        module.tutorial_row(row)
    passed('draft_published_exact_projection_and_uuid_order')

    sql('UPDATE flyway_schema_history SET checksum = checksum + 1')
    try:
        module.restored_media_rows(environment, '9', state)
    except module.Refused as failure:
        require(str(failure) == 'media query database identity/history changed', 'Wrong changed-history refusal')
    else:
        raise RuntimeError('Changed real database history was accepted')
    sql('UPDATE flyway_schema_history SET checksum = checksum - 1')
    require(module.database_state(environment, '9') == state, 'Fixture history not restored')
    passed('actual_history_change_refused')

    sql("""TRUNCATE tutorial_media;
INSERT INTO tutorial_media
SELECT ('00000000-0000-0000-0000-' || lpad(n::text, 12, '0'))::uuid,
       '00000000-0000-0000-0000-' || lpad(n::text, 12, '0') || '.png',
       'image/png', 10, 1, 2, repeat('a', 64), false, NULL, '2026-01-01T00:00:00Z'::timestamptz
FROM generate_series(1, 10000) AS n;""")
    rows = module.restored_media_rows(environment, '9', state)
    require(len(rows) == 10000 and rows[0]['id'] == ids[0]
            and rows[-1]['id'] == '00000000-0000-0000-0000-000000010000', 'Exact production row limit differs')
    for row in rows:
        module.tutorial_row(row)
    passed('actual_10000_rows_complete')
    sql("""INSERT INTO tutorial_media VALUES (
'00000000-0000-0000-0000-000000010001', '00000000-0000-0000-0000-000000010001.png',
'image/png', 10, 1, 2, repeat('a', 64), false, NULL, '2026-01-01T00:00:00Z')""")
    try:
        module.restored_media_rows(environment, '9', state)
    except module.Refused as failure:
        require(str(failure) == 'tutorial row inventory incomplete or over limit', 'Wrong overflow refusal')
    else:
        raise RuntimeError('Actual 10001-row overflow was accepted')
    passed('actual_10001_rows_refused_without_accepting_prefix')
    require(hashlib.sha256(source.read_bytes()).hexdigest() == expected, 'SQL module changed during witness')
    report.write_text(json.dumps({'status': 'PASS', 'backup_bundle_sha256': expected,
                                 'migration_sha256': hashlib.sha256(migration.read_bytes()).hexdigest(),
                                 'server_version_num': version, 'psql_version': client, 'cases': cases,
                                 'scope': 'actual fixed SELECT only; not restore/custody/durability/drill'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
