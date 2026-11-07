const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../../main/assets/import/jw_exams.js'), 'utf8');

async function run(exams, { semester = '{"XN":"2026-2027","XQ":"1"}', status = 200, networkError = false, twice = false } = {}) {
    const calls = [];
    const messages = [];
    let complete;
    const done = new Promise(resolve => { complete = resolve; });
    const context = vm.createContext({
        URLSearchParams, AbortSignal,
        window: { BeikeExams: { postMessage(text) { messages.push(JSON.parse(text)); complete(); } } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            if (networkError) throw new Error('network unavailable');
            const isSemester = url.includes('querydangqianxnxq');
            return { ok: isSemester || status === 200, status, text: async () => isSemester ? semester : exams };
        },
    });
    vm.runInContext(script, context);
    if (twice) vm.runInContext(script, context);
    await done;
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(context.window.__beikeExamsRunning, false, 'must permit retry after completion');
    return { calls, messages };
}

test('exam refresh only requests semester and exams with correct form parameters', async () => {
    const text = '{"total":1,"list":[{"KCMC":"机械设计","KSSJMS":"2027-01-15 08:00~09:50"}]}';
    const { calls, messages } = await run(text, { twice: true });
    assert.deepEqual(calls.map(x => x.url), ['/component/querydangqianxnxq', '/kscxtj/queryXsksByxhList']);
    const params = new URLSearchParams(calls[1].options.body);
    assert.equal(params.get('pxn'), '2026-2027');
    assert.equal(params.get('pxq'), '1');
    assert.equal(params.get('ppylx'), '1');
    assert.equal(calls[1].options.credentials, 'same-origin');
    assert.equal(messages.length, 1);
    assert.equal(messages[0].fn, 'onExamsResult');
    assert.equal(messages[0].args[1], text);
});

test('a successful empty list is a valid result', async () => {
    const { messages } = await run('{"total":0,"list":[]}');
    assert.equal(messages[0].fn, 'onExamsResult');
    assert.deepEqual(JSON.parse(messages[0].args[1]).list, []);
});

test('expired login stops before fetching exams', async () => {
    const { calls, messages } = await run('', { semester: '<html>login</html>' });
    assert.equal(calls.length, 1);
    assert.equal(messages[0].fn, 'onError');
    assert.match(messages[0].args[0], /会话已过期/);
});

for (const response of ['<html>login</html>', '{"error":"failed"}', 'not json', '{"list":null}']) {
    test(`invalid exam response reports error instead of clearing local exams: ${response}`, async () => {
        const { messages } = await run(response);
        assert.equal(messages.length, 1);
        assert.equal(messages[0].fn, 'onError');
    });
}

test('HTTP failure preserves failure semantics', async () => {
    const { messages } = await run('', { status: 500 });
    assert.equal(messages[0].fn, 'onError');
    assert.match(messages[0].args[0], /HTTP 500/);
});

test('network failure returns an error and releases the running flag', async () => {
    const { messages } = await run('', { networkError: true });
    assert.equal(messages[0].fn, 'onError');
    assert.match(messages[0].args[0], /network unavailable/);
});
