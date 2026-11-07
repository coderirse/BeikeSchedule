/** 考试页独立刷新。已登录 byyt 的同源会话，只查当前学期和考试安排。 */
(function () {
    if (window.__beikeExamsRunning) return;
    window.__beikeExamsRunning = true;

    function send(fn, args) {
        window.BeikeExams.postMessage(JSON.stringify({ fn: fn, args: args }));
    }

    function parseJson(text) {
        if (typeof text !== 'string' || text.trim().charAt(0) === '<') {
            throw new Error('会话已过期，请重新登录教务系统后重试');
        }
        try { return JSON.parse(text); }
        catch (e) { throw new Error('教务响应格式异常，请重新登录后重试'); }
    }

    function post(url, params) {
        return fetch(url, {
            method: 'POST', credentials: 'same-origin', signal: AbortSignal.timeout(20000),
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: new URLSearchParams(params).toString()
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        });
    }

    post('/component/querydangqianxnxq', {}).then(function (semesterText) {
        var semester = parseJson(semesterText);
        if (!semester || !semester.XN || !semester.XQ) throw new Error('未获取到当前学期，请确认已登录');
        return post('/kscxtj/queryXsksByxhList', {
            pxn: semester.XN, pxq: semester.XQ, ppylx: '1', pageNum: 1, pageSize: 100
        }).then(function (examsText) {
            var exams = parseJson(examsText);
            if (!exams || !Array.isArray(exams.list)) throw new Error('考试响应格式异常，已保留上次数据');
            send('onExamsResult', [semesterText, examsText]);
        });
    }).catch(function (e) {
        send('onError', [String(e)]);
    }).finally(function () {
        window.__beikeExamsRunning = false;
    });
})();
