/**
 * 北科本研一体化教务系统（byyt.ustb.edu.cn）课表抓取脚本。
 * 在已登录的 /authentication/main 页面注入，同源 fetch 复用 SESSION。
 * 接口定义见 docs/TECH_DESIGN.md 2.1 节。
 *
 * 教学周日历（修国庆跳周）：优先 Xiaoli/queryMonthList 一次取全量校历
 * （需 RoleCode 头；xlList 按周 7 条、每天一条，MON/TUES/... 字段只有一个非空），
 * 失败则逐周 queryRlZcSj 兜底。产出统一结构：
 *   {"totalWeeks":18, "weeks":[{"zc":1,"monday":"2026-09-07"}, ...]}
 */
(function () {
    if (window.__beikeRunning) return;
    window.__beikeRunning = true;

    function post(url, params, headers) {
        return fetch(url, {
            method: 'POST',
            headers: Object.assign({
                'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
            }, headers || {}),
            body: new URLSearchParams(params).toString(),
            credentials: 'same-origin'
        }).then(function (r) { return r.text(); });
    }

    /** 校历接口 → 统一周历结构；失败返回 null（由调用方兜底）。 */
    function calendarFromXiaoli(xn, xq) {
        return post('/Xiaoli/queryMonthList', { xn: xn, xq: xq }, { RoleCode: '01' })
            .then(function (text) {
                var data = JSON.parse(text);
                var semKey = xn + xq; // xlList 里 XNXQ 形如 "2026-20271"
                var weeks = (data.xlList || [])
                    .filter(function (e) { return e.XNXQ === semKey && e.MON && e.ZC >= 1 && e.ZC <= 90; })
                    .map(function (e) { return { zc: e.ZC, monday: e.MON }; })
                    .sort(function (a, b) { return a.zc - b.zc; });
                if (!weeks.length) return null;
                return weeks;
            })
            .catch(function () { return null; });
    }

    /** 逐周 queryRlZcSj 兜底 → 统一周历结构。 */
    function calendarByWeekLoop(xn, xq, zcList) {
        var weeks = [];
        return zcList.reduce(function (chain, zc) {
            return chain.then(function () {
                return post('/component/queryRlZcSj', { xn: xn, xq: xq, djz: String(zc) })
                    .then(function (text) {
                        var content = (JSON.parse(text) || {}).content || [];
                        var mon = content.filter(function (e) { return e.xqj === '1'; })[0];
                        if (mon && mon.rq) weeks.push({ zc: zc, monday: mon.rq });
                    });
            });
        }, Promise.resolve()).then(function () {
            return weeks.length ? weeks : null;
        });
    }

    post('/component/querydangqianxnxq', {})
        .then(function (semText) {
            var sem = JSON.parse(semText);
            if (!sem || !sem.XN) throw new Error('未获取到当前学期，请确认已登录');
            return Promise.all([
                post('/xszykb/querykbsffb', { xn: sem.XN, xq: sem.XQ }),
                post('/xszykb/queryxszykbzong', { xn: sem.XN, xq: sem.XQ }),
                post('/component/queryKbjg', { xn: sem.XN, xq: sem.XQ, pylx: '1' }),
                post('/component/queryRlZcSj', { xn: sem.XN, xq: sem.XQ, djz: '1' }),
                post('/component/queryzclist', { xn: sem.XN, xq: sem.XQ })
            ]).then(function (rs) {
                var zcList = [];
                try {
                    zcList = (JSON.parse(rs[4]) || [])
                        .map(function (e) { return e.ZC; })
                        .filter(function (z) { return z >= 1 && z <= 90; });
                } catch (e) { /* 周次列表异常时由校历自行推断 */ }

                return calendarFromXiaoli(sem.XN, sem.XQ).then(function (weeks) {
                    if (weeks) return weeks;
                    var loopList = zcList.length ? zcList
                        : Array.from({ length: 25 }, function (_, i) { return i + 1; });
                    return calendarByWeekLoop(sem.XN, sem.XQ, loopList);
                }).then(function (weeks) {
                    var totalWeeks = Math.max(
                        zcList.length ? Math.max.apply(null, zcList) : 0,
                        weeks ? weeks.length : 0,
                        16
                    );
                    var calendar = JSON.stringify({ totalWeeks: totalWeeks, weeks: weeks || [] });
                    window.BeikeImport.onResult(semText, rs[0], rs[1], rs[2], rs[3], calendar);
                });
            });
        })
        .catch(function (e) {
            window.__beikeRunning = false;
            window.BeikeImport.onError(String(e));
        });
})();
