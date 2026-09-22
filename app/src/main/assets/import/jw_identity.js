/**
 * 云登录身份抓取脚本：在已登录的 byyt 页面注入，只做一件事——
 * 从 /user/me 取学号（yhdm）与姓名（xm），经 BeikeIdentity 桥回传。
 *
 * 用户在统一身份认证页完成登录（扫码或账密均可）后，WebView 到达教务主页时由
 * CloudLoginScreen 注入本脚本；学号回传给客户端后由客户端向 api.caeamer.com 换 token
 * （账号 = 学号，无密码——教务登录本身就是身份验证）。
 *
 * queryxsxx 作为兜底（部分账号 user/me 缺 yhdm 时从学籍接口取 xh/xm），
 * 失败不阻塞：send('onError') 之前先尽力回传非空学号。
 */
(function () {
    if (window.__beikeIdentityRunning) return;
    window.__beikeIdentityRunning = true;

    function send(fn, args) {
        try {
            window.BeikeIdentity.postMessage(JSON.stringify({ fn: fn, args: args }));
        } catch (e) { /* 桥不可用 */ }
    }

    function fetchOpts(extra) {
        return Object.assign({ credentials: 'same-origin', signal: AbortSignal.timeout(20000) }, extra);
    }

    function postForm(url) {
        return fetch(url, fetchOpts({
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
            body: ''
        })).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            // 未登录时教务会 302 到 /authentication/require（且 Location 是 http:// 明文，
            // 会被 WebView 的禁止混合内容策略拦成 TypeError: Failed to fetch）。
            // 显式识别"被重定向到登录页"，报可读的未登录提示而不是底层网络错误。
            if (r.redirected && /\/authentication\/require/.test(r.url)) {
                throw new Error('尚未登录教务系统，请先完成登录后再抓取');
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
        window.__beikeIdentityRunning = false;
        send('onError', [String(e)]);
    });
})();
