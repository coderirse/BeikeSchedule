const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/import/jw_grades.js'), 'utf8');

async function run({ gradesOnly = true, grades = '{"content":{"list":[]}}', status = 200 } = {}) {
    const calls = [];
    let complete;
    const done = new Promise(resolve => { complete = resolve; });
    const context = vm.createContext({
        URLSearchParams, AbortSignal,
        window: { __beikeGradesOnly: gradesOnly, BeikeGrades: {
            postMessage: text => complete(JSON.parse(text)),
        } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            const isGrades = url === '/cjgl/grcjcx/grcjcx';
            const text = url.includes('querydangqianxnxq') ? '{"XN":"2026-2027","XQ":"1"}'
                : isGrades ? grades : '{}';
            return { ok: !isGrades || status === 200, status, text: async () => text };
        },
    });
    vm.runInContext(script, context);
    // Repeated page-ready callbacks must not start duplicate requests.
    vm.runInContext(script, context);
    const result = await done;
    assert.equal(context.window.__beikeGradesRunning, false);
    assert.equal(calls.filter(c => c.url === '/cjgl/grcjcx/grcjcx').length, 1);
    return { calls, result };
}

test('grades-only fetch skips exams and keeps the eight-argument bridge contract', async () => {
    const { calls, result } = await run();
    assert.ok(calls.some(c => c.url === '/cjgl/cjzhtjcx/cjcx/queryXflbyq'));
    assert.ok(calls.some(c => c.url === '/cjgl/cjzhtjcx/cjcx/queryBxkqk'));
    assert.ok(!calls.some(c => /kscxtj|xszykb|\/api\/bs/.test(c.url)));
    assert.equal(result.fn, 'onGradesResult');
    assert.equal(result.args.length, 8);
    assert.equal(result.args[5], '');
});

test('complete sync still fetches exams', async () => {
    const { calls, result } = await run({ gradesOnly: false });
    assert.ok(calls.some(c => c.url === '/kscxtj/queryXsksByxhList'));
    assert.equal(result.fn, 'onGradesResult');
});

for (const grades of ['<html>login</html>', '{"error":"failed"}', '{"content":{"list":null}}']) {
    test(`invalid grades preserve failure semantics: ${grades}`, async () => {
        const { result } = await run({ grades });
        assert.equal(result.fn, 'onError');
    });
}

test('HTTP failures release the guard and report failure', async () => {
    const { result } = await run({ status: 500 });
    assert.equal(result.fn, 'onError');
});
