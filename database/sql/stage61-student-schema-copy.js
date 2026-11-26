// Run through copy-campus-market-student-db.ps1.
'use strict';

const {spawn} = require('node:child_process');
const {randomBytes} = require('node:crypto');
const {closeSync, openSync, readFileSync} = require('node:fs');
const {createInterface} = require('node:readline');

const source = 'campus_market_dev';
const target = '2024011324';

function classicArgs(user, port) {
    return [
        '--no-defaults', '--protocol=TCP', '--host=127.0.0.1', '--port=' + port,
        '--user=' + user, '-p', '--batch',
        '--skip-reconnect', '--binary-mode', '--default-character-set=utf8mb4', '--local-infile=0'
    ];
}

function checkedExit(client) {
    return new Promise((resolve, reject) => {
        client.once('error', reject);
        client.once('close', (code, signal) => {
            if (code === 0) return resolve();
            const nativeCode = code === null ? null : code | 0;
            const error = new Error('mysql.exe exited with ' + (nativeCode ?? signal));
            error.nativeCode = nativeCode;
            reject(error);
        });
    });
}

function restoreFile(clientPath, user, port, inputPath) {
    // Give mysql the original file as stdin. The client handles its own password prompt.
    const fd = openSync(inputPath, 'r');
    try {
        return checkedExit(spawn(clientPath, [...classicArgs(user, port), '--database=' + target],
            {stdio: [fd, 'inherit', 'inherit']}));
    } finally {
        closeSync(fd);
    }
}

function openClassicClient(clientPath, user, port) {
    const client = spawn(clientPath, [...classicArgs(user, port), '--skip-column-names', '--unbuffered'],
        {stdio: ['pipe', 'pipe', 'inherit']});
    // The Windows client's password prompt uses the console. SQL uses the separate stdin pipe.
    const output = createInterface({input: client.stdout, crlfDelay: Infinity});
    const lines = output[Symbol.asyncIterator]();
    client.stdin.on('error', () => {}); // A terminating client may close the pipe mid-query.
    const closed = checkedExit(client);
    // The caller checks the rejection after consuming stdout, which may take longer than process exit.
    closed.catch(() => {});

    return {
        async query(sql, params = []) {
            let index = 0;
            const statement = sql.replace(/\?/g, () => {
                const value = params[index++];
                requireThat(typeof value === 'string', 'Invalid query parameter');
                return "CONVERT(X'" + Buffer.from(value, 'utf8').toString('hex') + "' USING utf8mb4)";
            });
            requireThat(index === params.length, 'Invalid query parameter count');
            const marker = '__campus_copy_' + randomBytes(16).toString('hex');
            client.stdin.write(statement + ";\nSELECT '" + marker + "';\n");
            const rows = [];
            for (;;) {
                const {value, done} = await lines.next();
                requireThat(!done, 'mysql.exe ended before returning a complete result');
                if (value === marker) return rows;
                rows.push(value.split('\t').map(decodeField));
            }
        },
        async close() {
            client.stdin.end();
            output.close();
            await closed;
        }
    };
}

function decodeField(field) {
    const escapes = {'0': '\0', n: '\n', r: '\r', t: '\t', '\\': '\\'};
    return field === 'NULL' ? null : field.replace(/\\([0nrt\\])/g, (_, code) => escapes[code]);
}

function ident(name) {
    return '`' + name.replace(/`/g, '``') + '`';
}

function table(schema, name) {
    return ident(schema) + '.' + ident(name);
}

function requireThat(condition, message) {
    if (!condition) throw new Error(message);
}

function same(a, b) {
    return JSON.stringify(a) === JSON.stringify(b);
}

async function runCopy(mode, query) {
    async function scalar(sql, params = []) {
        return (await query(sql, params))[0][0];
    }

    async function database(schema) {
        return (await query('SELECT DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?', [schema]))[0];
    }

    async function inventory(schema) {
        return {
            tables: (await query('SELECT TABLE_NAME, TABLE_TYPE, ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME', [schema]))
                .map(row => [row[0], row[1], row[2]]),
            triggers: await scalar('SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA = ?', [schema]),
            routines: await scalar('SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA = ?', [schema]),
            events: await scalar('SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA = ?', [schema])
        };
    }

    async function foreignKeys(schema) {
        return (await query(`SELECT k.TABLE_NAME, k.CONSTRAINT_NAME, k.COLUMN_NAME, k.ORDINAL_POSITION,
                    k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, k.REFERENCED_TABLE_SCHEMA = ?,
                    r.UPDATE_RULE, r.DELETE_RULE
                 FROM information_schema.KEY_COLUMN_USAGE k
                 JOIN information_schema.REFERENTIAL_CONSTRAINTS r
                   ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME
                   AND r.TABLE_NAME = k.TABLE_NAME
                 WHERE k.TABLE_SCHEMA = ? AND k.REFERENCED_TABLE_NAME IS NOT NULL
                 ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION`, [schema, schema]))
            .map(row => [row[0], row[1], row[2], row[3], row[4], row[5], row[6], row[7], row[8]]);
    }

    async function requireMigrations(schema) {
        const required = [
            ['settlements', 'level_id_snapshot', 'fk_settlements_level_snapshot', 'FOREIGN KEY'],
            ['return_requests', 'request_key', 'uq_return_requests_order_request_key', 'UNIQUE'],
            ['trade_reviews', 'rating', 'ck_trade_reviews_rating', 'CHECK']
        ];
        for (const [name, field, key, kind] of required) {
            requireThat(Number(await scalar('SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND COLUMN_NAME=?',
                [schema, name, field])) === 1, 'Missing column ' + name + '.' + field);
            requireThat(Number(await scalar('SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND CONSTRAINT_NAME=? AND CONSTRAINT_TYPE=?',
                [schema, name, key, kind])) === 1, 'Missing constraint ' + key);
        }
    }

    async function requireFixtures(schema) {
        const categoryNames = await query('SELECT name,sort_order,is_enabled FROM ' + table(schema, 'categories') + ' ORDER BY sort_order,id');
        const expectedCategories = ['教材与学习用品', '数码电子', '宿舍与生活用品', '服饰鞋包', '运动与户外', '其他'];
        requireThat(categoryNames.length === 6 && categoryNames.every((row, i) => row[0] === expectedCategories[i]
            && Number(row[1]) === (i + 1) * 10 && Number(row[2]) === 1), 'Expected six configured categories');
        const expected = ['0.001000', '0.002000', '0.005000', '0.007500', '0.010000'];
        const levels = await query('SELECT level_code, CAST(fee_rate AS CHAR) FROM ' + table(schema, 'merchant_levels') + ' ORDER BY level_code');
        requireThat(levels.length === 5 && levels.every((row, i) => Number(row[0]) === i + 1 && row[1] === expected[i]),
            'Expected five configured merchant fee rates');
    }

    async function imageCounts(schema) {
        const manifest = JSON.parse(readFileSync(process.env.CAMPUS_COPY_IMAGE_MANIFEST, 'utf8'));
        let products = 0;
        let documents = 0;
        for (const row of await query('SELECT storage_ref FROM ' + table(schema, 'product_images'))) {
            requireThat(manifest.products.includes(row[0]), 'At least one product image is missing or empty on disk');
            products++;
        }
        for (const row of await query('SELECT business_license_ref, identity_document_ref FROM ' + table(schema, 'merchants'))) {
            for (const ref of [row[0], row[1]]) {
                if (ref === null) continue;
                requireThat(manifest.documents.includes(ref), 'At least one private seller document is missing or empty on disk');
                documents++;
            }
        }
        return {products, documents};
    }

    async function snapshot(schema, objects) {
        const result = {};
        for (const [name] of objects.tables) {
            const columns = (await query('SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION',
                [schema, name])).map(row => ident(row[0]));
            const key = (await query("SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX",
                [schema, name])).map(row => ident(row[0]));
            requireThat(key.length > 0, 'Table lacks a primary key: ' + name);
            const qualified = table(schema, name);
            const definition = (await query('SHOW CREATE TABLE ' + qualified))[0][1];
            requireThat(!/REFERENCES\s+(?:`campus_market_dev`|campus_market_dev)\s*\./i.test(definition),
                'Explicit source-schema foreign key requires review: ' + name);
            const encoded = columns.map(column => 'CONVERT(HEX(CAST(' + column + ' AS BINARY)) USING utf8mb4)').join(',');
            // Hash every row, preserving NULL and binary bytes. Keep hashes in memory only.
            const hashes = (await query('SELECT SHA2(JSON_ARRAY(' + encoded + '),256) FROM ' + qualified + ' ORDER BY ' + key.join(',')))
                .map(row => row[0]);
            result[name] = {definition, hashes};
        }
        return result;
    }

    async function emptyTarget() {
        const config = await database(target);
        if (!config) return null;
        const objects = await inventory(target);
        requireThat(!objects.tables.length && !(Number(objects.triggers) + Number(objects.routines) + Number(objects.events)),
            'Target schema contains objects. No overwrite or automatic retry is allowed.');
        return config;
    }

    function reportCounts(data) {
        for (const name of Object.keys(data)) console.log(name + ': ' + data[name].hashes.length + ' rows');
    }

    async function verifyForeignKeyRows(schema, keys) {
        const groups = new Map();
        for (const row of keys) {
            const id = JSON.stringify([row[0], row[1]]);
            if (!groups.has(id)) groups.set(id, []);
            groups.get(id).push(row);
        }
        for (const columns of groups.values()) {
            const [child, constraint, , , parent] = columns[0];
            const nonNull = columns.map(row => 'c.' + ident(row[2]) + ' IS NOT NULL').join(' AND ');
            const match = columns.map(row => 'p.' + ident(row[5]) + ' = c.' + ident(row[2])).join(' AND ');
            requireThat(!(await query('SELECT 1 FROM ' + table(schema, child) + ' c WHERE ' + nonNull
                + ' AND NOT EXISTS (SELECT 1 FROM ' + table(schema, parent) + ' p WHERE ' + match + ') LIMIT 1')).length,
                'Foreign key has unmatched rows: ' + child + '.' + constraint);
        }
    }

    const targetConfig = mode === 'Verify' ? await database(target) : await emptyTarget();
    if (mode === 'Verify') requireThat(targetConfig, 'Target does not exist');
    console.log('Target: ' + (targetConfig ? 'exists' : 'does not exist'));
    const config = await database(source);
    requireThat(config, 'Source schema is missing or not visible');
    const charset = config[0];
    const collation = config[1];
    requireThat(/^[A-Za-z0-9_]+$/.test(charset) && /^[A-Za-z0-9_]+$/.test(collation), 'Unexpected source schema defaults');
    if (targetConfig) requireThat(targetConfig[0] === charset && targetConfig[1] === collation, 'Target schema defaults differ');
    console.log('Source defaults: ' + charset + ' / ' + collation);
    const objects = await inventory(source);
    const views = objects.tables.filter(row => row[1] !== 'BASE TABLE');
    console.log('Source objects: ' + objects.tables.length + ' table/view objects, ' + views.length + ' views, '
        + objects.triggers + ' triggers, ' + objects.routines + ' routines, ' + objects.events + ' events');
    requireThat(!views.length && !(Number(objects.triggers) + Number(objects.routines) + Number(objects.events)),
        'Additional database objects need explicit review of schema references and definers before automatic copy');
    requireThat(objects.tables.length === 26 && objects.tables.every(row => row[2] === 'InnoDB'),
        'Source differs from the expected 26 InnoDB tables');
    const keys = await foreignKeys(source);
    requireThat(keys.length > 0 && keys.every(row => Number(row[6]) === 1 && row[7] === 'RESTRICT' && row[8] === 'RESTRICT'),
        'Source foreign keys require review');
    await verifyForeignKeyRows(source, keys);
    await requireMigrations(source);
    await requireFixtures(source);
    const images = await imageCounts(source);
    const before = await snapshot(source, objects);
    if (mode === 'Preflight') {
        reportCounts(before);
        console.log('Source image references matched: ' + images.products + ' products, ' + images.documents + ' private documents');
        return 'PREFLIGHT COMPLETE: metadata and files inspected; no database writes performed.';
    }
    if (mode === 'PrepareTarget') {
        const current = await emptyTarget();
        requireThat(Boolean(targetConfig) === Boolean(current)
            && (!current || (current[0] === charset && current[1] === collation)), 'Target changed before import');
        if (!current) await query('CREATE DATABASE ' + ident(target) + ' CHARACTER SET ' + charset + ' COLLATE ' + collation);
        return 'TARGET READY: only the empty target is available for SQL restore.';
    }

    const newObjects = await inventory(target);
    requireThat(same(objects, newObjects), 'Target table inventory differs');
    requireThat(same(keys, await foreignKeys(target)), 'Foreign key definitions or referenced schemas differ');
    await verifyForeignKeyRows(target, keys);
    await requireMigrations(target);
    await requireFixtures(target);
    requireThat(same(images, await imageCounts(target)), 'Target file references differ');
    const after = await snapshot(target, newObjects);
    requireThat(same(objects, await inventory(source)) && same(before, await snapshot(source, objects)),
        'Source changed during copy; target is not approved for cutover');
    for (const name of Object.keys(before)) {
        requireThat(before[name].definition === after[name].definition, 'Table DDL or AUTO_INCREMENT differs: ' + name);
        requireThat(same(before[name].hashes, after[name].hashes), 'Row data differs: ' + name);
        console.log(name + ': ' + before[name].hashes.length + ' source / ' + after[name].hashes.length + ' target, DDL and row digests match');
    }
    return 'COPY VERIFIED: 26 tables, foreign keys, three migrations, categories, fee levels and storage references.';
}

async function main() {
    const mode = process.env.CAMPUS_COPY_MODE;
    const user = process.env.CAMPUS_COPY_MYSQL_USER;
    const port = Number(process.env.CAMPUS_COPY_MYSQL_PORT);
    const clientPath = process.env.CAMPUS_COPY_MYSQL_CLIENT;
    requireThat(['Preflight', 'PrepareTarget', 'Restore', 'Verify'].includes(mode)
        && typeof user === 'string' && /^[A-Za-z0-9_.-]+$/.test(user)
        && Number.isInteger(port) && port >= 1 && port <= 65535 && clientPath,
        'Start the copy using the PowerShell launcher.');

    if (mode === 'Restore') {
        requireThat(process.argv.length === 3, 'Missing SQL backup path.');
        await restoreFile(clientPath, user, port, process.argv[2]);
        return;
    }

    const client = openClassicClient(clientPath, user, port);
    let completed;
    try {
        await client.query("SET SESSION time_zone = '+00:00'");
        completed = await runCopy(mode, client.query);
    } finally {
        await client.close();
    }
    console.log(completed);
}

if (require.main === module) {
    main().catch(error => {
        console.error(process.env.CAMPUS_COPY_MODE + ' failed: ' + String(error.message || error).split(/\r?\n/, 1)[0]);
        process.exitCode = error.nativeCode || 1;
    });
}

module.exports = {openClassicClient, runCopy};
