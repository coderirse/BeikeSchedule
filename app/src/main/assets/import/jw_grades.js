/**
 * 北科教务成绩+考试+学业进度抓取脚本。在已登录的 byyt 页面注入，同源 fetch 复用 SESSION。
 * grcjcx 为 JSON POST（与课表接口的 form 提交不同），getgpa/getXss 为 form POST，
 * queryXflbyq/queryBxkqk 为 JSON POST（参数依赖 getXss 的培养方案标识），
 * queryXsksByxhList 参数带 p 前缀（pxn/pxq/ppylx）。
 * 结果经 BeikeGrades 桥回传：onGradesResult(gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk)。
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

    /** 学业进度：getXss 取培养方案标识 → 并发查学分类别要求 + 毕业总进度；失败回退空串不阻塞成绩。 */
    function fetchProgress(xn, xq) {
        return postForm('/cjgl/cjzhtjcx/cjcx/getXss', {}).then(function (text) {
            var xs = (JSON.parse(text).content || [])[0] || {};
            var body = {
                xh: xs.xh || '', pylx: xs.pylx || '1', nj: xs.nj || '',
                jzxnxq: xn + xq, xjid: xs.xjid || '', fah: xs.fah || ''
            };
            var bxkqkBody = Object.assign({ sfcxxfj: '0' }, body);
            return Promise.all([
                postJson('/cjgl/cjzhtjcx/cjcx/queryXflbyq', body),
                postJson('/cjgl/cjzhtjcx/cjcx/queryBxkqk', bxkqkBody)
            ]);
        }).catch(function () { return ['', '']; });
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
            }),
            postForm('/UserManager/queryxsxx', {}).catch(function () { return ''; }),
            postForm('/kscxtj/queryXsksByxhList', {
                pxn: sem.XN, pxq: sem.XQ, ppylx: '1', pageNum: 1, pageSize: 100
            }).catch(function () { return ''; }),
            fetchProgress(sem.XN, sem.XQ)
        ]).then(function (all) {
            window.__beikeGradesRunning = false;
            // all[0]=getgpa, all[1]=grcjcx, all[2]=queryxsxx, all[3]=考试, all[4]=[xflbyq, bxkqk]
            window.BeikeGrades.onGradesResult(all[0], all[1], rs[1], all[2], rs[0], all[3], all[4][0], all[4][1]);
        });
    }).catch(function (e) {
        window.__beikeGradesRunning = false;
        window.BeikeGrades.onError(String(e));
    });
})();
