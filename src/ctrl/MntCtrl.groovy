package ctrl

import cn.xnatural.app.CacheSrv
import cn.xnatural.app.ServerTpl
import cn.xnatural.enet.event.EL
import cn.xnatural.http.*
import cn.xnatural.jpa.Repo
import com.alibaba.fastjson.JSON
import entity.Decision
import entity.OpHistory
import entity.User
import entity.UserSession

import java.text.SimpleDateFormat
import java.util.concurrent.ConcurrentHashMap

@Ctrl(prefix = 'mnt')
class MntCtrl extends ServerTpl {

    @Lazy def repo = bean(Repo, 'jpa_rule_repo')
    protected final Set<WebSocket> wss = ConcurrentHashMap.newKeySet()


    @EL(name = 'wsMsg_rule')
    void wsMsgBroadcast(String msg) { wss.each {ws -> ws.send(msg)} }

    @WS(path = 'ws')
    void receiveWs(WebSocket ws) {
        log.info('WS connect. {}', ws.session.getRemoteAddress())
        ws.listen(new WsListener() {

            @Override
            void onClose(WebSocket wst) { wss.remove(wst) }

            @Override
            void onText(String msg) {
                log.info('rule mnt ws receive client msg: {}', msg)
            }
        })
        wss.add(ws)
    }


    /**
     * 登录
     * @param username 用户名
     * @param password 密码
     */
    @Path(path = 'login')
    ApiResp login(String username, String password, HttpContext hCtx) {
        if (!username) return ApiResp.fail('Param username not empty')
        if (!password) return ApiResp.fail('Param password not empty')
        def user = repo.find(User) {root, query, cb -> cb.equal(root.get('name'), username)}
        if (!user) return ApiResp.fail("用户不存在")
        def pIds = user.permissions?.split(',')?.toList()?.toSet()?:Collections.emptySet()
        hCtx.setAttr('permissions', pIds)
        hCtx.auth("mnt-login")
        if (password != user.password) return ApiResp.fail('密码错误')
        bean(CacheSrv)?.set("permission_" + user.id, pIds)

        hCtx.setSessionAttr('uId', user.id)
        hCtx.setSessionAttr('uName', user.name)
        hCtx.setSessionAttr('uGroup', user.group)

        user.login = new Date()
        repo.saveOrUpdate(user)
        repo.saveOrUpdate(new UserSession(valid: true, sessionId: hCtx.sessionId, userId: user.id, data: JSON.toJSONString([uId: user.id, uName: user.name, uGroup: user.group])))
        ApiResp.ok().attr('id', user.id).attr('name', username).attr('permissionIds', pIds)
    }


    /**
     * 获取当前 会话 中的用户信息
     */
    @Path(path = 'currentUser', method = 'get')
    ApiResp currentUser(HttpContext hCtx) {
        return ApiResp.ok().attr('id', hCtx.getSessionAttr('uId'))
                .attr('name', hCtx.getSessionAttr('uName'))
                .attr('permissionIds', hCtx.getAttr('permissions', Set))
    }


    /**
     * 退出会话
     */
    @Path(path = 'logout')
    ApiResp logout(HttpContext hCtx) {
        hCtx.removeSessionAttr('uId')
        hCtx.removeSessionAttr('uName')
        hCtx.regFinishedFn {
            def session = repo.findById(UserSession, hCtx.sessionId)
            if (session && session.valid) {
                session.valid = false
                repo.saveOrUpdate(session)
            }
        }
        ApiResp.ok()
    }


    @Path(path = 'opHistoryPage')
    ApiResp opHistoryPage(HttpContext hCtx, Integer page, Integer pageSize, String kw, String type, String startTime, String endTime) {
        if (pageSize && pageSize > 20) return ApiResp.fail("Param pageSize <=20")
        hCtx.auth("opHistory-read")
        Date start = startTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(startTime) : null
        Date end = endTime ? new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(endTime) : null
        ApiResp.ok(
                repo.findPage(OpHistory, page, pageSize?:5) { root, query, cb ->
                    query.orderBy(cb.desc(root.get('createTime')))
                    def ps = []
                    if (kw) ps << cb.like(root.get('content'), '%' + kw + '%')
                    if (start) ps << cb.greaterThanOrEqualTo(root.get('createTime'), start)
                    if (end) ps << cb.lessThanOrEqualTo(root.get('createTime'), end)
                    if (type) {
                        ps << cb.equal(root.get('tbName'), repo.tbName(Class.forName(Decision.package.name + "." + type)).replace("`", ""))
                    }
                    ps ? ps.inject {p1, p2 -> cb.and(p1, p2)} : null
                }
        )
    }
}
