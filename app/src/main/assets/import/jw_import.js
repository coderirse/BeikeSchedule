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
        }).then(function (r) {
            // 会话过期被 302 到登录页、接口 5xx 时 r.text() 会拿到 HTML，
            // 下游 JSON.parse 报英文错直接铺到中文界面。显式抛 HTTP 状态更可读。
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.text();
        });
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
                    // queryzclist 返回**包装**结构 {content:[{ZC}]}（见 docs/JWXT_API.md），
                    // 此前按裸数组解析 → `.map is not a function` 抛异常 → 被下面的 catch
                    // 静默吞掉 → zcList 恒为空。后果是兜底路径退化成硬编码的 25 周顺序请求，
                    // 且 totalWeeks 只能取校历长度。两种形态都兼容。
                    var raw = JSON.parse(rs[4]);
                    var arr = Array.isArray(raw) ? raw : ((raw && raw.content) || []);
                    zcList = arr
                        .map(function (e) { return e && e.ZC; })
                        .filter(function (z) { return typeof z === 'number' && z >= 1 && z <= 90; });
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
                    // 成功路径也必须复位重入标志：否则"手动抓取"按钮在首次成功后
                    // 变成静默无操作的空按钮（jw_grades.js 一直在成功路径复位，此处是漏改）。
                    window.__beikeRunning = false;
                    window.BeikeImport.onResult(semText, rs[0], rs[1], rs[2], rs[3], calendar);
                });
            });
        })
        .catch(function (e) {
            window.__beikeRunning = false;
            window.BeikeImport.onError(String(e));
        });
})();
