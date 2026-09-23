import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, mkdir, cp, writeFile, readFile, rename, access} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {spawn} from 'node:child_process';
import {createServer} from 'node:net';

const project = resolve(import.meta.dirname, '../../..');
async function fixture() {
    const root = await mkdtemp(join(tmpdir(), 'db-companion runtime-'));
    await mkdir(join(root, 'scripts'));
    for (const file of ['startup.sh', 'shutdown.sh', 'restart.sh', 'scripts/app-runtime.sh']) {
        await cp(join(project, file), join(root, file));
    }
    await mkdir(join(root, '.run'));
    return root;
}
function run(root, script, env = {}) {
    return new Promise((resolveRun, reject) => {
        // Deliberately launch from outside the project; paths containing spaces are supported.
        const child = spawn('/bin/bash', [join(root, script)], {
            cwd: tmpdir(), env: {...process.env, ...env}, stdio: ['ignore', 'pipe', 'pipe'],
        });
        let output = '';
        child.stdout.on('data', chunk => { output += chunk; });
        child.stderr.on('data', chunk => { output += chunk; });
        child.on('error', reject);
        child.on('close', code => resolveRun({code, output}));
    });
}
async function exists(path) {
    try { await access(path); return true; } catch { return false; }
}

test('shutdown is idempotent and rejects malformed or unrelated live PIDs', async () => {
    const root = await fixture();
    assert.equal((await run(root, 'shutdown.sh')).code, 0);
    for (const invalid of ['0', '-1', '1', 'abc', '12\n34']) {
        await writeFile(join(root, '.run/app.pid'), invalid);
        assert.notEqual((await run(root, 'shutdown.sh')).code, 0);
        assert.equal(await readFile(join(root, '.run/app.pid'), 'utf8'), invalid);
    }
    await writeFile(join(root, '.run/app.pid'), String(process.pid));
    const rejected = await run(root, 'shutdown.sh');
    assert.notEqual(rejected.code, 0, rejected.output);
    assert.match(rejected.output, /일치하지 않습니다/);
    assert.equal(await readFile(join(root, '.run/app.pid'), 'utf8'), String(process.pid));
});

test('concurrent operations fail closed and missing build does not launch', async () => {
    const root = await fixture();
    const missing = await run(root, 'startup.sh');
    assert.notEqual(missing.code, 0);
    assert.match(missing.output, /target/);
    await mkdir(join(root, '.run/lifecycle.lock'));
    assert.notEqual((await run(root, 'startup.sh')).code, 0);
    assert.notEqual((await run(root, 'shutdown.sh')).code, 0);
    assert.equal(await exists(join(root, '.run/lifecycle.lock')), true);
});

// Optional integration test uses the already-built real app; no database connection or AI call.
test('real app: copy, PID, readiness, duplicate guard, preflight, restart, shutdown', {
    skip: !process.env.RUNTIME_TEST_JAR || !process.env.JAVA_HOME,
    timeout: 90000,
}, async t => {
    const root = await fixture();
    await mkdir(join(root, 'target'));
    const sourceJar = join(root, 'target/db-manage-companion-test.jar');
    await cp(resolve(process.env.RUNTIME_TEST_JAR), sourceJar);
    const reservation = createServer();
    await new Promise(resolveListen => reservation.listen(0, '127.0.0.1', resolveListen));
    const port = String(reservation.address().port);
    const env = {PORT: port, STARTUP_TIMEOUT: '20', SHUTDOWN_TIMEOUT: '15'};
    try {
        const busy = await run(root, 'startup.sh', env);
        assert.notEqual(busy.code, 0, busy.output);
        assert.match(busy.output, /이미 사용 중/);
        assert.equal(await exists(join(root, '.run/app.pid')), false);
    } finally {
        await new Promise(resolveClose => reservation.close(resolveClose));
    }
    t.after(async () => { await run(root, 'shutdown.sh', env); });
    const started = await run(root, 'startup.sh', env);
    assert.equal(started.code, 0, started.output);
    const pid = (await readFile(join(root, '.run/app.pid'), 'utf8')).trim();
    assert.match(pid, /^[1-9][0-9]+$/);
    assert.equal((await fetch(`http://127.0.0.1:${port}/login`)).status, 200);
    assert.deepEqual(await readFile(join(root, '.run/app.jar')), await readFile(sourceJar));
    assert.notEqual((await run(root, 'startup.sh', env)).code, 0);
    await rename(sourceJar, `${sourceJar}.held`);
    assert.notEqual((await run(root, 'restart.sh', env)).code, 0);
    assert.equal((await readFile(join(root, '.run/app.pid'), 'utf8')).trim(), pid);
    assert.equal((await fetch(`http://127.0.0.1:${port}/login`)).status, 200);
    await rename(`${sourceJar}.held`, sourceJar);
    // Preserve the previous port and remembered Java when environment overrides are omitted.
    const restarted = await run(root, 'restart.sh', {...env, PORT: '', JAVA_HOME: ''});
    assert.equal(restarted.code, 0, restarted.output);
    assert.notEqual((await readFile(join(root, '.run/app.pid'), 'utf8')).trim(), pid);
    assert.equal((await fetch(`http://127.0.0.1:${port}/login`)).status, 200);
    assert.equal((await run(root, 'shutdown.sh', env)).code, 0);
    assert.equal(await exists(join(root, '.run/app.pid')), false);
    assert.equal(await exists(join(root, '.run/app.identity')), false);
    assert.equal(await exists(join(root, '.run/app.jar')), true);
    assert.equal(await exists(join(root, 'logs/app.log')), true);
    assert.equal((await run(root, 'shutdown.sh', env)).code, 0);
});
