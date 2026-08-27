/**
 * 北科本研一体化教务系统（byyt.ustb.edu.cn）课表抓取脚本。
 * 在已登录的 /authentication/main 页面注入，同源 fetch 复用 SESSION。
 * 接口定义见 docs/TECH_DESIGN.md 2.1 节。
 */
(function () {
    if (window.__beikeRunning) return;
    window.__beikeRunning = true;

    function post(url, params) {
        return fetch(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: new URLSearchParams(params).toString(),
            credentials: 'same-origin'
        }).then(function (r) { return r.text(); });
    }

    post('/component/querydangqianxnxq', {})
        .then(function (semText) {
            var sem = JSON.parse(semText);
            if (!sem || !sem.XN) throw new Error('未获取到当前学期，请确认已登录');
            return Promise.all([
                post('/xszykb/querykbsffb', { xn: sem.XN, xq: sem.XQ }),
                post('/xszykb/queryxszykbzong', { xn: sem.XN, xq: sem.XQ }),
                post('/component/queryKbjg', { xn: sem.XN, xq: sem.XQ, pylx: '1' }),
                post('/component/queryRlZcSj', { xn: sem.XN, xq: sem.XQ, djz: '1' })
            ]).then(function (rs) {
                window.BeikeImport.onResult(semText, rs[0], rs[1], rs[2], rs[3]);
            });
        })
        .catch(function (e) {
            window.__beikeRunning = false;
            window.BeikeImport.onError(String(e));
        });
})();
