/**
 * 北科教务成绩抓取脚本。在已登录的 byyt 页面注入，同源 fetch 复用 SESSION。
 * grcjcx 为 JSON POST（与课表接口的 form 提交不同），getgpa 为 form POST；
 * 请求体里的 yhdm/pylx 从 user/me 取。
 * 结果经 BeikeGrades 桥回传：onGradesResult(gpaJson, gradesJson)。
 */
(function () {
    if (window.__beikeGradesRunning) return;
    window.__beikeGradesRunning = true;

    function postForm(url, params) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: new URLSearchParams(params).toString(),
            credentials: 'same-origin'
        }).then(function (r) { return r.text(); });
    }

    function postJson(url, body) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json;charset=UTF-8' },
            body: JSON.stringify(body),
            credentials: 'same-origin'
        }).then(function (r) { return r.text(); });
    }

    Promise.all([
        postForm('/component/querydangqianxnxq', {}),
        postForm('/user/me', {})
    ]).then(function (rs) {
        var sem = JSON.parse(rs[0]);
        if (!sem || !sem.XN) throw new Error('未获取到当前学期，请确认已登录');
        var me = {};
        try { me = JSON.parse(rs[1]); } catch (e) { /* 匿名兜底 */ }
        return Promise.all([
            postForm('/cjgl/grcjcx/getgpa', {}),
            postJson('/cjgl/grcjcx/grcjcx', {
                xn: null, xq: null, kcmc: null, cxbj: '-1',
                pylx: me.pylx || '1',
                current: 1, pageSize: 500,
                xscjlb: null, sffx: null, yhdm: me.yhdm || null
            })
        ]).then(function (results) {
            window.__beikeGradesRunning = false;
            window.BeikeGrades.onGradesResult(results[0], results[1]);
        });
    }).catch(function (e) {
        window.__beikeGradesRunning = false;
        window.BeikeGrades.onError(String(e));
    });
})();
