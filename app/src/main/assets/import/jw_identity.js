/**
 * 云登录身份抓取脚本：仅在已登录的 byyt 教务页面注入，
 * 从 /user/me 取学号（yhdm）与姓名（xm），经 BeikeIdentity 桥回传。
 *
 * 用户在统一身份认证页完成登录（扫码或账密均可）后，WebView 到达教务主页时由
 * CloudLoginScreen 注入本脚本；学号回传给客户端后由客户端向自有服务器（IP 直连）换 token。
 *
 * **必须先校验 host**：相对路径 /user/me 会打到「当前主框架域」。
 * 在 SSO（sso.ustb.edu.cn）或微认证（sis.ustb.edu.cn）上注入会得到 404，
 * 界面曾显示无上下文的 "Error: HTTP 404" 卡在扫码页。
 *
 * queryxsxx 作为兜底（部分账号 user/me 缺 yhdm 时从学籍接口取 xh/xm）。
 */
(function () {
    if (window.__beikeIdentityRunning) return;
    window.__beikeIdentityRunning = true;

    function send(fn, args) {
        try {
            window.BeikeIdentity.postMessage(JSON.stringify({ fn: fn, args: args }));
        } catch (e) { /* 桥不可用 */ }
    }

    function fail(message) {
        window.__beikeIdentityRunning = false;
        send('onError', [message]);
    }

    // 只在教务本体域抓身份：否则相对路径会 404/打到错误站点
    var host = String(location.host || '').toLowerCase();
    if (host !== 'byyt.ustb.edu.cn') {
        fail('请先登录并进入教务系统主页后再获取（当前不在教务页）');
        return;
    }

    function fetchOpts(extra) {
        return Object.assign({ credentials: 'same-origin', signal: AbortSignal.timeout(20000) }, extra);
    }

    function postForm(path) {
        return fetch(path, fetchOpts({
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: ''
        })).then(function (r) {
            if (r.redirected && /\/authentication\/require/.test(r.url)) {
                throw new Error('尚未登录教务系统，请先完成登录后再抓取');
            }
            if (!r.ok) {
                throw new Error('教务接口 ' + path + ' 返回 HTTP ' + r.status);
            }
            return r.text();
        }, function (e) {
            if (e && e.name === 'AbortError') throw new Error('请求超时，请检查网络后重试');
            throw new Error('尚未登录教务系统，请先完成登录后再抓取');
        });
    }

    Promise.all([
        postForm('/user/me'),
        postForm('/UserManager/queryxsxx').catch(function () { return ''; })
    ]).then(function (rs) {
        window.__beikeIdentityRunning = false;
        var me = {};
        try { me = JSON.parse(rs[0]); } catch (e) { /* 会话过期时是 HTML */ }
        var xsxx = {};
        try { xsxx = JSON.parse(rs[1]); } catch (e) { /* 兜底失败可忽略 */ }
        var xh = me.yhdm || me.xh || xsxx.xh || '';
        var xm = me.xm || xsxx.XM || xsxx.xm || '';
        if (!xh) throw new Error('未获取到学号，请确认已登录教务系统');
        send('onIdentity', [xh, xm]);
    }).catch(function (e) {
        fail(String(e && e.message ? e.message : e));
    });
})();
